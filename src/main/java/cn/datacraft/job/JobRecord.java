package cn.datacraft.job;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "generation_jobs")
public class JobRecord {
    @Id @Column(length = 36)
    String id;
    @Column(nullable = false)
    Long userId;
    @Column(nullable = false, length = 32)
    String status;
    int progress;
    @Column(length = 500)
    String message;
    @Lob String error;
    @Lob @Column(nullable = false) String statement;
    @Lob @Column(nullable = false) String standardCode;
    @Lob @Column(nullable = false) String requirements;
    int caseCount;
    @Column(length = 12) String cppStandard;
    @Lob String planJson;
    @Column(length = 1000) String artifactPath;
    @Column(nullable = false) Instant createdAt;
    @Column(nullable = false) Instant updatedAt;
}
