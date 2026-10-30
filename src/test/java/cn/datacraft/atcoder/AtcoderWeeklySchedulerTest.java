package cn.datacraft.atcoder;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtcoderWeeklySchedulerTest {
    @Test void officialRowsAreParsedWithTimezoneAndOnlyWeekendAbcIsChosen() {
        var rows=AtcoderContestSchedule.parse("""
            <div id="contest-table-upcoming"><table><tbody>
            <tr><td><time>2026-10-31 21:00:00+0900</time></td><td><a href="/contests/abc482">ABC 482</a></td><td>01:40</td></tr>
            <tr><td><time>2026-11-07 21:00:00+0900</time></td><td><a href="/contests/abc483">ABC 483</a></td><td>01:40</td></tr>
            <tr><td><time>2026-10-31 21:00:00+0900</time></td><td><a href="/contests/arc234">ARC</a></td><td>02:00</td></tr>
            <tr><td><time>garbage</time></td><td><a href="/contests/abc999">bad</a></td><td>01:40</td></tr>
            </tbody></table></div>""");
        assertThat(rows).hasSize(2);
        var selected=AtcoderWeeklyScheduler.choose(rows,LocalDate.parse("2026-10-26"),ZoneId.of("Asia/Shanghai")).orElseThrow();
        assertThat(selected.id()).isEqualTo("abc482");
        assertThat(selected.start()).isEqualTo(Instant.parse("2026-10-31T12:00:00Z"));
        assertThat(selected.end()).isEqualTo(Instant.parse("2026-10-31T13:40:00Z"));
    }
    @Test void retriesAfterFailureCatchesUpAfterRestartAndDoesNotRepeatSuccessfulWeek() throws Exception {
        var source=mock(AtcoderContestSchedule.class);var boards=mock(AtcoderLeaderboardService.class);
        var configs=mock(AtcoderLeaderboardConfigRepository.class);when(configs.findAll()).thenReturn(List.of());
        var before=new AtcoderWeeklyScheduler(source,boards,configs,true,Clock.fixed(Instant.parse("2026-10-31T10:59:59Z"),ZoneOffset.UTC));
        before.tick();verifyNoInteractions(source);
        var due=Instant.parse("2026-10-31T11:00:00Z");
        var candidate=new AtcoderContestSchedule.Candidate("abc482","ABC",due.plusSeconds(3600),due.plusSeconds(9600));
        when(source.fetch()).thenThrow(new IllegalStateException("network")).thenReturn(List.of(candidate));
        var after=new AtcoderWeeklyScheduler(source,boards,configs,true,Clock.fixed(due.plusSeconds(60),ZoneOffset.UTC));
        after.tick();verifyNoInteractions(boards);after.tick();verify(boards).selectWeeklyContest(candidate);
        var stored=new AtcoderLeaderboardConfig(candidate.id(),"ABC","ABC",candidate.start(),candidate.end(),"[]",due.plusSeconds(60));
        stored.setAutoSelected(true);when(configs.findAll()).thenReturn(List.of(stored));
        after.tick();verify(source,times(2)).fetch();
    }
    @Test void emptyOrWrongWeekDoesNotSwitch() {
        assertThat(AtcoderWeeklyScheduler.choose(List.of(),LocalDate.parse("2026-10-26"),ZoneId.of("Asia/Shanghai"))).isEmpty();
    }
}
