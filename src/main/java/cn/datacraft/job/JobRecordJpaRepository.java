package cn.datacraft.job;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface JobRecordJpaRepository extends JpaRepository<JobRecord, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select j from JobRecord j where j.id=:id")
    java.util.Optional<JobRecord> lock(@org.springframework.data.repository.query.Param("id")String id);
    long countByStatus(String status);
    List<JobRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    List<JobRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant createdAt);
}
