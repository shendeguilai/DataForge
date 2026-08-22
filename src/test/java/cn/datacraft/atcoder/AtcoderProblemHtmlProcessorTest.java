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
}
