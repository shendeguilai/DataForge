package cn.datacraft.job;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface JobRecordJpaRepository extends JpaRepository<JobRecord, String> {
    List<JobRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    List<JobRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant createdAt);
}
