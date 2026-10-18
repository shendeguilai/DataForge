package cn.datacraft.homework;

import jakarta.persistence.*;
import java.time.Instant;

/** Teacher-owned durable aggregates, including encrypted settings and leased sync jobs. */
@Entity
@Table(name = "kedaoj_homework_records", indexes = {
        @Index(name = "idx_hw_owner_kind", columnList = "owner_key,kind"),
        @Index(name = "idx_hw_queue", columnList = "kind,state,lease_until")})
public class HomeworkRecord {
    @Id @Column(length = 36) public String id;
    @Column(name = "owner_key", nullable = false, length = 40) public String owner;
    @Column(nullable = false, length = 24) public String kind;
    @Column(nullable = false, length = 24) public String state = "READY";
    @Column(name = "active_key", unique = true, length = 100) public String activeKey;
    @Column(name = "lease_until") public Instant leaseUntil;
    @Column(nullable = false, columnDefinition = "TEXT") public String payload;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    @Version public long version;
}
