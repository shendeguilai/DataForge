package cn.datacraft.cspsim;

import jakarta.persistence.*;
import java.time.Instant;

/** Durable aggregates: students, exams, workspaces, submissions, batches and leased judge tasks. */
@Entity
@Table(name = "csp_sim_records", indexes = {
        @Index(name = "idx_csp_owner_kind", columnList = "owner_key,kind"),
        @Index(name = "idx_csp_parent_kind", columnList = "parent_id,kind"),
        @Index(name = "idx_csp_queue", columnList = "kind,state,lease_until")})
public class CspRecord {
    @Id @Column(length = 36) public String id;
    @Column(nullable = false, length = 24) public String kind;
    @Column(name = "owner_key", nullable = false, length = 40) public String owner;
    @Column(name = "parent_id", length = 36) public String parentId;
    @Column(name = "unique_key", length = 128, unique = true) public String uniqueKey;
    @Column(nullable = false, length = 24) public String state = "READY";
    @Column(name = "lease_until") public Instant leaseUntil;
    @Column(nullable = false, columnDefinition = "TEXT") public String payload;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt = Instant.now();
    @Version public long version;
}
