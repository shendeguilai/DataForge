package cn.datacraft.homework;

import cn.datacraft.homework.HomeworkTypes.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HomeworkRulesTest {
    final Instant deadline = Instant.parse("2026-10-19T10:00:00Z");
    Submission record(int status, String submitted, String contest) {
        return new Submission("6ad4457d2b71ec54920761b8", "system", 42, 32, status,
                status == 1 ? 100 : 60, Instant.parse(submitted), contest);
    }
    @Test void oldAndContestAcCountAndLaterFailuresDoNotEraseAc() {
        var beforeStart = record(1, "2026-09-01T10:00:00Z", "6ad4457d2b71ec54920761b8");
        var laterFailure = record(2, "2026-10-18T10:00:00Z", "");
        var cell = HomeworkService.evaluate(List.of(laterFailure, beforeStart), deadline, deadline);
        assertThat(cell.status).isEqualTo("AC"); assertThat(cell.completed).isTrue();
        assertThat(cell.beforeDeadline).isTrue(); assertThat(cell.accepted).isEqualTo(beforeStart);
        assertThat(cell.latest).isEqualTo(laterFailure); assertThat(cell.submissions).isEqualTo(2);
    }
    @Test void onlyFirstAcAfterDeadlineIsLateIncludingExactBoundary() {
        var cell = HomeworkService.evaluate(List.of(record(1, "2026-10-19T10:00:00Z", "")), deadline, deadline);
        assertThat(cell.status).isEqualTo("LATE"); assertThat(cell.completed).isTrue(); assertThat(cell.beforeDeadline).isFalse();
        cell = HomeworkService.evaluate(List.of(record(1, "2026-10-18T10:00:00Z", ""), record(1, "2026-10-20T10:00:00Z", "")), deadline, deadline);
        assertThat(cell.status).isEqualTo("AC");
    }
    @Test void distinguishesAttemptPendingEmptyAndExcludesPretests() {
        assertThat(HomeworkService.evaluate(List.of(), deadline, deadline).status).isEqualTo("EMPTY");
        assertThat(HomeworkService.evaluate(List.of(record(2, "2026-10-18T10:00:00Z", "")), deadline, deadline).status).isEqualTo("ATTEMPTED");
        assertThat(HomeworkService.evaluate(List.of(record(20, "2026-10-18T10:00:00Z", "")), deadline, deadline).status).isEqualTo("PENDING");
        assertThat(HomeworkService.evaluate(List.of(record(1, "2026-10-18T10:00:00Z", "000000000000000000000000")), deadline, deadline).status).isEqualTo("EMPTY");
    }
    @Test void extractsSidWithoutKeepingOtherCookiesAndRejectsHeaderInjection() {
        assertThat(KedaojClient.credential("Cookie: other=discard; sid=secret-value; lang=zh")).isEqualTo("secret-value");
        assertThat(KedaojClient.credential("sid=secret-value")).isEqualTo("secret-value");
        assertThat(KedaojClient.credential("opaque-token==")).isEqualTo("opaque-token==");
        assertThatThrownBy(() -> KedaojClient.credential("sid=a; sid=b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KedaojClient.credential("sid=a\r\nX-Other: b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KedaojClient.credential("other=abc; lang=zh")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KedaojClient.domain("../../other")).isInstanceOf(IllegalArgumentException.class);
    }
}
