package cn.datacraft.web;

import cn.datacraft.job.*;
import cn.datacraft.user.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.security.Principal;
import java.nio.file.Files;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;
    private final UserService users;
    public JobController(JobService jobs, UserService users) { this.jobs = jobs; this.users = users; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerationJob create(@Valid @RequestBody GenerationRequest request, Principal principal) {
        UserAccount user = currentUser(principal);
        return jobs.create(request, user.getId(), user.getDailyGenerationLimit());
    }

    @GetMapping("/{id}")
    public GenerationJob get(@PathVariable UUID id, Principal principal) { return jobs.requireOwned(id, userId(principal)); }

    @GetMapping
    public List<GenerationJob> recent(Principal principal) { return jobs.recent(userId(principal)); }

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerationJob confirm(@PathVariable UUID id, Principal principal) {
        jobs.requireOwned(id, userId(principal));
        return jobs.confirm(id);
    }

    @PostMapping("/{id}/retry-plan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerationJob retryPlan(@PathVariable UUID id, Principal principal) {
        jobs.requireOwned(id, userId(principal));
        return jobs.retryPlan(id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable UUID id, Principal principal) throws IOException {
        GenerationJob job = jobs.requireOwned(id, userId(principal));
        if (!job.isDownloadReady()) throw new IllegalStateException("数据包尚未生成完成");
        if (!Files.isRegularFile(job.getArtifact())) throw new IllegalStateException("数据包文件不存在，请联系管理员恢复备份");
        FileSystemResource file = new FileSystemResource(job.getArtifact());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(file.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=algorithm-data-" + id + ".zip")
                .body(file);
    }
    private Long userId(Principal principal) { return currentUser(principal).getId(); }
    private UserAccount currentUser(Principal principal) { return users.requireByUsername(principal.getName()); }
}
