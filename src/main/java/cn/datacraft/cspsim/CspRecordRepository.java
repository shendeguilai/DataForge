package cn.datacraft.cspsim;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface CspRecordRepository extends JpaRepository<CspRecord, String> {
    boolean existsByUniqueKey(String uniqueKey);
    Optional<CspRecord> findByUniqueKey(String uniqueKey);
    List<CspRecord> findByKindAndOwnerOrderByUpdatedAtDesc(String kind, String owner);
    List<CspRecord> findByKindAndParentIdOrderByUpdatedAtAsc(String kind, String parentId);
    List<CspRecord> findByKindAndStateOrderByUpdatedAtAsc(String kind, String state);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CspRecord r where r.id = :id")
    Optional<CspRecord> lock(@Param("id") String id);
}
