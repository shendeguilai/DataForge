package cn.datacraft.job;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "generation_jobs")
public class JobRecord {
    @Id @Column(name = "id", length = 36)
    String id;
    @Column(name = "user_id", nullable = false)
    Long userId;
    @Column(name = "status", nullable = false, length = 32)
    String status;
    @Column(name = "progress", nullable = false)
    int progress;
    @Column(name = "message", length = 500)
    String message;
    @Column(name = "error", columnDefinition = "TEXT") String error;
    @Column(name = "statement", nullable = false, columnDefinition = "TEXT") String statement;
    @Column(name = "standard_code", nullable = false, columnDefinition = "TEXT") String standardCode;
    @Column(name = "requirements", nullable = false, columnDefinition = "TEXT") String requirements;
    @Column(name = "case_count", nullable = false)
    int caseCount;
    @Column(name = "cpp_standard", length = 12) String cppStandard;
    @Column(name = "plan_json", columnDefinition = "TEXT") String planJson;
    @Column(name = "artifact_path", length = 1000) String artifactPath;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
