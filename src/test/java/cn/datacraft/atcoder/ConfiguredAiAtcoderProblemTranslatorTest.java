package cn.datacraft.atcoder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredAiAtcoderProblemTranslatorTest {
    @Test
    void promptStartsWithOfficialTranslationOnlyHeaderAndThenSource() {
        String source = "<h3>Problem Statement</h3><p>Text</p>";

        String prompt = ConfiguredAiAtcoderProblemTranslator.buildPrompt(source);

        assertThat(prompt).startsWith(ConfiguredAiAtcoderProblemTranslator.TRANSLATION_HEADER);
        assertThat(prompt).endsWith(source);
        assertThat(prompt.substring(ConfiguredAiAtcoderProblemTranslator.TRANSLATION_HEADER.length()))
                .isEqualTo("\n\n" + source);
    }

    @Test
    void deepseekRequestUsesProfessionalSystemPromptAndExplicitHighThinking() {
        Map<String, Object> body = ConfiguredAiAtcoderProblemTranslator.buildRequestBody(
                "https://api.deepseek.com/v1", "deepseek-v4-flash", "<p>Statement</p>");

        assertThat(body).containsEntry("thinking", Map.of("type", "enabled"))
                .containsEntry("reasoning_effort", "high")
                .doesNotContainKey("temperature");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("role", "system");
        assertThat(messages.get(0).get("content"))
                .contains("professional translator", "standard Chinese competitive-programming terminology")
                .doesNotContain("algorithm solution");
        assertThat(messages.get(1).get("content"))
                .startsWith(ConfiguredAiAtcoderProblemTranslator.TRANSLATION_HEADER)
                .endsWith("<p>Statement</p>");
    }

    @Test
    void genericCompatibleApiKeepsDeterministicNonReasoningRequest() {
        Map<String, Object> body = ConfiguredAiAtcoderProblemTranslator.buildRequestBody(
                "https://api.example.com/v1", "translator-model", "<p>Statement</p>");

        assertThat(body).containsEntry("temperature", 0)
                .doesNotContainKeys("thinking", "reasoning_effort");
    }
}
