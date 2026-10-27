package cn.datacraft.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

class ProgramExecutionTest {
    @TempDir Path dir;
    private final ObjectMapper json=new ObjectMapper();

    @Test void remoteCompilerAndRunnerExchangeBoundedArchivesAndCredentials() throws Exception {
        String secret="test-only-independent-secret-123456";
        AtomicInteger calls=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/execute",exchange->{
            try {
                if(!secret.equals(exchange.getRequestHeaders().getFirst("X-Execution-Key"))) {
                    exchange.sendResponseHeaders(403,-1);return;
                }
                Map<String,byte[]> files=new HashMap<>();
                try(var in=new ZipInputStream(exchange.getRequestBody())) {
                    ZipEntry entry;while((entry=in.getNextEntry())!=null)files.put(entry.getName(),in.readAllBytes());
                }
                Map<?,?> meta=json.readValue(files.get("request.json"),Map.class);
                String action=(String)meta.get("action");
                if("compile".equals(action)) {
                    assertThat(meta.get("standard")).isEqualTo("c++20");
                    assertThat(new String(files.get("source.cpp"))).contains("int main");
                } else {
                    assertThat(meta.get("args")).isEqualTo(List.of("123"));
                    assertThat(files.get("program")).isEqualTo("binary".getBytes());
                    assertThat(files.get("input")).isEqualTo("input".getBytes());
                }
                var bytes=new ByteArrayOutputStream();
                try(var out=new ZipOutputStream(bytes)) {
                    out.putNextEntry(new ZipEntry("result.json"));out.write("{\"ok\":true}".getBytes());out.closeEntry();
                    out.putNextEntry(new ZipEntry("compile".equals(action)?"program":"output"));
                    out.write(("compile".equals(action)?"binary":"answer").getBytes());out.closeEntry();
                }
                byte[] body=bytes.toByteArray();calls.incrementAndGet();exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            } finally {exchange.close();}
        });
        server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort();
            var execution=new ProgramExecution(json,"remote",url,secret);
            Path source=Files.writeString(dir.resolve("source.cpp"),"int main(){}");
            Path program=dir.resolve("program"),input=Files.writeString(dir.resolve("input"),"input"),output=dir.resolve("output");
            execution.run(List.of("g++","-std=c++20",source.toString(),"-o",program.toString()),dir,null,null,Duration.ofSeconds(5));
            assertThat(program).hasContent("binary");
            execution.run(List.of(program.toString(),"123"),dir,input,output,Duration.ofSeconds(5));
            assertThat(output).hasContent("answer");assertThat(calls.get()).isEqualTo(2);
            var unauthorized=new ProgramExecution(json,"remote",url,"wrong-test-secret-1234567890");
            assertThatThrownBy(()->unauthorized.run(List.of(program.toString()),dir,input,output,Duration.ofSeconds(5)))
                    .isInstanceOf(IOException.class).hasMessageContaining("403");
            try(var files=Files.list(dir)){assertThat(files.map(p->p.getFileName().toString()).toList())
                    .noneMatch(name->name.startsWith("runner-")&&(name.endsWith(".request")||name.endsWith(".response")));}
        } finally {server.stop(0);}
    }
}
