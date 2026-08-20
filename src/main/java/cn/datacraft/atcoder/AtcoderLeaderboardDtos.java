package cn.datacraft.atcoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AtcoderLeaderboardDtos {
    private AtcoderLeaderboardDtos() {}

    public record ContestView(String id, String title, String officialTitle, Instant startAt,
                              Instant endAt, String status, String url) {}

    public record TaskView(String id, String label, String name, BigDecimal maxScore, String url) {}

    public record MovementView(String type, int places) {}

    public record TaskResultView(String taskId, String status, BigDecimal score, long elapsedSeconds,
                                 String elapsedText, int failure, int penalty,
                                 boolean pending, boolean frozen) {}

    public record EntryView(Long participantId, Integer classRank, String displayName,
                            String atcoderUsername, BigDecimal totalScore, long penaltySeconds,
                            String penaltyText, int wrongAttempts, Integer officialRank,
                            MovementView movement, String status, List<TaskResultView> taskResults) {}

    public record LeaderboardView(boolean configured, boolean dataAvailable, ContestView contest,
                                  List<TaskView> tasks, List<EntryView> entries, int participantCount,
                                  int rankedCount, Instant lastSyncedAt, boolean stale, boolean refreshing,
                                  String error, int refreshAfterSeconds, int refreshCooldownSeconds) {}

    public record AdminConfigView(boolean configured, String contestId, String displayTitle,
                                  String officialTitle, Instant startAt, Instant endAt,
                                  Instant updatedAt, String cookieStatus, String cookieSource,
                                  Instant cookieUpdatedAt, List<TaskView> tasks) {}

    public record ParticipantView(Long id, String displayName, String atcoderUsername,
                                  int sortOrder, Instant createdAt) {}

    public static class ConfigRequest {
        public String contestId;
        public String displayTitle;
    }

    public static class ParticipantRequest {
        public String displayName;
        public String atcoderUsername;
    }

    public static class BulkParticipantRequest {
        public String realName;
        public String atcoderId;
    }

    public static class CookieRequest {
        public String cookie;
    }
}
