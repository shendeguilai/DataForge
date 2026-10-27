package cn.datacraft.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** Production code is sent to a trusted internal runner; the website never receives Docker access. */
@Component
public class ProgramExecution {
    private final String mode,url,secret;
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public ProgramExecution(ObjectMapper json,@Value("${dataforge.execution.mode:local}")String mode,
            @Value("${dataforge.execution.url:http://generation-runner:8780}")String url,
            @Value("${dataforge.execution.secret:}")String secret) {
        this.json=json;this.mode=mode;this.url=url;this.secret=secret;
    }
    public boolean remote(){if(!Set.of("local","remote").contains(mode))throw new IllegalStateException("程序执行模式无效");return "remote".equals(mode);}
    public void run(List<String> command,Path dir,Path input,Path output,Duration timeout)throws Exception {
        if(!remote())throw new IllegalStateException("隔离执行未启用");
        if(secret.length()<24)throw new IllegalStateException("请配置独立的 DATAFORGE_EXECUTION_SECRET 后使用隔离执行服务");
        boolean compile="g++".equals(command.get(0));
        Path request=Files.createTempFile(dir,"runner-",".request"),response=Files.createTempFile(dir,"runner-",".response");
        try {
            Map<String,Object> meta=new LinkedHashMap<>();meta.put("action",compile?"compile":"run");meta.put("timeoutSeconds",timeout.toSeconds());
            try(var zip=new ZipOutputStream(Files.newOutputStream(request))) {
                if(compile) {
                    meta.put("standard",command.stream().filter(c->c.startsWith("-std=")).findFirst().orElse("-std=c++17").substring(5));
                    Path source=command.stream().filter(c->c.endsWith(".cpp")).map(Path::of).findFirst().orElseThrow();add(zip,"source.cpp",source);
                } else {meta.put("args",command.subList(1,command.size()));add(zip,"program",Path.of(command.get(0)));if(input!=null)add(zip,"input",input);}
                zip.putNextEntry(new ZipEntry("request.json"));zip.write(json.writeValueAsBytes(meta));zip.closeEntry();
            }
            var http=HttpRequest.newBuilder(URI.create(url+"/execute")).timeout(timeout.plusSeconds(25))
                    .header("X-Execution-Key",secret).header("Content-Type","application/zip")
                    .POST(HttpRequest.BodyPublishers.ofFile(request)).build();
            var result=client.send(http,HttpResponse.BodyHandlers.ofFile(response,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE));
            if(result.statusCode()!=200)throw new IOException("隔离执行服务不可用（"+result.statusCode()+"），请稍后重试");
            if(Files.size(response)>80L*1024*1024)throw new IOException("隔离执行返回过大");
            try(var zip=new ZipFile(response.toFile())) {
                var report=zip.getEntry("result.json");if(report==null||report.getSize()>20000)throw new IOException("隔离执行结果无效");
                Map<?,?> outcome=json.readValue(zip.getInputStream(report).readNBytes(20001),Map.class);
                if(!Boolean.TRUE.equals(outcome.get("ok")))throw new IOException(String.valueOf(outcome.getOrDefault("message",null)));
                Path target=compile?Path.of(command.get(command.indexOf("-o")+1)):output;
                String entryName=compile?"program":"output";var entry=zip.getEntry(entryName);
                if(target!=null){if(entry==null||entry.getSize()<0||entry.getSize()>64L*1024*1024)throw new IOException("隔离执行输出无效");try(var in=zip.getInputStream(entry)){Files.copy(in,target,StandardCopyOption.REPLACE_EXISTING);}}
            }
        } finally {Files.deleteIfExists(request);Files.deleteIfExists(response);}
    }
    private static void add(ZipOutputStream zip,String name,Path source)throws IOException {
        if(Files.size(source)>64L*1024*1024)throw new IOException("单文件超过64MB执行上限");
        zip.putNextEntry(new ZipEntry(name));Files.copy(source,zip);zip.closeEntry();
    }
}
