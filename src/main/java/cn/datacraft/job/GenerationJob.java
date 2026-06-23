package cn.datacraft.job;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

public class GenerationJob {
    private UUID id;
    private Long userId;
    private Instant createdAt;
    private Instant updatedAt = Instant.now();
    private volatile JobStatus status = JobStatus.ANALYZING;
    private volatile int progress;
    private volatile String message = "正在分析题目";
    private volatile String error;
    private GenerationRequest request;
    private DataPlan plan;
    @JsonIgnore
    private Path artifact;

    public GenerationJob() { this(UUID.randomUUID(), Instant.now()); }
    GenerationJob(UUID id, Instant createdAt) { this.id = id; this.createdAt = createdAt; }

    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public JobStatus getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getMessage() { return message; }
    public String getError() { return error; }
    public GenerationRequest getRequest() { return request; }
    public void setRequest(GenerationRequest request) { this.request = request; }
    public DataPlan getPlan() { return plan; }
    public void setPlan(DataPlan plan) { this.plan = plan; }
    public Path getArtifact() { return artifact; }
    public void setArtifact(Path artifact) { this.artifact = artifact; }
    public boolean isDownloadReady() { return status == JobStatus.COMPLETED && artifact != null; }
    public synchronized void clearError() { this.error = null; }

    public synchronized void update(JobStatus status, int progress, String message) {
        this.status = status;
        this.progress = progress;
        this.message = message;
        this.updatedAt = Instant.now();
    }
    public synchronized void fail(String error) {
        this.status = JobStatus.FAILED;
        this.error = error;
        this.message = "任务失败";
        this.updatedAt = Instant.now();
    }
    void restore(JobStatus status, int progress, String message, String error, Instant updatedAt) {
        this.status = status; this.progress = progress; this.message = message; this.error = error; this.updatedAt = updatedAt;
    }
}
