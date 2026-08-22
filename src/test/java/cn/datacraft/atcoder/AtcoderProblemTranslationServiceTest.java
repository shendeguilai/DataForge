package cn.datacraft.atcoder;

import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemDetailView;
import cn.datacraft.atcoder.AtcoderProblemDtos.AdminProblemDetailView;
import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemOverviewView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtcoderProblemTranslationServiceTest {
    private final Map<String, AtcoderProblemTranslation> stored = new LinkedHashMap<>();
    private AtcoderLeaderboardConfigRepository configs;
    private AtcoderProblemTranslationRepository translations;
    private AtcoderProblemTranslationService service;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        List<AtcoderStandings.Task> tasks = List.of(
                new AtcoderStandings.Task("abc430_a", "A", "Warm Up", BigDecimal.valueOf(100)),
                new AtcoderStandings.Task("abc430_b", "B", "Strings", BigDecimal.valueOf(200))
        );
        AtcoderLeaderboardConfig config = new AtcoderLeaderboardConfig(
                "abc430", "班级 ABC430", "AtCoder Beginner Contest 430", null, null,
                mapper.writeValueAsString(tasks), Instant.parse("2026-08-21T00:00:00Z")
        );
        configs = mock(AtcoderLeaderboardConfigRepository.class);
        translations = mock(AtcoderProblemTranslationRepository.class);
        when(configs.findById(AtcoderLeaderboardConfig.SINGLETON_ID)).thenReturn(Optional.of(config));
        when(translations.findAllByContestIdOrderByTaskOrderAscIdAsc("abc430"))
                .thenAnswer(invocation -> new ArrayList<>(stored.values()));
        when(translations.findByContestIdAndTaskId(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(1))));
        when(translations.saveAndFlush(any())).thenAnswer(invocation -> {
            AtcoderProblemTranslation entity = invocation.getArgument(0);
            stored.put(entity.getTaskId(), entity);
            return entity;
        });
        doAnswer(invocation -> { stored.clear(); return null; }).when(translations).deleteAllInBatch();

        AtcoderProblemSourceGateway source = taskSource();
        AtcoderProblemTranslator translator = html -> html
                .replace("Problem Statement", "题目描述")
                .replace("Print the value ", "输出这个值 ");
        Executor direct = Runnable::run;
        service = new AtcoderProblemTranslationService(
                configs, translations, source, translator, new AtcoderProblemHtmlProcessor(), mapper,
                direct, direct, Clock.fixed(Instant.parse("2026-08-21T01:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void translatesAllTasksAndPublishesReadyDetails() {
        ProblemOverviewView result = service.startAll(false);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.readyCount()).isEqualTo(2);
        assertThat(result.tasks()).extracting(task -> task.status()).containsOnly("READY");
        ProblemDetailView detail = service.detail("abc430_a");
        assertThat(detail.sourceHtml()).contains("Problem Statement", "<pre>1");
        assertThat(detail.translatedHtml()).contains("题目描述", "输出这个值", "<pre>1");
    }

    @Test
    void ordinaryRestartSkipsReadyRowsAndContestSwitchDeletesThem() {
        service.startAll(false);
        String firstTranslation = stored.get("abc430_a").getTranslatedHtml();

        service.startAll(false);
        assertThat(stored.get("abc430_a").getTranslatedHtml()).isEqualTo(firstTranslation);

        service.onContestChanged("abc430", "abc431");
        assertThat(stored).isEmpty();
        assertThat(service.publicOverview().readyCount()).isZero();
    }

    @Test
    void rejectsStartBeforeQueueingWhenAiIsNotConfigured() {
        AtcoderProblemTranslator missingConfiguration = new AtcoderProblemTranslator() {
            @Override
            public void requireConfigured() {
                throw new IllegalStateException("AI 接口尚未配置");
            }

            @Override
            public String translateToChinese(String sourceHtml) {
                throw new AssertionError("translation must not start");
            }
        };
        Executor direct = Runnable::run;
        AtcoderProblemTranslationService unconfigured = new AtcoderProblemTranslationService(
                configs, translations, taskSource(), missingConfiguration,
                new AtcoderProblemHtmlProcessor(), new ObjectMapper().findAndRegisterModules(),
                direct, direct, Clock.systemUTC());

        assertThatThrownBy(() -> unconfigured.startAll(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI 接口尚未配置");
        assertThat(stored).isEmpty();
    }

    @Test
    void keepsSanitizedAiDraftWhenFinalValidationFails() {
        AtcoderProblemTranslator malformed = html -> html
                .replace("Problem Statement", "题目描述")
                .replaceFirst("__DATAFORGE_ATCODER_PROTECTED_END_0000__", "")
                + "<script>alert(1)</script>";
        Executor direct = Runnable::run;
        service = new AtcoderProblemTranslationService(
                configs, translations, taskSource(), malformed, new AtcoderProblemHtmlProcessor(),
                new ObjectMapper().findAndRegisterModules(), direct, direct, Clock.systemUTC());

        ProblemOverviewView result = service.startAll(false);
        AdminProblemDetailView detail = service.adminDetail("abc430_a");

        assertThat(result.failedCount()).isEqualTo(2);
        assertThat(detail.translatedHtml()).isNull();
        assertThat(detail.draftHtml()).contains("题目描述", "<pre>1\n</pre>")
                .doesNotContain("script", "alert");
        assertThat(detail.editorHtml()).isNotBlank();
    }

    @Test
    void adminCanEditReadyTranslationWithoutChangingSamples() {
        service.startAll(false);
        String edited = service.adminDetail("abc430_a").editorHtml()
                .replace("题目描述", "人工校订题面")
                .replace("<pre>1\n</pre>", "<pre>999</pre>")
                + "<script>alert(1)</script>";

        AdminProblemDetailView saved = service.saveManualTranslation("abc430_a", edited);

        assertThat(saved.task().status()).isEqualTo("READY");
        assertThat(saved.translatedHtml()).contains("人工校订题面", "<pre>1\n</pre>")
                .doesNotContain("999", "script", "alert");
        assertThat(service.detail("abc430_a").translatedHtml()).isEqualTo(saved.translatedHtml());
    }

    private static AtcoderProblemSourceGateway taskSource() {
        return (contestId, taskId) -> """
                <div id="task-statement"><span class="lang-ja"><p>日本語</p></span>
                <span class="lang-en"><h3>Problem Statement</h3><p>Print the value <var>N</var>.</p>
                <h3>Sample Input 1</h3><pre>1
                </pre></span></div>
                """;
    }
}
