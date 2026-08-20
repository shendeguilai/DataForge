package cn.datacraft.atcoder;

import cn.datacraft.atcoder.AtcoderLeaderboardDtos.EntryView;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.LeaderboardView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AtcoderLeaderboardServiceTest {
    private AtcoderLeaderboardConfigRepository configs;
    private AtcoderLeaderboardParticipantRepository participants;
    private AtcoderStandingsGateway gateway;
    private MutableClock clock;
    private AtcoderLeaderboardService service;

    @BeforeEach
    void setUp() {
        configs = mock(AtcoderLeaderboardConfigRepository.class);
        participants = mock(AtcoderLeaderboardParticipantRepository.class);
        gateway = mock(AtcoderStandingsGateway.class);
        clock = new MutableClock(Instant.parse("2026-08-20T01:00:00Z"));
        service = new AtcoderLeaderboardService(configs, participants, gateway,
                new ObjectMapper().findAndRegisterModules(), clock);

        AtcoderLeaderboardConfig config = new AtcoderLeaderboardConfig(
                "abc430", "校内 ABC430 排行榜", "AtCoder Beginner Contest 430",
                Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-20T03:00:00Z"),
                "[]", clock.instant()
        );
        when(configs.findById(AtcoderLeaderboardConfig.SINGLETON_ID)).thenReturn(Optional.of(config));
    }

    @Test
    void ranksConfiguredUsersTracksMovementAndLeavesMissingUsersAtBottom() {
        List<AtcoderLeaderboardParticipant> roster = List.of(
                participant(1L, "小明", "Alice", 1),
                participant(2L, "小红", "Bob", 2),
                participant(3L, "小李", "MissingUser", 3)
        );
        when(participants.findAllByOrderBySortOrderAscIdAsc()).thenReturn(roster);
        when(gateway.fetchStandings("abc430")).thenReturn(
                standings(entry("Alice", 10, 300, 100, 0), entry("Bob", 20, 200, 200, 1)),
                standings(entry("Alice", 30, 100, 300, 2), entry("Bob", 5, 500, 90, 0))
        );

        LeaderboardView first = service.currentLeaderboard();
        assertThat(first.entries()).extracting(EntryView::displayName).containsExactly("小明", "小红", "小李");
        assertThat(first.entries()).extracting(EntryView::classRank).containsExactly(1, 2, null);
        assertThat(first.entries().get(2).status()).isEqualTo("NOT_STARTED");

        clock.advanceSeconds(11);
        LeaderboardView second = service.manualRefresh();
        assertThat(second.entries()).extracting(EntryView::displayName).containsExactly("小红", "小明", "小李");
        assertThat(second.entries().get(0).movement().type()).isEqualTo("UP");
        assertThat(second.entries().get(0).movement().places()).isEqualTo(1);
        assertThat(second.entries().get(1).movement().type()).isEqualTo("DOWN");
    }

    @Test
    void reusesFreshCacheAndFallsBackToLastSuccessfulSnapshot() {
        when(participants.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(participant(1L, "小明", "Alice", 1)));
        when(gateway.fetchStandings("abc430"))
                .thenReturn(standings(entry("Alice", 10, 300, 100, 0)))
                .thenThrow(new IllegalStateException("request timed out"));

        LeaderboardView first = service.currentLeaderboard();
        LeaderboardView cached = service.currentLeaderboard();
        assertThat(cached.lastSyncedAt()).isEqualTo(first.lastSyncedAt());
        verify(gateway, times(1)).fetchStandings("abc430");

        clock.advanceSeconds(56);
        LeaderboardView stale = service.currentLeaderboard();
        assertThat(stale.dataAvailable()).isTrue();
        assertThat(stale.stale()).isTrue();
        assertThat(stale.error()).contains("timed out");
        assertThat(stale.entries()).hasSize(1);
        assertThat(service.currentLeaderboard().stale()).isTrue();
        verify(gateway, times(2)).fetchStandings("abc430");
    }

    @Test
    void givesEqualOfficialRanksTheSameClassRank() {
        when(participants.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(
                participant(1L, "A", "Alice", 1), participant(2L, "B", "Bob", 2), participant(3L, "C", "Carol", 3)
        ));
        when(gateway.fetchStandings("abc430")).thenReturn(standings(
                entry("Alice", 10, 300, 100, 0), entry("Bob", 10, 300, 100, 0), entry("Carol", 12, 200, 150, 0)
        ));

        assertThat(service.currentLeaderboard().entries()).extracting(EntryView::classRank).containsExactly(1, 1, 3);
    }

    private static AtcoderLeaderboardParticipant participant(Long id, String name, String username, int order) {
        AtcoderLeaderboardParticipant participant = new AtcoderLeaderboardParticipant(
                name, username, username.toLowerCase(), order, Instant.parse("2026-08-20T00:00:00Z")
        );
        ReflectionTestUtils.setField(participant, "id", id);
        return participant;
    }

    private static AtcoderStandings.Snapshot standings(AtcoderStandings.Entry... entries) {
        Map<String, AtcoderStandings.Entry> byName = new LinkedHashMap<>();
        for (AtcoderStandings.Entry entry : entries) byName.put(entry.username().toLowerCase(), entry);
        return new AtcoderStandings.Snapshot(
                List.of(new AtcoderStandings.Task("abc430_a", "A", "Warm Up", BigDecimal.valueOf(100))),
                Map.copyOf(byName)
        );
    }

    private static AtcoderStandings.Entry entry(String username, int rank, int score, int elapsedSeconds, int penalty) {
        AtcoderStandings.TaskResult task = new AtcoderStandings.TaskResult(
                BigDecimal.valueOf(score), elapsedSeconds * 1_000_000_000L, penalty, penalty,
                1 + penalty, false, false, "1"
        );
        return new AtcoderStandings.Entry(username, rank, BigDecimal.valueOf(score),
                elapsedSeconds * 1_000_000_000L, penalty, Map.of("abc430_a", task));
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        private MutableClock(Instant value) { this.value = value; }
        void advanceSeconds(long seconds) { value = value.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
