package cn.datacraft.job;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.file.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OperationsService {
    private final ArtifactStorage storage;
    private final JobRepository jobs;
    private final JobRecordJpaRepository records;
    private final GenerationTaskRepository queue;
    private final int retention;
    private final boolean automatic;
    private record Candidate(Path path,long bytes,long modified,UUID job) {}
    private record Plan(String owner,Instant expires,List<Candidate> files) {}
    private final Map<String,Plan> plans=new ConcurrentHashMap<>();
    public OperationsService(ArtifactStorage storage,JobRepository jobs,JobRecordJpaRepository records,GenerationTaskRepository queue,
            @Value("${dataforge.operations.retention-days:30}")int retention,
            @Value("${dataforge.operations.cleanup-enabled:false}")boolean automatic){
        this.storage=storage;this.jobs=jobs;this.records=records;this.queue=queue;
        this.retention=Math.max(1,retention);this.automatic=automatic;
    }
    public Map<String,Object> status()throws IOException {
        long used=0;try(var files=Files.walk(storage.root())){
            var paths=files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).iterator();
            while(paths.hasNext())used+=Files.size(paths.next());
        }
        long free=Files.getFileStore(storage.root()).getUsableSpace();var alerts=new ArrayList<String>();
        if(free<1024L*1024*1024)alerts.add("磁盘可用空间不足1GB，请清理临时文件或扩容");
        Path marker=storage.root().resolve(".backup-success");
        if(!Files.isRegularFile(marker)||Files.getLastModifiedTime(marker).toInstant().isBefore(Instant.now().minus(Duration.ofHours(48))))
            alerts.add("最近48小时未记录成功备份，请检查自动备份任务（升级后首次备份前也会提示）");
        long failed=records.countByStatus("FAILED");if(failed>0)alerts.add("有 "+failed+" 个数据生成失败任务，可查看任务记录重试");
        return Map.of("freeBytesText",bytes(free),"runtimeBytesText",bytes(used),"queued",queue.countByState("QUEUED"),
                "running",queue.countByState("RUNNING"),"failed",failed,"retentionDays",automatic?retention:0,"alerts",alerts);
    }
    private List<Candidate> candidates()throws IOException {
        var result=new ArrayList<Candidate>();Instant cutoff=Instant.now().minus(Duration.ofDays(retention));
        try(var dirs=Files.list(storage.root())) {for(Path dir:dirs.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
            UUID id;try{id=UUID.fromString(dir.getFileName().toString());}catch(IllegalArgumentException e){continue;}
            var job=jobs.find(id);if(job.isEmpty()||!Set.of(JobStatus.COMPLETED,JobStatus.FAILED,JobStatus.CANCELLED).contains(job.get().getStatus())||job.get().getUpdatedAt().isAfter(cutoff))continue;
            try(var files=Files.walk(dir)) {for(var file:files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name=file.getFileName().toString();
                boolean temporary=name.matches("(standard|generator|validator|brute)(-check)?(\\.exe)?")||name.matches(".*\\.(err|tmp|request|response)$")
                        ||file.getParent().equals(dir.resolve("data"))&&Files.isRegularFile(storage.finalZip(id),LinkOption.NOFOLLOW_LINKS);
                long modified=Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toMillis();
                if(temporary&&Instant.ofEpochMilli(modified).isBefore(cutoff))result.add(new Candidate(file,Files.size(file),modified,id));
            }}
        }}return result;
    }
    public Map<String,Object> preview(String owner)throws IOException {
        plans.entrySet().removeIf(e->e.getValue().expires.isBefore(Instant.now()));
        if(plans.size()>100)throw new IllegalStateException("清理预览过多，请稍后重试");
        var files=candidates();String token=UUID.randomUUID().toString();plans.put(token,new Plan(owner,Instant.now().plusSeconds(600),files));
        return Map.of("token",token,"count",files.size(),"bytesText",bytes(files.stream().mapToLong(Candidate::bytes).sum()));
    }
    public int cleanup(String owner,String token)throws IOException {
        Plan plan=plans.remove(token);if(plan==null||!plan.owner.equals(owner)||plan.expires.isBefore(Instant.now()))throw new IllegalArgumentException("预览已过期，请重新预览");
        Path root=storage.root().toRealPath();int count=0;
        for(var c:plan.files) {
            var job=jobs.find(c.job);if(job.isEmpty()||!Set.of(JobStatus.COMPLETED,JobStatus.FAILED,JobStatus.CANCELLED).contains(job.get().getStatus())||job.get().getUpdatedAt().isAfter(Instant.now().minus(Duration.ofDays(retention))))continue;
            if(c.path.getParent().equals(storage.root().resolve(c.job.toString()).resolve("data"))
                    && !Files.isRegularFile(storage.finalZip(c.job),LinkOption.NOFOLLOW_LINKS))continue;
            if(!Files.isRegularFile(c.path,LinkOption.NOFOLLOW_LINKS)||!c.path.getParent().toRealPath().startsWith(root))continue;
            if(Files.size(c.path)!=c.bytes||Files.getLastModifiedTime(c.path).toMillis()!=c.modified)continue;
            Files.delete(c.path);count++;
        }return count;
    }
    @Scheduled(cron="0 20 3 * * *",zone="Asia/Shanghai")public void automaticCleanup()throws IOException {
        if(automatic)cleanup("system",String.valueOf(preview("system").get("token")));
    }
    private static String bytes(long value){return String.format(Locale.ROOT,"%.1f MB",value/1048576.0);}
}
