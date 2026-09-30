package cn.datacraft.atcoder;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 开发环境测试数据：用于查看多比赛排行榜下拉切换效果。
 */
@Component
@ConditionalOnProperty(name = "dataforge.atcoder-demo-seed.enabled", havingValue = "true")
class AtcoderLeaderboardDemoSeed implements ApplicationRunner {
    private final AtcoderLeaderboardConfigRepository configs;
    private final Clock clock = Clock.systemUTC();

    AtcoderLeaderboardDemoSeed(AtcoderLeaderboardConfigRepository configs) {
        this.configs = configs;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String[]> demos = List.of(
                new String[]{"abc300", "AtCoder Beginner Contest 300"},
                new String[]{"abc350", "AtCoder Beginner Contest 350"},
                new String[]{"abc400", "AtCoder Beginner Contest 400"},
                new String[]{"arc180", "AtCoder Regular Contest 180"}
        );

        Instant now = clock.instant();
        for (String[] demo : demos) {
            if (configs.findByContestIdIgnoreCase(demo[0]).isPresent()) {
                continue;
            }
            AtcoderLeaderboardConfig config = new AtcoderLeaderboardConfig(
                    demo[0],
                    demo[1],
                    demo[1],
                    now.minusSeconds(3600),
                    now,
                    "[]",
                    now
            );
            configs.save(config);
        }
    }
}
