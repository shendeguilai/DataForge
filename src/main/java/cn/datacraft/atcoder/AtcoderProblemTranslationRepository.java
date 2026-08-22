package cn.datacraft.atcoder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface AtcoderProblemTranslationRepository extends JpaRepository<AtcoderProblemTranslation, Long> {
    List<AtcoderProblemTranslation> findAllByContestIdOrderByTaskOrderAscIdAsc(String contestId);
    Optional<AtcoderProblemTranslation> findByContestIdAndTaskId(String contestId, String taskId);
}
