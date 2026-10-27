package cn.datacraft.job;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface GenerationTaskRepository extends JpaRepository<GenerationTask,String> {
    List<GenerationTask> findByStateInOrderByUpdatedAtAsc(Collection<String> states,org.springframework.data.domain.Pageable page);
    long countByState(String state);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from GenerationTask t where t.id=:id") Optional<GenerationTask> lock(@Param("id")String id);
}
