package cn.datacraft.homework;

import cn.datacraft.ai.SecretCipher;
import cn.datacraft.homework.HomeworkTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:homework-tests;DB_CLOSE_DELAY=-1", "dataforge.homework.scheduling-enabled=false",
        "dataforge.bootstrap-enabled=false", "dataforge.runtime-dir=${java.io.tmpdir}/homework-test-runtime"})
@AutoConfigureMockMvc
class HomeworkIntegrationTest {
    @Autowired HomeworkRepository repository;
    @Autowired ObjectMapper json;
    @Autowired SecretCipher cipher;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @MockitoBean KedaojGateway oj;
    HomeworkService service;
    String owner;
    final Instant time = Instant.parse("2026-10-18T16:00:00Z");
    @BeforeEach void setup() {
        owner = "t" + UUID.randomUUID().toString().substring(0, 8);
        service = new HomeworkService(repository, json, cipher, oj, manager, Runnable::run, Clock.fixed(time, ZoneOffset.UTC), true);
        when(oj.identity(anyString(), anyString(), anyString())).thenReturn(new KedaojGateway.Identity(10766, "teacher"));
        when(oj.user(anyString(), anyString(), anyString(), anyLong())).thenAnswer(a -> new KedaojGateway.Identity(a.getArgument(3), "student_" + a.getArgument(3)));
        when(oj.problem(anyString(), anyString(), anyString(), anyString())).thenAnswer(a -> new Problem(a.getArgument(3), 32, "题目"));
        when(oj.records(anyString(), anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(List.of());
        when(oj.recentRecords(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any()))
                .thenAnswer(a -> new RecordFetch(oj.records(a.getArgument(0), a.getArgument(1), a.getArgument(2), a.getArgument(3), a.getArgument(4), a.getArgument(6)), true));
        service.saveSettings(owner, new SettingsInput("sid=dummy-secret; other=discard", "system", true));
    }
    AssignmentView assignment() {
        var c = service.saveClass(owner, null, new ClassInput("提高班"));
        service.addStudents(owner, c.id(), List.of(new StudentInput("小明", 42)));
        return service.createAssignment(owner, new CreateAssignment(c.id(), "第 1 周", "system", time.minus(Duration.ofDays(7)), time.minusSeconds(1), List.of("P0032")));
    }
    @Test void snapshotsRosterAndPersistsResultsAcrossServiceRecreation() {
        var a = assignment(); var student = a.students().get(0);
        service.removeStudent(owner, a.classId(), student.id());
        assertThat(service.assignment(owner, a.id()).students()).hasSize(1);
        when(oj.records(eq(owner), anyString(), eq("system"), eq(42L), eq(32L), any()))
                .thenReturn(List.of(new Submission("6ad4457d2b71ec54920761b8", "system", 42, 32, 1, 100, time.minus(Duration.ofDays(30)), "6ad4457d2b71ec54920761b8")));
        var task = service.refresh(owner, a.id()); service.dispatch();
        assertThat(service.task(owner, task.id()).state()).isEqualTo("SUCCEEDED");
        var again = new HomeworkService(repository, json, cipher, oj, manager, Runnable::run, Clock.systemUTC(), false);
        var result = again.assignment(owner, a.id());
        assertThat(result.completedStudents()).isEqualTo(1); assertThat(result.cells().get("42:32").status).isEqualTo("AC");
        assertThat(repository.findByKind("PAIR")).anyMatch(r -> r.owner.equals(owner));
        assertThatThrownBy(() -> again.assignment("other", a.id())).isInstanceOf(NoSuchElementException.class);
    }
    @Test void failurePreservesLastAcAndLastSuccessfulTimestamp() {
        var a = assignment();
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(List.of(new Submission("6ad4457d2b71ec54920761b8", "system", 42, 32, 1, 100, time.minusSeconds(10), "")));
        service.refresh(owner, a.id()); service.dispatch();
        var first = service.assignment(owner, a.id());
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenThrow(new KedaojException("NETWORK_ERROR", "网络失败"));
        var task = service.refresh(owner, a.id()); service.dispatch();
        var after = service.assignment(owner, a.id()); var cell = after.cells().get("42:32");
        assertThat(cell.status).isEqualTo("UNKNOWN"); assertThat(cell.previousStatus).isEqualTo("AC");
        assertThat(cell.completed).isTrue(); assertThat(cell.accepted).isNotNull();
        assertThat(after.syncedAt()).isEqualTo(first.syncedAt()); assertThat(service.task(owner, task.id()).state()).isEqualTo("FAILED");
    }
    @Test void deduplicatesTasksAndQueriesAcrossAssignmentsAndHonorsArchive() {
        var a = assignment();
        var b = service.createAssignment(owner, new CreateAssignment(a.classId(), "第 2 周", "system", time.minusSeconds(10), time.plusSeconds(10), List.of("P0032")));
        var first = service.refresh(owner, a.id()); var second = service.refresh(owner, b.id());
        assertThat(second.id()).isEqualTo(first.id());
        service.dispatch(); verify(oj, times(1)).records(eq(owner), anyString(), eq("system"), eq(42L), eq(32L), any());
        service.archive(owner, a.id(), true); service.archive(owner, b.id(), true);
        assertThatThrownBy(() -> service.refresh(owner, a.id())).isInstanceOf(IllegalArgumentException.class);
        service.nightlyUpdate(); assertThat(service.activeTask(owner)).isNull();
        service.archive(owner, a.id(), false); service.nightlyUpdate(); assertThat(service.activeTask(owner)).isNotNull();
        service.dispatch();
    }
    @Test void respectsManualOnlyAndDoesNotScheduleFutureAssignments() throws Exception {
        var a = assignment(); service.saveSettings(owner, new SettingsInput(null, "system", false));
        service.nightlyUpdate(); assertThat(service.activeTask(owner)).isNull();
        service.saveSettings(owner, new SettingsInput(null, "system", true)); service.archive(owner, a.id(), true);
        service.createAssignment(owner, new CreateAssignment(a.classId(), "未来作业", "system", time.plusSeconds(10), null, List.of("P0032")));
        service.nightlyUpdate(); assertThat(service.activeTask(owner)).isNull();
        var cron = Arrays.stream(HomeworkService.class.getMethod("nightlyUpdate").getAnnotationsByType(org.springframework.scheduling.annotation.Scheduled.class)).findFirst().orElseThrow();
        assertThat(cron.cron()).isEqualTo("0 0 0 * * *"); assertThat(cron.zone()).isEqualTo("Asia/Shanghai");
    }
    @Test void encryptsCredentialsAndProtectsTeacherDataAtHttpBoundary() throws Exception {
        var a = assignment();
        mvc.perform(get("/api/tools/kedaoj-homework/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/kedaoj-homework.html").accept("text/html")).andExpect(status().is3xxRedirection());
        String body = mvc.perform(get("/api/tools/kedaoj-homework/settings").with(user(owner)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("dummy-secret", "encryptedSid", "credential");
        assertThat(repository.findByOwnerAndKindOrderByUpdatedAtDesc(owner, "SETTINGS").get(0).payload).doesNotContain("dummy-secret", "discard");
        mvc.perform(get("/api/tools/kedaoj-homework/assignments/" + a.id()).with(user("other"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/tools/kedaoj-homework/assignments/" + a.id() + "/refresh").with(user("other"))).andExpect(status().isNotFound());
    }
    @Test void mergesAssignmentsAddedDuringRunningJobAndDoesNotOverwriteArchivedResults() {
        var a = assignment();
        var b = service.createAssignment(owner, new CreateAssignment(a.classId(), "另一份作业", "system", time.minusSeconds(10), null, List.of("P0032")));
        var task = service.refresh(owner, a.id());
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            assertThat(service.refresh(owner, b.id()).id()).isEqualTo(task.id());
            service.archive(owner, a.id(), true);
            return List.of(new Submission("6ad4457d2b71ec54920761b8", "system", 42, 32, 1, 100, time.minusSeconds(20), ""));
        });
        service.dispatch();
        assertThat(service.assignment(owner, a.id()).archived()).isTrue();
        assertThat(service.assignment(owner, a.id()).cells()).isEmpty();
        assertThat(service.assignment(owner, b.id()).completedStudents()).isEqualTo(1);
        assertThat(service.task(owner, task.id()).total()).isEqualTo(2);
        verify(oj, times(1)).records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any());
    }
    @Test void expiredLeaseIsRecoveredWithoutRepeatingLiveLeases() {
        var a = assignment(); var task = service.refresh(owner, a.id());
        var record = repository.findById(task.id()).orElseThrow();
        var data = json.convertValue(json.valueToTree(readTask(record)), SyncTask.class);
        data.leaseToken = "old-process"; record.state = "RUNNING"; record.leaseUntil = time.plusSeconds(60);
        record.payload = encodeTask(data); repository.saveAndFlush(record);
        service.dispatch(); verify(oj, never()).records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any());
        record = repository.findById(task.id()).orElseThrow(); record.leaseUntil = time.minusSeconds(1); repository.saveAndFlush(record);
        service.dispatch(); assertThat(service.task(owner, task.id()).state()).isEqualTo("SUCCEEDED");
    }
    private SyncTask readTask(HomeworkRecord r) {
        try { return json.readValue(r.payload, SyncTask.class); } catch (Exception e) { throw new AssertionError(e); }
    }
    @Test void editsPreserveRosterRecomputeLateStatusAndGuardConcurrentRevisions() {
        var a = assignment();
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(List.of(new Submission("6ad4457d2b71ec54920761b8", "system", 42, 32, 1, 100, time, "")));
        service.refresh(owner, a.id()); service.dispatch();
        assertThat(service.assignment(owner, a.id()).cells().get("42:32").status).isEqualTo("LATE");
        service.removeStudent(owner, a.classId(), a.students().get(0).id());
        var input = new CreateAssignment(a.classId(), "修正截止时间", "system", a.startAt(), time.plusSeconds(60), List.of("P0032"));
        var edited = service.editAssignment(owner, a.id(), input, 0);
        assertThat(edited.students()).hasSize(1); assertThat(edited.revision()).isEqualTo(1);
        assertThat(edited.cells().get("42:32").status).isEqualTo("AC"); assertThat(edited.cells().get("42:32").beforeDeadline).isTrue();
        assertThatThrownBy(() -> service.editAssignment(owner, a.id(), input, 0)).isInstanceOf(IllegalArgumentException.class);
        service.archive(owner, a.id(), true);
        assertThatThrownBy(() -> service.editAssignment(owner, a.id(), input, 1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void copyUsesTargetCurrentRosterAndDoesNotShareResults() {
        var a = assignment(); var target = service.saveClass(owner, null, new ClassInput("另一个班"));
        service.addStudents(owner, target.id(), List.of(new StudentInput("小红", 99)));
        var input = new CreateAssignment(target.id(), "副本", "system", time, time.plusSeconds(100), List.of("P0032"));
        var copied = service.copyAssignment(owner, a.id(), input);
        assertThat(copied.id()).isNotEqualTo(a.id()); assertThat(copied.students()).extracting(Student::uid).containsExactly(99L);
        assertThat(copied.cells()).isEmpty(); assertThat(service.assignment(owner, a.id()).students()).extracting(Student::uid).containsExactly(42L);
        assertThatThrownBy(() -> service.copyAssignment("other", a.id(), input)).isInstanceOf(NoSuchElementException.class);
    }
    @Test void onlyFailedCellsAreRetriedAndSuccessfulCellsSurvivePartialNetworkFailure() {
        when(oj.problem(anyString(), anyString(), anyString(), anyString())).thenAnswer(a -> new Problem(a.getArgument(3), a.<String>getArgument(3).equals("P0033") ? 33 : 32, "题目"));
        var original = assignment();
        var a = service.editAssignment(owner, original.id(), new CreateAssignment(original.classId(), "两道题", "system", original.startAt(), original.endAt(), List.of("P0032", "P0033")), 0);
        when(oj.records(eq(owner), anyString(), anyString(), eq(42L), eq(33L), any())).thenThrow(new KedaojException("NETWORK_ERROR", "模拟网络中断"));
        var task = service.refresh(owner, a.id()); service.dispatch();
        var partial = service.assignment(owner, a.id());
        assertThat(partial.cells().get("42:32").status).isEqualTo("EMPTY");
        assertThat(partial.failedCells()).isEqualTo(1); assertThat(service.task(owner, task.id()).state()).isEqualTo("PARTIAL");
        when(oj.records(eq(owner), anyString(), anyString(), eq(42L), eq(33L), any())).thenReturn(List.of());
        var retry = service.refresh(owner, a.id(), false, true); service.dispatch();
        assertThat(service.task(owner, retry.id()).checkedCells()).isEqualTo(1);
        assertThat(service.assignment(owner, a.id()).failedCells()).isZero();
        verify(oj, times(1)).records(eq(owner), anyString(), anyString(), eq(42L), eq(32L), any());
        assertThatThrownBy(() -> service.refresh(owner, a.id(), false, true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exposesDurableProgressBeforeEachQueryAndDoesNotDoubleCountRepeatedClicks() {
        when(oj.problem(anyString(), anyString(), anyString(), eq("P0033"))).thenReturn(new Problem("P0033", 33, "新题"));
        var original = assignment();
        var a = service.editAssignment(owner, original.id(), new CreateAssignment(original.classId(), "进度验证", "system", original.startAt(), original.endAt(), List.of("P0032", "P0033")), 0);
        var task = service.refresh(owner, a.id());
        assertThat(task.totalCells()).isEqualTo(2); assertThat(task.processedCells()).isZero();
        var duplicate = service.refresh(owner, a.id());
        assertThat(duplicate.id()).isEqualTo(task.id()); assertThat(duplicate.totalCells()).isEqualTo(2);
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            long doc = invocation.getArgument(4);
            // Observe through a separate service, as a browser polling the persisted task would.
            var reader = new HomeworkService(repository, json, cipher, oj, manager, Runnable::run, Clock.systemUTC(), false);
            var running = reader.task(owner, task.id());
            assertThat(running.state()).isEqualTo("RUNNING"); assertThat(running.totalCells()).isEqualTo(2);
            assertThat(running.processedCells()).isEqualTo(doc == 32 ? 0 : 1);
            assertThat(running.message()).contains("小明", doc == 32 ? "P0032" : "P0033");
            return List.of();
        });
        service.dispatch();
        var finished = service.task(owner, task.id());
        assertThat(finished.state()).isEqualTo("SUCCEEDED"); assertThat(finished.processedCells()).isEqualTo(2);
        assertThat(finished.checkedCells()).isEqualTo(2); assertThat(finished.totalCells()).isEqualTo(2);
    }
    @Test void tracksNewCompletionsAndChangedRejudgeResultsWithoutCallingFirstLoadNewWork() {
        var a = assignment(); service.refresh(owner, a.id()); service.dispatch();
        assertThat(service.assignment(owner, a.id()).lastChange().initial).isTrue();
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(List.of(new Submission("6ad4457d2b71ec54920761b8", "system", 42, 32, 1, 100, time, "")));
        service.refresh(owner, a.id()); service.dispatch();
        var change = service.assignment(owner, a.id()).lastChange();
        assertThat(change.initial).isFalse(); assertThat(change.newlyCompleted).hasSize(1); assertThat(change.newlyLate).hasSize(1);
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(List.of());
        service.refresh(owner, a.id(), true, false); service.dispatch();
        assertThat(service.assignment(owner, a.id()).lastChange().changedResults).hasSize(1);
    }
    @Test void incrementalCacheKeepsOlderAcAndWeeklyFullScanReconcilesRejudges() {
        var a = assignment(); var accepted = new Submission("68ff00002b71ec54920761b8", "system", 42, 32, 1, 100, time.minus(Duration.ofDays(30)), "");
        var failed = new Submission("6ad4457d2b71ec54920761b8", "system", 42, 32, 2, 60, time, "");
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(List.of(accepted));
        service.refresh(owner, a.id()); service.dispatch();
        when(oj.recentRecords(eq(owner), anyString(), anyString(), anyLong(), anyLong(), anyString(), any())).thenReturn(new RecordFetch(List.of(failed), false));
        service.refresh(owner, a.id()); service.dispatch();
        var cell = service.assignment(owner, a.id()).cells().get("42:32"); assertThat(cell.status).isEqualTo("AC"); assertThat(cell.submissions).isEqualTo(2);
        var later = new HomeworkService(repository, json, cipher, oj, manager, Runnable::run, Clock.fixed(time.plus(Duration.ofDays(8)), ZoneOffset.UTC), true);
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(List.of(failed));
        later.refresh(owner, a.id()); later.dispatch();
        assertThat(later.assignment(owner, a.id()).cells().get("42:32").status).isEqualTo("ATTEMPTED");
        verify(oj, times(2)).records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any());
        verify(oj, times(1)).recentRecords(eq(owner), anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
    }
    @Test void studentOverviewAndNewEndpointsRemainTeacherScoped() throws Exception {
        var a = assignment(); service.refresh(owner, a.id()); service.dispatch();
        var profile = service.studentOverview(owner, 42); assertThat(profile.assignmentCount()).isEqualTo(1); assertThat(profile.outstandingCells()).isEqualTo(1);
        service.archive(owner, a.id(), true); assertThat(service.studentOverview(owner, 42).outstandingCells()).isZero();
        assertThatThrownBy(() -> service.studentOverview("other", 42)).isInstanceOf(NoSuchElementException.class);
        mvc.perform(get("/api/tools/kedaoj-homework/assignments/" + a.id() + "/incomplete").with(user("other"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/tools/kedaoj-homework/assignments/" + a.id() + "/export.xlsx").with(user(owner))).andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        mvc.perform(get("/api/tools/kedaoj-homework/assignments/" + a.id() + "/export.xlsx").with(user("other"))).andExpect(status().isNotFound());
    }
    @Test void credentialRotationAndPendingRecordsForceFullReconciliation() {
        var a = assignment();
        var pending = new Submission("6ad4457d2b71ec54920761b8", "system", 42, 32, 20, 0, time, "");
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(List.of(pending));
        service.refresh(owner, a.id()); service.dispatch();
        var accepted = new Submission(pending.rid(), "system", 42, 32, 1, 100, time, "");
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(List.of(accepted));
        service.refresh(owner, a.id()); service.dispatch();
        assertThat(service.assignment(owner, a.id()).cells().get("42:32").status).isEqualTo("LATE");
        service.saveSettings(owner, new SettingsInput("different-session", "system", true));
        when(oj.records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(List.of());
        service.refresh(owner, a.id()); service.dispatch();
        assertThat(service.assignment(owner, a.id()).cells().get("42:32").status).isEqualTo("EMPTY");
        verify(oj, times(3)).records(eq(owner), anyString(), anyString(), anyLong(), anyLong(), any());
        verify(oj, never()).recentRecords(eq(owner), anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
    }
    @Test void editingNewProblemsOrDomainRequiresNewVerificationAndRejectsActiveSync() {
        var a = assignment(); service.refresh(owner, a.id()); service.dispatch();
        var original = new CreateAssignment(a.classId(), "修改题单", "system", a.startAt(), a.endAt(), List.of("P0032"));
        service.refresh(owner, a.id());
        assertThatThrownBy(() -> service.editAssignment(owner, a.id(), original, 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("正在更新");
        service.dispatch();
        when(oj.problem(anyString(), anyString(), anyString(), eq("P0033"))).thenReturn(new Problem("P0033", 33, "新题"));
        var edited = service.editAssignment(owner, a.id(), new CreateAssignment(a.classId(), "加题", "system", a.startAt(), a.endAt(), List.of("P0032", "P0033")), 0);
        assertThat(edited.cells()).containsKey("42:32").doesNotContainKey("42:33"); assertThat(edited.unknownCells()).isEqualTo(1); assertThat(edited.syncedAt()).isNull();
        var changedDomain = service.editAssignment(owner, a.id(), new CreateAssignment(a.classId(), "换域", "another", a.startAt(), a.endAt(), List.of("P0032")), 1);
        assertThat(changedDomain.cells()).isEmpty(); assertThat(changedDomain.unknownCells()).isEqualTo(1); assertThat(changedDomain.syncedAt()).isNull();
        assertThat(service.assignment(owner, a.id()).students()).hasSize(1);
    }
    private String encodeTask(SyncTask task) {
        try { return json.writeValueAsString(task); } catch (Exception e) { throw new AssertionError(e); }
    }
}
