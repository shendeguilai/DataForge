package cn.datacraft.homework;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface HomeworkRepository extends JpaRepository<HomeworkRecord, String> {
    List<HomeworkRecord> findByOwnerAndKindOrderByUpdatedAtDesc(String owner, String kind);
    List<HomeworkRecord> findByKindAndStateInOrderByUpdatedAtAsc(String kind, Collection<String> states);
    List<HomeworkRecord> findByKind(String kind);
    Optional<HomeworkRecord> findByActiveKey(String activeKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from HomeworkRecord r where r.id = :id")
    Optional<HomeworkRecord> lock(@Param("id") String id);
}
