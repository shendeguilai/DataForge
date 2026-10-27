package cn.datacraft.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JobRepository {
    @org.springframework.beans.factory.annotation.Autowired private GenerationTaskRepository taskRecords;
    private final JobRecordJpaRepository records;
    private final ObjectMapper mapper;
    private final ArtifactStorage artifacts;
    public JobRepository(JobRecordJpaRepository records, ObjectMapper mapper, ArtifactStorage artifacts) {
        this.records = records;
        this.mapper = mapper;
        this.artifacts = artifacts;
    }

    @org.springframework.transaction.annotation.Transactional
    public GenerationJob save(GenerationJob job) {
        JobRecord record = records.lock(job.getId().toString()).orElseGet(JobRecord::new);
        String token=GenerationTaskQueue.CURRENT_TOKEN.get();
        if(token!=null) {
            var task=taskRecords.findById(job.getId().toString()).orElseThrow();
            if(!token.equals(task.token)||!"RUNNING".equals(task.state))throw new GenerationCancelledException("任务租约已失效");
        }
        if("CANCELLED".equals(record.status)&&job.getStatus()!=JobStatus.CANCELLED)throw new GenerationCancelledException("任务已取消");
        record.id = job.getId().toString(); record.userId = job.getUserId(); record.status = job.getStatus().name();
        record.progress = job.getProgress(); record.message = job.getMessage(); record.error = job.getError();
        record.statement = job.getRequest().getStatement(); record.standardCode = job.getRequest().getStandardCode();
        record.validatorCode=job.getRequest().getValidatorCode();record.bruteCode=job.getRequest().getBruteCode();
        record.requirements = job.getRequest().getRequirements(); record.caseCount = job.getRequest().getCaseCount();
        record.cppStandard = job.getRequest().getCppStandard(); record.createdAt = job.getCreatedAt(); record.updatedAt = job.getUpdatedAt();
        record.artifactPath = artifacts.toStoredKey(job.getArtifact());
        try { record.planJson = job.getPlan() == null ? null : mapper.writeValueAsString(job.getPlan()); }
        catch (Exception ex) { throw new IllegalStateException("任务方案无法保存", ex); }
        records.save(record);
        return job;
    }
    public Optional<GenerationJob> find(UUID id) { return records.findById(id.toString()).map(this::toDomain); }
    @org.springframework.transaction.annotation.Transactional
    public void prepareRetry(UUID id) {
        JobRecord record=records.lock(id.toString()).orElseThrow();
        if(!java.util.Set.of("FAILED","CANCELLED").contains(record.status))throw new IllegalStateException("只能重试失败或已取消的任务");
        record.status="FAILED";records.saveAndFlush(record);
    }
    public List<GenerationJob> recent(Long userId) {
        return records.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)).stream().map(this::toDomain).collect(Collectors.toList());
    }
    public List<GenerationJob> recentAll() {
        return records.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 200)).stream().map(this::toDomain).collect(Collectors.toList());
    }
    public long countCreatedSince(Long userId, Instant since) {
        return records.countByUserIdAndCreatedAtGreaterThanEqual(userId, since);
    }

    private GenerationJob toDomain(JobRecord record) {
        GenerationRequest request = new GenerationRequest();
        request.setStatement(record.statement); request.setStandardCode(record.standardCode); request.setRequirements(record.requirements);
        request.setValidatorCode(record.validatorCode);request.setBruteCode(record.bruteCode);
        request.setCaseCount(record.caseCount); request.setCppStandard(record.cppStandard);
        GenerationJob job = new GenerationJob(UUID.fromString(record.id), record.createdAt == null ? Instant.now() : record.createdAt);
        job.setUserId(record.userId); job.setRequest(request);
        if (record.planJson != null) {
            try { job.setPlan(mapper.readValue(record.planJson, DataPlan.class)); }
            catch (Exception ex) { throw new IllegalStateException("任务方案无法读取", ex); }
        }
        if (record.artifactPath != null) job.setArtifact(artifacts.resolveStored(record.artifactPath));
        job.restore(JobStatus.valueOf(record.status), record.progress, record.message, record.error, record.updatedAt);
        return job;
    }
}
