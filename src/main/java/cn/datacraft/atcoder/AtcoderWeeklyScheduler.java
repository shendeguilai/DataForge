package cn.datacraft.atcoder;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Component
public class AtcoderWeeklyScheduler {
    private final AtcoderContestSchedule schedule;
    private final AtcoderLeaderboardService boards;
    private final AtcoderLeaderboardConfigRepository configs;
    private final boolean enabled;
    private final Clock clock;
    @Autowired
    public AtcoderWeeklyScheduler(AtcoderContestSchedule schedule,AtcoderLeaderboardService boards,
            AtcoderLeaderboardConfigRepository configs,@Value("${dataforge.atcoder.weekly-enabled:true}")boolean enabled) {
        this(schedule,boards,configs,enabled,Clock.systemUTC());
    }
    AtcoderWeeklyScheduler(AtcoderContestSchedule schedule,AtcoderLeaderboardService boards,
            AtcoderLeaderboardConfigRepository configs,boolean enabled,Clock clock) {
        this.schedule=schedule;this.boards=boards;this.configs=configs;this.enabled=enabled;this.clock=clock;
    }
    @Scheduled(cron="0 0 19 * * SAT",zone="Asia/Shanghai")
    public void saturday(){tick();}
    // Catch up after a restart or upstream failure; persistent selection makes successful runs idempotent.
    @Scheduled(fixedDelay=600000,initialDelay=10000)
    public synchronized void tick() {
        if(!enabled)return;
        var now=clock.instant();var zone=ZoneId.of("Asia/Shanghai");
        var monday=now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var due=monday.plusDays(5).atTime(19,0).atZone(zone).toInstant();
        if(now.isBefore(due))return;
        var weekend=monday.plusDays(5).atStartOfDay(zone).toInstant();
        var nextWeek=monday.plusWeeks(1).atStartOfDay(zone).toInstant();
        if(configs.findAll().stream().anyMatch(c->c.isAutoSelected()&&c.getStartAt()!=null
                &&!c.getStartAt().isBefore(weekend)&&c.getStartAt().isBefore(nextWeek)
                &&c.getUpdatedAt()!=null&&!c.getUpdatedAt().isBefore(due)))return;
        try {
            var candidate=choose(schedule.fetch(),monday,zone);
            if(candidate.isPresent())boards.selectWeeklyContest(candidate.get());
            else LoggerFactory.getLogger(getClass()).warn("本周末未发现官方 ABC 比赛，保留当前排行榜");
        } catch(Exception e) {
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            LoggerFactory.getLogger(getClass()).warn("AtCoder 自动切换未完成，将在十分钟后重试");
        }
    }
    static Optional<AtcoderContestSchedule.Candidate> choose(List<AtcoderContestSchedule.Candidate> candidates,LocalDate monday,ZoneId zone) {
        Instant from=monday.plusDays(5).atStartOfDay(zone).toInstant(),to=monday.plusWeeks(1).atStartOfDay(zone).toInstant();
        return candidates.stream().filter(c->c.id().matches("abc[0-9]+")&&!c.start().isBefore(from)&&c.start().isBefore(to))
                .min(Comparator.comparing(AtcoderContestSchedule.Candidate::start));
    }
}
