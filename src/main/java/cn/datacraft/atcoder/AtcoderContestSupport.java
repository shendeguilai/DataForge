package cn.datacraft.atcoder;

import cn.datacraft.atcoder.AtcoderLeaderboardDtos.ContestOptionView;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;

final class AtcoderContestSupport {
    private static final Pattern CONTEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private AtcoderContestSupport() {}

    static Optional<AtcoderLeaderboardConfig> select(AtcoderLeaderboardConfigRepository configs,
                                                      String rawContestId) {
        if (rawContestId == null || rawContestId.isBlank()) return defaultContest(configs);
        String contestId = normalizeContestId(rawContestId);
        return Optional.of(configs.findByContestIdIgnoreCase(contestId)
                .orElseThrow(() -> new NoSuchElementException("比赛不存在：" + contestId)));
    }

    static Optional<AtcoderLeaderboardConfig> defaultContest(AtcoderLeaderboardConfigRepository configs) {
        return sorted(configs).stream().findFirst();
    }

    static List<AtcoderLeaderboardConfig> sorted(AtcoderLeaderboardConfigRepository configs) {
        Comparator<AtcoderLeaderboardConfig> order = Comparator
                .comparing(AtcoderLeaderboardConfig::getStartAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AtcoderLeaderboardConfig::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AtcoderLeaderboardConfig::getContestId, Comparator.reverseOrder());
        return configs.findAll().stream().sorted(order).toList();
    }

    static List<ContestOptionView> options(AtcoderLeaderboardConfigRepository configs,
                                           String selectedContestId, Clock clock) {
        return sorted(configs).stream().map(config -> new ContestOptionView(
                config.getContestId(), config.getDisplayTitle(), config.getOfficialTitle(),
                config.getStartAt(), config.getEndAt(), status(config, clock.instant()),
                config.getContestId().equalsIgnoreCase(selectedContestId)
        )).toList();
    }

    static String status(AtcoderLeaderboardConfig config, Instant now) {
        if (config.getStartAt() == null || config.getEndAt() == null) return "UNKNOWN";
        if (now.isBefore(config.getStartAt())) return "UPCOMING";
        if (!now.isBefore(config.getEndAt())) return "FINISHED";
        return "RUNNING";
    }

    static String normalizeContestId(String value) {
        String contestId = value == null ? "" : value.trim();
        if (!CONTEST_ID.matcher(contestId).matches()) {
            throw new IllegalArgumentException("Contest ID 只能包含字母、数字、下划线和短横线，最长 64 位");
        }
        return contestId.toLowerCase(Locale.ROOT);
    }
}
