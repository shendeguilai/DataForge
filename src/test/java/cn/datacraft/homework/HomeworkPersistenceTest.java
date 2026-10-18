package cn.datacraft.homework;

import cn.datacraft.DataForgeApplication;
import cn.datacraft.ai.SecretCipher;
import cn.datacraft.homework.HomeworkTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HomeworkPersistenceTest {
    HomeworkService service(ConfigurableApplicationContext context, KedaojGateway oj) {
        return new HomeworkService(context.getBean(HomeworkRepository.class), context.getBean(ObjectMapper.class),
                context.getBean(SecretCipher.class), oj, context.getBean(PlatformTransactionManager.class), Runnable::run, Clock.systemUTC(), true);
    }
    @Test void queuedJobEncryptedCredentialAndAssignmentSurviveRealApplicationRestart() throws Exception {
        Path root = Files.createTempDirectory("homework-restart-");
        String[] args = {"--spring.datasource.url=jdbc:h2:file:" + root.resolve("database"),
                "--dataforge.runtime-dir=" + root.resolve("runtime"), "--dataforge.bootstrap-enabled=false", "--dataforge.homework.scheduling-enabled=false"};
        var oj = mock(KedaojGateway.class);
        when(oj.identity(anyString(), anyString(), anyString())).thenReturn(new KedaojGateway.Identity(10766, "teacher"));
        when(oj.user(anyString(), anyString(), anyString(), eq(42L))).thenReturn(new KedaojGateway.Identity(42, "student"));
        when(oj.problem(anyString(), anyString(), anyString(), eq("P0032"))).thenReturn(new Problem("P0032", 32, "题目"));
        when(oj.records(anyString(), anyString(), anyString(), eq(42L), eq(32L), any())).thenReturn(List.of(
                new Submission("6ad4457d2b71ec54920761b8", "system", 42, 32, 1, 100, Instant.parse("2026-09-01T00:00:00Z"), "")));
        String assignmentId, taskId;
        try (var first = new SpringApplicationBuilder(DataForgeApplication.class).web(WebApplicationType.NONE).run(args)) {
            var s = service(first, oj); s.saveSettings("teacher", new SettingsInput("test-sid", "system", true));
            var c = s.saveClass("teacher", null, new ClassInput("提高班")); s.addStudents("teacher", c.id(), List.of(new StudentInput("小明", 42)));
            var a = s.createAssignment("teacher", new CreateAssignment(c.id(), "周作业", "system", Instant.parse("2026-10-01T00:00:00Z"), null, List.of("P0032")));
            assignmentId = a.id(); taskId = s.refresh("teacher", a.id()).id();
        }
        try (var second = new SpringApplicationBuilder(DataForgeApplication.class).web(WebApplicationType.NONE).run(args)) {
            var s = service(second, oj); assertThat(s.settings("teacher").configured()).isTrue();
            assertThat(s.task("teacher", taskId).state()).isEqualTo("QUEUED"); s.dispatch();
            assertThat(s.assignment("teacher", assignmentId).completedStudents()).isEqualTo(1);
            s.archive("teacher", assignmentId, true);
        }
        try (var third = new SpringApplicationBuilder(DataForgeApplication.class).web(WebApplicationType.NONE).run(args)) {
            var s = service(third, oj); var a = s.assignment("teacher", assignmentId);
            assertThat(a.archived()).isTrue(); assertThat(a.cells().get("42:32").status).isEqualTo("AC");
            assertThat(s.task("teacher", taskId).state()).isEqualTo("SUCCEEDED");
        }
    }
}
