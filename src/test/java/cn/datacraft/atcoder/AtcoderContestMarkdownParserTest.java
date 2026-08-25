package cn.datacraft.atcoder;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtcoderContestMarkdownParserTest {
    private final AtcoderContestMarkdownParser parser = new AtcoderContestMarkdownParser();

    @Test
    void splitsStructuredContestAndIgnoresHeadingsInsideCodeFences() {
        String markdown = """
                # A - Warm Up

                - Contest: `abc430`
                - Problem: `abc430_a`

                ## Problem Statement

                Given $N$, print `Yes` when it is positive.

                ## Input

                ```text
                # B - This is sample data, not a task heading
                N
                ```

                ## Output

                Print the answer.

                ## Sample Input 1

                ```text
                1
                ```

                ## Sample Output 1

                ```text
                Yes
                ```

                # B - Strings

                - Contest: `abc430`
                - Problem: `abc430_b`

                ## Problem Statement

                Given a string $S$, print the same string without changing it.

                ## Input

                ```text
                S
                ```

                ## Output

                Print the answer.

                ## Sample Input 1

                ```text
                abc
                ```

                ## Sample Output 1

                ```text
                abc
                ```
                """;

        AtcoderContestMarkdownParser.ParsedContestMarkdown parsed = parser.parse(
                markdown.getBytes(StandardCharsets.UTF_8), "abc430_all.md");

        assertThat(parsed.contestId()).isEqualTo("abc430");
        assertThat(parsed.filename()).isEqualTo("abc430_all.md");
        assertThat(parsed.problems()).hasSize(2);
        assertThat(parsed.problems().get(0).label()).isEqualTo("A");
        assertThat(parsed.problems().get(0).problemId()).isEqualTo("abc430_a");
        assertThat(parsed.problems().get(0).samplePairCount()).isEqualTo(1);
        assertThat(parsed.problems().get(0).sourceMarkdown())
                .contains("# B - This is sample data, not a task heading");
        assertThat(parsed.problems().get(1).title()).isEqualTo("Strings");
    }

    @Test
    void rejectsUnclosedFenceAndMissingMetadata() {
        String unclosed = """
                # A - Warm Up
                - Contest: `abc430`
                - Problem: `abc430_a`
                ## Problem Statement
                This statement is deliberately long enough for validation.
                ```text
                1
                """;
        String missing = """
                # A - Warm Up
                - Contest: `abc430`
                ## Problem Statement
                This statement is deliberately long enough to pass the minimum content-length validation.
                ## Input
                Input one integer.
                ## Output
                Print it.
                """;

        assertThatThrownBy(() -> parser.parse(unclosed.getBytes(StandardCharsets.UTF_8), "bad.md"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未闭合");
        assertThatThrownBy(() -> parser.parse(missing.getBytes(StandardCharsets.UTF_8), "bad.md"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Problem");
    }
}
