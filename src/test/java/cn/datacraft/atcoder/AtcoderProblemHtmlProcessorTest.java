package cn.datacraft.atcoder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtcoderProblemHtmlProcessorTest {
    private final AtcoderProblemHtmlProcessor processor = new AtcoderProblemHtmlProcessor();

    @Test
    void extractsOnlyEnglishAndKeepsOnlySafeAtcoderResources() {
        String page = """
                <html><body><div id="task-statement"><span class="lang-ja"><p>日本語</p></span>
                <span class="lang-en"><h3>Problem Statement</h3><p>Hello <var>N</var>.</p>
                <a href="/contests/abc430">contest</a><img src="https://evil.example/image.png" onerror="alert(1)">
                <script>alert(1)</script></span></div></body></html>
                """;

        String source = processor.extractEnglish(page);

        assertThat(source).contains("Problem Statement", "Hello", "https://atcoder.jp/contests/abc430");
        assertThat(source).doesNotContain("日本語", "script", "onerror", "evil.example");
    }

    @Test
    void restoresVariablesCodeAndSamplesAfterTranslation() {
        String source = """
                <h3>Problem Statement</h3><p>Given <var>N\\leq 10</var>, print <code>Yes</code>.</p>
                <h3>Sample Input 1</h3><pre><var>N</var> 10
                </pre>
                """;
        AtcoderProblemHtmlProcessor.TranslationInput input = processor.prepareTranslationInput(source);
        String aiOutput = input.html()
                .replace("Problem Statement", "题目描述")
                .replace("Given", "给定")
                .replace("print", "输出")
                .replace("N\\leq 10", "被修改的公式")
                .replace("Yes", "被修改的代码")
                .replace("<pre>", "<pre>被修改的样例");
        String translated = processor.prepareTranslation(source, input, aiOutput);

        assertThat(translated).contains("题目描述", "给定", "N\\leq 10", "<code>Yes</code>");
        assertThat(translated).contains("<pre><var>N</var> 10\n</pre>");
        assertThat(input.html()).contains("N\\leq 10", "<code>Yes</code>", "<pre>",
                "__DATAFORGE_ATCODER_PROTECTED_BEGIN_", "__DATAFORGE_ATCODER_PROTECTED_END_");
    }

    @Test
    void rejectsTranslationThatDropsProtectedPlaceholder() {
        String source = "<p><var>N</var></p><pre>1</pre>";
        AtcoderProblemHtmlProcessor.TranslationInput input = processor.prepareTranslationInput(source);

        assertThatThrownBy(() -> processor.prepareTranslation(source, input, "<p>中文，但占位符丢失</p>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公式、代码或样例占位符");
    }

    @Test
    void restoresLinksAndImagesFromProtectedPlaceholders() {
        String source = "<p>See <a href=\"https://atcoder.jp/contests/abc430\">contest</a>"
                + "<img src=\"https://img.atcoder.jp/assets/logo.png\"></p>";
        AtcoderProblemHtmlProcessor.TranslationInput input = processor.prepareTranslationInput(source);

        String translated = processor.prepareTranslation(source, input,
                input.html().replace("See", "参见"));

        assertThat(translated).contains("参见", "https://atcoder.jp/contests/abc430",
                "https://img.atcoder.jp/assets/logo.png");
    }

    @Test
    void keepsSafeDraftWhenAiDropsAProtectedMarker() {
        String source = "<h3>Problem Statement</h3><p>Print <var>N</var>.</p><pre>1</pre>";
        AtcoderProblemHtmlProcessor.TranslationInput input = processor.prepareTranslationInput(source);
        String brokenOutput = input.html()
                .replace("Problem Statement", "题目描述")
                .replaceFirst("__DATAFORGE_ATCODER_PROTECTED_END_0000__", "")
                + "<script>alert(1)</script>";

        String draft = processor.prepareDraft(input, brokenOutput);

        assertThat(draft).contains("题目描述", "<pre>1</pre>")
                .doesNotContain("DATAFORGE_ATCODER_PROTECTED", "script", "alert(1)");
    }

    @Test
    void sanitizesManualEditAndRestoresProtectedContent() {
        String source = "<h3>Problem Statement</h3><p>Print <var>N</var>.</p><pre>1\n</pre>";
        String edited = "<h3 onclick=\"alert(1)\">人工题面</h3><p>输出 <var>错误</var>。</p>"
                + "<pre>错误样例</pre><script>alert(2)</script>";

        String translated = processor.prepareEditedTranslation(source, edited);

        assertThat(translated).contains("人工题面", "<var>N</var>", "<pre>1\n</pre>")
                .doesNotContain("错误样例", "onclick", "script", "alert");
    }

    @Test
    void convertsStructuredManualTextIntoSafeStatementHtml() {
        String manual = """
                【题目描述】
                给定两个整数 A 和 B，输出它们的和。

                【输入格式】
                输入包含两个整数 A 和 B。

                【输出格式】
                输出 A+B。

                【样例输入 1】
                1 2

                【样例输出 1】
                3
                """;

        String translated = processor.prepareStructuredManualTranslation(manual);

        assertThat(translated).contains("<h3>题目描述</h3>", "给定两个整数", "<pre>1 2</pre>", "<pre>3</pre>");
    }

    @Test
    void rejectsManualTextThatDoesNotUseTheStandardTemplate() {
        assertThatThrownBy(() -> processor.prepareStructuredManualTranslation("这是没有段落标记的题面"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("【题目描述】");
        assertThatThrownBy(() -> processor.prepareStructuredManualTranslation("【题目描述】\n\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void acceptsSanitizedPdfTranslationAndAllowsLaterVisualEditing() {
        String source = processor.preparePdfSource("A", "Warm Up", 1, 2,
                "Problem Statement\nPrint A.\nSample Input 1\n1\nSample Output 1\n1");
        String translated = processor.preparePdfTranslation("""
                ```html
                <section><h3>题目描述</h3><p>输出 \\(A\\)。</p></section>
                <section><h3>输入格式</h3><p>输入一个整数。</p></section>
                <section><h3>输出格式</h3><p>输出答案。</p></section>
                <section><h3>样例输入 1</h3><pre>1</pre></section>
                <section><h3>样例输出 1</h3><pre>1</pre></section>
                ```
                """, 1);

        assertThat(processor.isPdfSource(source)).isTrue();
        assertThat(translated).contains("题目描述", "<pre>1</pre>").doesNotContain("```", "script");
    }

    @Test
    void rendersMarkdownAndProtectsMathCodeLinksAndSamplesDuringTranslation() {
        AtcoderContestMarkdownParser.ParsedProblem problem = new AtcoderContestMarkdownParser.ParsedProblem(
                "A", "Warm Up", "abc430", "abc430_a", """
                # A - Warm Up

                - Contest: `abc430`
                - Problem: `abc430_a`
                - Source: https://atcoder.jp/contests/abc430/tasks/abc430_a

                Score : $100$ points

                ## Problem Statement

                Given $N$, print `Yes`.

                ## Input

                ```text
                N Q
                U_1 V_1
                \\vdots
                \\text{query}_1
                ```

                ## Output

                Print the answer in the following format.

                ```text
                K
                v_1 v_2 \\ldots v_K
                ```

                ## Sample Input 1

                ```text
                1
                ```

                ## Sample Output 1

                ```text
                Yes
                ```
                """, 1);
        String storedSource = processor.prepareMarkdownSource(problem, "ABC430_ALL.md");
        String rendered = processor.renderMarkdownProblem(problem.sourceMarkdown());
        AtcoderProblemHtmlProcessor.TranslationInput input = processor.prepareTranslationInput(rendered);
        String aiOutput = input.html()
                .replace("Problem Statement", "题目描述")
                .replace("Input", "输入格式")
                .replace("Output", "输出格式")
                .replace("Sample 输入格式", "样例输入")
                .replace("Sample 输出格式", "样例输出")
                .replace("Given", "给定")
                .replace("Print the answer", "输出答案");

        String translated = processor.prepareMarkdownTranslation(rendered, input, aiOutput, 1);

        assertThat(storedSource).contains("Markdown Imported Source", "# A - Warm Up");
        assertThat(processor.isMarkdownSource(storedSource)).isTrue();
        assertThat(processor.markdownSourceFilename(storedSource)).isEqualTo("ABC430_ALL.md");
        assertThat(processor.markdownSourceText(storedSource)).isEqualTo(problem.sourceMarkdown());
        assertThat(rendered).doesNotContain("<h1>", "Contest:", "Problem:")
                .contains("<var>100</var>", "<var>N</var>", "<code>Yes</code>",
                        "<pre><var>N</var>   <var>Q</var><br><var>U_1</var>   <var>V_1</var>",
                        "<var>\\vdots</var>", "<var>\\text{query}_1</var>",
                        "<pre><var>K</var><br><var>v_1</var>   <var>v_2</var>   <var>\\ldots</var>   <var>v_K</var>");
        assertThat(translated).contains("题目描述", "输入格式", "输出格式",
                "<var>N</var>", "<code>Yes</code>", "<pre><code>1\n</code></pre>");
    }

    @Test
    void recoversMarkdownTranslationWhenAiDropsMarkerButKeepsHtmlStructure() {
        String rendered = """
                <h2>Problem Statement</h2><p>Given <var>N</var>, print <code>Yes</code>.</p>
                <h2>Input</h2><pre><var>N</var></pre>
                <h2>Output</h2><p>Print the answer.</p>
                <h2>Sample Input 1</h2><pre><code>1
                </code></pre>
                <h2>Sample Output 1</h2><pre><code>Yes
                </code></pre>
                """;
        AtcoderProblemHtmlProcessor.TranslationInput input = processor.prepareTranslationInput(rendered);
        String output = input.html()
                .replace("Problem Statement", "题目描述")
                .replace("Sample Input", "样例输入")
                .replace("Sample Output", "样例输出")
                .replace("Input", "输入格式")
                .replace("Output", "输出格式")
                .replaceFirst("__DATAFORGE_ATCODER_PROTECTED_END_0000__", "");

        String translated = processor.prepareMarkdownTranslation(rendered, input, output, 1);

        assertThat(translated).contains("题目描述", "<var>N</var>", "<code>Yes</code>",
                "<pre><code>1\n</code></pre>")
                .doesNotContain("DATAFORGE_ATCODER_PROTECTED");
    }

    @Test
    void formatsOutputAndInteractionButKeepsSamplesAndLiteralWordsAsCode() {
        String displayed = processor.prepareStatementDisplay("""
                <h3>输出格式：</h3><p>按以下格式输出：</p><pre>K
                v_1 v_2 \\ldots v_K
                Yes</pre>
                <h3>样例输出 1</h3><pre>3
                1 2 3</pre>
                <h3>Interaction</h3><pre>? i j</pre>
                """);

        assertThat(displayed)
                .contains("<pre><var>K</var><br>", "<var>\\ldots</var>", "<code>Yes</code>",
                        "<h3>样例输出 1</h3>", "<pre>3\n1 2 3</pre>",
                        "<h3>Interaction</h3>", "<pre><code>?</code>   <var>i</var>   <var>j</var></pre>")
                .doesNotContain("<h3>样例输出 1</h3><pre><var>");
    }
}
