package cn.datacraft.job;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="generation_task_queue")
public class GenerationTask {
    @Id @Column(length=36) public String id;
    @Column(nullable=false,length=16) public String phase;
    @Column(nullable=false,length=16) public String state="QUEUED";
    public String token;
    public Instant leaseUntil;
    @Column(nullable=false) public Instant updatedAt=Instant.now();
    @Version public long version;
}
