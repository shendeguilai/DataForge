package cn.datacraft.atcoder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface AtcoderLeaderboardConfigRepository extends JpaRepository<AtcoderLeaderboardConfig, Long> {}

interface AtcoderLeaderboardParticipantRepository extends JpaRepository<AtcoderLeaderboardParticipant, Long> {
    List<AtcoderLeaderboardParticipant> findAllByOrderBySortOrderAscIdAsc();
    Optional<AtcoderLeaderboardParticipant> findByAtcoderUsernameKey(String usernameKey);
    Optional<AtcoderLeaderboardParticipant> findTopByOrderBySortOrderDesc();
}
