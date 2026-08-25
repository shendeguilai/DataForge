package cn.datacraft.atcoder;

import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemDetailView;
import cn.datacraft.atcoder.AtcoderProblemDtos.AdminProblemDetailView;
import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemOverviewView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    @Test
    void adminCanPublishStructuredManualStatementWithoutFetchedSource() {
        String manual = """
                【题目描述】
                给定整数 N，输出 N。

                【输入格式】
                一个整数 N。

                【输出格式】
                输出 N。

                【样例输入 1】
                7

                【样例输出 1】
                7
                """;

        AdminProblemDetailView saved = service.saveStructuredManualTranslation("abc430_a", manual);

        assertThat(saved.task().status()).isEqualTo("READY");
        assertThat(saved.sourceHtml()).isNull();
        assertThat(saved.translatedHtml()).contains("给定整数 N", "<pre>7</pre>");
        assertThat(saved.editorHtml()).isEqualTo(saved.translatedHtml());
        assertThat(service.detail("abc430_a").translatedHtml()).isEqualTo(saved.translatedHtml());
    }

    @Test
    void importsMatchingContestPdfAndPublishesAiHtmlWithoutAtcoderStructureComparison() throws Exception {
        AtcoderProblemTranslator pdfTranslator = new AtcoderProblemTranslator() {
            @Override
            public String translateToChinese(String sourceHtml) {
                return sourceHtml;
            }

            @Override
            public String translatePdfTextToChinese(String label, String title, String sourceText) {
                return """
                        <section><h3>题目描述</h3><p>这是 PDF 题目 %s。</p></section>
                        <section><h3>输入格式</h3><p>输入内容。</p></section>
                        <section><h3>输出格式</h3><p>输出答案。</p></section>
                        <section><h3>样例输入 1</h3><pre>1</pre></section>
                        <section><h3>样例输出 1</h3><pre>1</pre></section>
                        """.formatted(label);
            }
        };
        Executor direct = Runnable::run;
        service = new AtcoderProblemTranslationService(
                configs, translations, taskSource(), pdfTranslator, new AtcoderProblemHtmlProcessor(),
                new ObjectMapper().findAndRegisterModules(), direct, direct, Clock.systemUTC());
        byte[] pdf = contestPdf(
                "A - Warm Up\nProblem Statement\nYou are given an integer A. Print A as the answer.\nConstraints\nA is an integer.\nInput\nA\nOutput\nA\nSample Input 1\n1\nSample Output 1\n1",
                "B - Strings\nProblem Statement\nYou are given a string B. Print B as the answer.\nConstraints\nB is a string.\nInput\nB\nOutput\nB\nSample Input 1\n1\nSample Output 1\n1"
        );

        ProblemOverviewView result = service.importPdf("abc430.pdf", pdf);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.readyCount()).isEqualTo(2);
        assertThat(service.adminDetail("abc430_a").sourceHtml()).contains("PDF Extracted Source", "Print A");
        assertThat(service.detail("abc430_a").translatedHtml()).contains("这是 PDF 题目 A", "<pre>1</pre>");
        AdminProblemDetailView edited = service.saveManualTranslation("abc430_a",
                service.adminDetail("abc430_a").editorHtml().replace("这是 PDF 题目", "人工校订 PDF 题目"));
        assertThat(edited.translatedHtml()).contains("人工校订 PDF 题目");
    }

    @Test
    void importsMatchingContestMarkdownThenTranslatesOneOrAllFromStoredSource() {
        AtcoderProblemTranslator markdownTranslator = new AtcoderProblemTranslator() {
            @Override
            public String translateToChinese(String sourceHtml) {
                return sourceHtml;
            }

            @Override
            public String translateMarkdownToChinese(String label, String title, String renderedSourceHtml) {
                return renderedSourceHtml
                        .replace("Problem Statement", "题目描述")
                        .replace("Sample Input", "样例输入")
                        .replace("Sample Output", "样例输出")
                        .replace("Input", "输入格式")
                        .replace("Output", "输出格式")
                        .replace("Given", "给定")
                        .replace("Print the answer", "输出答案");
            }
        };
        Executor direct = Runnable::run;
        service = new AtcoderProblemTranslationService(
                configs, translations, taskSource(), markdownTranslator, new AtcoderProblemHtmlProcessor(),
                new ObjectMapper().findAndRegisterModules(), direct, direct, Clock.systemUTC());
        String markdown = markdownProblem("A", "Markdown Warm Up", "abc430_a", "N", "1")
                + "\n\n" + markdownProblem("B", "Markdown Strings", "abc430_b", "S", "abc");

        ProblemOverviewView result = service.importMarkdown(
                "ABC430_ALL.md", markdown.getBytes(StandardCharsets.UTF_8));

        assertThat(result.status()).isEqualTo("IMPORTED");
        assertThat(result.readyCount()).isZero();
        assertThat(result.importedBundle().filename()).isEqualTo("ABC430_ALL.md");
        assertThat(result.importedBundle().problemCount()).isEqualTo(2);
        assertThat(result.tasks()).extracting(task -> task.status()).containsOnly("IMPORTED");
        assertThat(result.tasks()).extracting(task -> task.name())
                .containsExactly("Markdown Warm Up", "Markdown Strings");
        assertThat(service.adminDetail("abc430_a").sourceHtml())
                .contains("Markdown Imported Source", "Imported file: ABC430_ALL.md", "Problem: `abc430_a`");
        assertThat(service.detail("abc430_a").translatedHtml()).isNull();

        ProblemOverviewView retried = service.retryTask("abc430_a");

        assertThat(retried.status()).isEqualTo("PARTIAL");
        assertThat(retried.readyCount()).isEqualTo(1);
        assertThat(retried.tasks().get(0).name()).isEqualTo("Markdown Warm Up");
        assertThat(service.adminDetail("abc430_a").sourceHtml()).contains("Markdown Imported Source");
        assertThat(service.detail("abc430_a").translatedHtml())
                .contains("题目描述", "<var>N</var>", "<pre><code>1\n</code></pre>");

        ProblemOverviewView all = service.translateImportedMarkdownAll();

        assertThat(all.status()).isEqualTo("READY");
        assertThat(all.readyCount()).isEqualTo(2);
        AdminProblemDetailView edited = service.saveManualTranslation("abc430_a",
                service.adminDetail("abc430_a").editorHtml().replace("给定", "人工校订：给定"));
        assertThat(edited.translatedHtml()).contains("人工校订：给定");
    }

    @Test
    void markdownUploadDoesNotRequireAiUntilTranslationStarts() {
        AtcoderProblemTranslator unavailable = new AtcoderProblemTranslator() {
            @Override
            public void requireConfigured() {
                throw new IllegalStateException("AI 接口尚未配置");
            }

            @Override
            public String translateToChinese(String sourceHtml) {
                throw new AssertionError("upload must not call AI");
            }
        };
        Executor direct = Runnable::run;
        service = new AtcoderProblemTranslationService(
                configs, translations, taskSource(), unavailable, new AtcoderProblemHtmlProcessor(),
                new ObjectMapper().findAndRegisterModules(), direct, direct, Clock.systemUTC());
        String markdown = markdownProblem("A", "Warm Up", "abc430_a", "N", "1")
                + "\n\n" + markdownProblem("B", "Strings", "abc430_b", "S", "abc");

        ProblemOverviewView imported = service.importMarkdown(
                "ABC430_ALL.md", markdown.getBytes(StandardCharsets.UTF_8));

        assertThat(imported.status()).isEqualTo("IMPORTED");
        assertThatThrownBy(() -> service.retryTask("abc430_a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AI 接口尚未配置");
        assertThat(service.adminOverview().status()).isEqualTo("IMPORTED");
    }

    private static AtcoderProblemSourceGateway taskSource() {
        return (contestId, taskId) -> """
                <div id="task-statement"><span class="lang-ja"><p>日本語</p></span>
                <span class="lang-en"><h3>Problem Statement</h3><p>Print the value <var>N</var>.</p>
                <h3>Sample Input 1</h3><pre>1
                </pre></span></div>
                """;
    }

    private static String markdownProblem(String label, String title, String problemId,
                                          String variable, String sample) {
        return """
                # %s - %s

                - Contest: `abc430`
                - Problem: `%s`
                - Source: https://atcoder.jp/contests/abc430/tasks/%s

                Score : $100$ points

                ## Problem Statement

                Given $%s$, print the answer without changing any protected content.

                ## Constraints

                - $%s$ is valid input data.

                ## Input

                ```text
                %s
                ```

                ## Output

                Print the answer.

                ## Sample Input 1

                ```text
                %s
                ```

                ## Sample Output 1

                ```text
                %s
                ```
                """.formatted(label, title, problemId, problemId, variable, variable, variable, sample, sample);
    }

    private static byte[] contestPdf(String... pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String value : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.newLineAtOffset(48, 750);
                    for (String line : value.split("\\n", -1)) {
                        content.showText(line);
                        content.newLineAtOffset(0, -16);
                    }
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
