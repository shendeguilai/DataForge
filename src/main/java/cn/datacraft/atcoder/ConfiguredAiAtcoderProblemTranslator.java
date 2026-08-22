package cn.datacraft.atcoder;

import cn.datacraft.ai.AiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class ConfiguredAiAtcoderProblemTranslator implements AtcoderProblemTranslator {
    static final String TRANSLATION_SYSTEM_PROMPT = """
            You are a professional translator of competitive programming problem statements.
            Your only task is to translate the supplied AtCoder English problem statement into accurate, natural Simplified Chinese.

            Translation requirements:
            1. Translate every explanatory sentence completely and faithfully. Do not omit conditions, quantifiers, notes, or constraints.
            2. Use standard Chinese competitive-programming terminology. For example: integer=整数, sequence=序列, string=字符串, vertex=顶点, edge=边, query=询问, operation=操作, constraint=约束, sample input=样例输入, sample output=样例输出.
               Translate standard headings consistently: Problem Statement=题目描述, Constraints=约束, Input=输入, Output=输出, Sample Input=样例输入, Sample Output=样例输出, Explanation=说明.
            3. Prefer concise and unambiguous Chinese suitable for contestants. Preserve the logical relationship and reference of every sentence.
            4. Preserve the HTML structure and every DataForge marker whose name starts with __DATAFORGE_ATCODER_PROTECTED_. Content between matching BEGIN and END markers is reference-only and must remain present; the server will restore it exactly.
            5. Preserve proper nouns and identifiers when translation would make them ambiguous.
            6. Output only the complete translated HTML. Do not add introductions, summaries, explanations, algorithms, hints, proofs, or answers.
            """;
    static final String TRANSLATION_HEADER = "The following text or image is a problem statement from an AtCoder contest. "
            + "During an ongoing AtCoder contest, only the translation of the problem statement is allowed. "
            + "Any other outputs such as summaries of the problem statement, algorithms, or strategies are strictly prohibited. "
            + "Please provide only the translation of the problem statement into Simplified Chinese.";

    private final AiConfigService config;
    private final RestTemplate rest;

    @Autowired
    ConfiguredAiAtcoderProblemTranslator(AiConfigService config) {
        this(config, restTemplate());
    }

    ConfiguredAiAtcoderProblemTranslator(AiConfigService config, RestTemplate rest) {
        this.config = config;
        this.rest = rest;
    }

    @Override
    public void requireConfigured() {
        requireSettings();
    }

    @Override
    public String translateToChinese(String sourceHtml) {
        AiConfigService.Settings settings = requireSettings();
        Map<String, Object> body = buildRequestBody(settings.baseUrl, settings.model, sourceHtml);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(settings.apiKey);
        String url = settings.baseUrl.replaceAll("/$", "") + "/chat/completions";
        try {
            ResponseEntity<JsonNode> response = rest.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class
            );
            String content = response.getBody() == null ? "" : response.getBody()
                    .path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) throw new IllegalStateException("AI 未返回译文");
            return content;
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("AI 翻译请求失败，状态码：" + ex.getStatusCode().value(), ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            throw new IllegalStateException("AI 翻译请求失败："
                    + (message == null || message.isBlank() ? ex.getClass().getSimpleName() : message), ex);
        }
    }

    private AiConfigService.Settings requireSettings() {
        AiConfigService.Settings settings = config.current();
        if (settings.baseUrl == null || settings.baseUrl.isBlank()
                || settings.apiKey == null || settings.apiKey.isBlank()) {
            throw new IllegalStateException("AI 接口尚未配置，请先在后台配置 Base URL 和 API Key");
        }
        if (settings.model == null || settings.model.isBlank()) {
            throw new IllegalStateException("AI 模型尚未配置，请先在后台填写模型名称");
        }
        return settings;
    }

    static String buildPrompt(String sourceHtml) {
        return TRANSLATION_HEADER + "\n\n" + (sourceHtml == null ? "" : sourceHtml);
    }

    static Map<String, Object> buildRequestBody(String baseUrl, String model, String sourceHtml) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", TRANSLATION_SYSTEM_PROMPT),
                Map.of("role", "user", "content", buildPrompt(sourceHtml))
        ));
        if (isDeepseek(baseUrl, model)) {
            body.put("thinking", Map.of("type", "enabled"));
            body.put("reasoning_effort", "high");
        } else {
            body.put("temperature", 0);
        }
        return body;
    }

    private static boolean isDeepseek(String baseUrl, String model) {
        String url = baseUrl == null ? "" : baseUrl.toLowerCase();
        String modelName = model == null ? "" : model.toLowerCase();
        return url.contains("deepseek.com") || modelName.startsWith("deepseek-");
    }

    private static RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        return new RestTemplate(factory);
    }
}
