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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AtcoderLeaderboardServiceTest {
    private AtcoderLeaderboardConfigRepository configs;
    private AtcoderLeaderboardSnapshotRepository snapshots;
    private AtcoderLeaderboardParticipantRepository participants;
    private AtcoderStandingsGateway gateway;
    private MutableClock clock;
    private AtcoderLeaderboardService service;
    private final Map<String, AtcoderLeaderboardConfig> configured = new LinkedHashMap<>();
    private final Map<String, AtcoderLeaderboardSnapshot> storedSnapshots = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        configs = mock(AtcoderLeaderboardConfigRepository.class);
        snapshots = mock(AtcoderLeaderboardSnapshotRepository.class);
        participants = mock(AtcoderLeaderboardParticipantRepository.class);
        gateway = mock(AtcoderStandingsGateway.class);
        clock = new MutableClock(Instant.parse("2026-08-20T01:00:00Z"));
        service = new AtcoderLeaderboardService(configs, snapshots, participants, gateway,
                new ObjectMapper().findAndRegisterModules(), clock);

        AtcoderLeaderboardConfig config = new AtcoderLeaderboardConfig(
                "abc430", "校内 ABC430 排行榜", "AtCoder Beginner Contest 430",
                Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-20T03:00:00Z"),
                "[]", clock.instant()
        );
        configured.put(config.getContestId(), config);
        when(configs.findAll()).thenAnswer(invocation -> List.copyOf(configured.values()));
        when(configs.findByContestIdIgnoreCase(any())).thenAnswer(invocation ->
                Optional.ofNullable(configured.get(((String) invocation.getArgument(0)).toLowerCase())));
        when(configs.saveAndFlush(any())).thenAnswer(invocation -> {
            AtcoderLeaderboardConfig saved = invocation.getArgument(0);
            configured.put(saved.getContestId(), saved);
            return saved;
        });
        when(snapshots.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(storedSnapshots.get(invocation.getArgument(0))));
        when(snapshots.saveAndFlush(any())).thenAnswer(invocation -> {
            AtcoderLeaderboardSnapshot snapshot = invocation.getArgument(0);
            storedSnapshots.put(snapshot.getContestId(), snapshot);
            return snapshot;
        });
        when(gateway.cookieStatus()).thenReturn(AtcoderStandingsGateway.CookieStatus.AVAILABLE);
        when(gateway.cookieSource()).thenReturn("NONE");
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
        AtcoderLeaderboardService restarted = new AtcoderLeaderboardService(
                configs, snapshots, participants, gateway,
                new ObjectMapper().findAndRegisterModules(), clock);
        LeaderboardView cached = restarted.currentLeaderboard();
        assertThat(cached.lastSyncedAt()).isEqualTo(first.lastSyncedAt());
        verify(gateway, times(1)).fetchStandings("abc430");

        clock.advanceSeconds(56);
        LeaderboardView stale = restarted.currentLeaderboard();
        assertThat(stale.dataAvailable()).isTrue();
        assertThat(stale.stale()).isTrue();
        assertThat(stale.error()).contains("timed out");
        assertThat(stale.entries()).hasSize(1);
        assertThat(restarted.currentLeaderboard().stale()).isTrue();
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

    @Test
    void usesPersistedMarkdownTitleWhenLiveStandingsOnlyExposeTaskId() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AtcoderStandings.Task configuredTask = new AtcoderStandings.Task(
                "abc430_a", "A", "Markdown Official Title", BigDecimal.valueOf(100));
        AtcoderLeaderboardConfig config = new AtcoderLeaderboardConfig(
                "abc430", "校内 ABC430 排行榜", "AtCoder Beginner Contest 430",
                Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-20T03:00:00Z"),
                mapper.writeValueAsString(List.of(configuredTask)), clock.instant());
        configured.put(config.getContestId(), config);
        when(participants.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(gateway.fetchStandings("abc430")).thenReturn(new AtcoderStandings.Snapshot(
                List.of(new AtcoderStandings.Task(
                        "abc430_a", "A", "abc430_a", BigDecimal.valueOf(100))), Map.of()));

        LeaderboardView view = service.currentLeaderboard();

        assertThat(view.tasks().get(0).name()).isEqualTo("Markdown Official Title");
    }

    @Test
    void selectsLatestContestByDefaultAndKeepsSnapshotsIndependent() {
        AtcoderLeaderboardConfig newer = new AtcoderLeaderboardConfig(
                "abc431", "校内 ABC431", "AtCoder Beginner Contest 431",
                Instant.parse("2026-08-20T00:30:00Z"), Instant.parse("2026-08-20T03:30:00Z"),
                "[]", clock.instant().plusSeconds(1));
        configured.put(newer.getContestId(), newer);
        when(participants.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(gateway.fetchStandings("abc431")).thenReturn(standings());
        when(gateway.fetchStandings("abc430")).thenReturn(standings());

        LeaderboardView defaultView = service.currentLeaderboard();
        LeaderboardView olderView = service.currentLeaderboard("abc430");

        assertThat(defaultView.contest().id()).isEqualTo("abc431");
        assertThat(defaultView.contests()).extracting(option -> option.id())
                .containsExactly("abc431", "abc430");
        assertThat(defaultView.contests().get(0).selected()).isTrue();
        assertThat(olderView.contest().id()).isEqualTo("abc430");
        assertThat(storedSnapshots).containsOnlyKeys("abc430", "abc431");
    }

    @Test
    void upcomingAndUnknownContestsNeverAutoFetchWithoutSnapshot() {
        AtcoderLeaderboardConfig upcoming = new AtcoderLeaderboardConfig(
                "abc430", "校内 ABC430", "AtCoder Beginner Contest 430",
                clock.instant().plusSeconds(3600), clock.instant().plusSeconds(7200),
                "[]", clock.instant());
        configured.put(upcoming.getContestId(), upcoming);
        when(participants.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());

        LeaderboardView upcomingView = service.currentLeaderboard("abc430");
        configured.put("abc430", new AtcoderLeaderboardConfig(
                "abc430", "校内 ABC430", "AtCoder Beginner Contest 430",
                null, null, "[]", clock.instant()));
        LeaderboardView unknownView = service.currentLeaderboard("abc430");

        assertThat(upcomingView.contest().status()).isEqualTo("UPCOMING");
        assertThat(upcomingView.autoRefresh()).isFalse();
        assertThat(upcomingView.refreshAfterSeconds()).isZero();
        assertThat(unknownView.contest().status()).isEqualTo("UNKNOWN");
        assertThat(unknownView.autoRefresh()).isFalse();
        verify(gateway, never()).fetchStandings(any());
    }

    @Test
    void finishedContestAutoAttemptsOnlyOnceEvenAfterFailureAndStillAllowsManualRefresh() {
        AtcoderLeaderboardConfig finished = new AtcoderLeaderboardConfig(
                "abc430", "校内 ABC430", "AtCoder Beginner Contest 430",
                clock.instant().minusSeconds(7200), clock.instant().minusSeconds(3600),
                "[]", clock.instant().minusSeconds(7200));
        configured.put(finished.getContestId(), finished);
        when(participants.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(gateway.fetchStandings("abc430"))
                .thenThrow(new IllegalStateException("final fetch failed"))
                .thenReturn(standings());

        LeaderboardView failed = service.currentLeaderboard("abc430");
        LeaderboardView frozen = service.currentLeaderboard("abc430");

        assertThat(failed.error()).contains("final fetch failed");
        assertThat(frozen.error()).contains("final fetch failed");
        assertThat(frozen.autoRefresh()).isFalse();
        verify(gateway, times(1)).fetchStandings("abc430");

        clock.advanceSeconds(11);
        LeaderboardView manual = service.manualRefresh("abc430");
        assertThat(manual.dataAvailable()).isTrue();
        assertThat(manual.error()).isNull();
        verify(gateway, times(2)).fetchStandings("abc430");
    }

    @Test
    void savingAContestIsAnIdempotentUpsertAndDoesNotReplaceOtherContests() {
        AtcoderStandings.Snapshot nextStandings = standings();
        AtcoderStandings.ContestMetadata nextMetadata = new AtcoderStandings.ContestMetadata(
                "AtCoder Beginner Contest 431",
                Instant.parse("2026-08-21T00:00:00Z"), Instant.parse("2026-08-21T02:00:00Z"));
        when(gateway.fetchStandings("abc431")).thenReturn(nextStandings);
        when(gateway.fetchMetadata("abc431")).thenReturn(nextMetadata);

        var created = service.saveConfig("ABC431", "校内新赛");
        var updated = service.saveConfig("abc431", "更新后标题");

        assertThat(created.contestId()).isEqualTo("abc431");
        assertThat(updated.displayTitle()).isEqualTo("更新后标题");
        assertThat(configured).containsOnlyKeys("abc430", "abc431");
        assertThat(storedSnapshots).containsKey("abc431");
        verify(gateway, times(2)).fetchStandings("abc431");
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
