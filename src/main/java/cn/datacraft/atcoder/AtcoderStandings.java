package cn.datacraft.atcoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AtcoderStandings {
    private AtcoderStandings() {}

    public record ContestMetadata(String title, Instant startAt, Instant endAt) {}

    public record Task(String id, String label, String name, BigDecimal maxScore) {}

    public record TaskResult(BigDecimal score, long elapsedNanos, int penalty, int failure,
                             int count, boolean pending, boolean frozen, String status) {}

    public record Entry(String username, Integer officialRank, BigDecimal totalScore,
                        long elapsedNanos, int penalty, Map<String, TaskResult> taskResults) {}

    public record Snapshot(List<Task> tasks, Map<String, Entry> entriesByUsernameKey) {}
}
