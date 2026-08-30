package cn.datacraft.web;

import cn.datacraft.tools.CspPaperStudioClient;
import cn.datacraft.tools.CspPaperStudioException;
import cn.datacraft.tools.CspPaperStudioProperties;
import cn.datacraft.user.UserService;
import cn.datacraft.config.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CspPaperStudioController.class)
@Import(SecurityConfig.class)
class CspPaperStudioControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean CspPaperStudioClient client;
    @MockitoBean CspPaperStudioProperties properties;
    @MockitoBean UserService users;

    @BeforeEach
    void configureLimits() {
        given(properties.getMaxMarkdownBytes()).willReturn(2 * 1024 * 1024);
    }

    @Test
    void pageIsPublicButToolApisRequireLogin() throws Exception {
        mvc.perform(get("/csp-paper-studio.html").with(anonymous()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tools/csp-paper-studio/samples/2022j").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tools/csp-paper-studio/analyze")
                        .with(anonymous())
                        .contentType("application/json")
                        .content("{\"markdown\":\"# test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void forwardsSampleAndAnalysisWithoutCaching() throws Exception {
        given(client.sample("2022j")).willReturn(objectMapper.readTree(
                "{\"name\":\"2022_CSP-J.md\",\"markdown\":\"# sample\"}"
        ));
        given(client.analyze("# sample")).willReturn(objectMapper.readTree(
                "{\"counts\":{\"ok\":20,\"warning\":0,\"error\":0}}"
        ));

        mvc.perform(get("/api/tools/csp-paper-studio/samples/2022j"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.name").value("2022_CSP-J.md"));
        mvc.perform(post("/api/tools/csp-paper-studio/analyze")
                        .contentType("application/json")
                        .content("{\"markdown\":\"# sample\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.counts.ok").value(20));
    }

    @Test
    @WithMockUser
    void validatesNumberingPayloadSizeAndSampleName() throws Exception {
        mvc.perform(get("/api/tools/csp-paper-studio/samples/unknown"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/tools/csp-paper-studio/export")
                        .contentType("application/json")
                        .content("{\"markdown\":\"# sample\",\"filename\":\"test.md\",\"numbering\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("题号模式必须是 auto、global 或 local"));

        given(properties.getMaxMarkdownBytes()).willReturn(4);
        mvc.perform(post("/api/tools/csp-paper-studio/analyze")
                        .contentType("application/json")
                        .content("{\"markdown\":\"中文内容\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Markdown 不能超过 2 MiB"));
    }

    @Test
    @WithMockUser
    void returnsWordWithUtf8FilenameAndMapsUpstreamErrors() throws Exception {
        given(client.export("# sample", "中文试卷.md", "auto"))
                .willReturn(new CspPaperStudioClient.ExportedWord(new byte[]{1, 2, 3}, "中文试卷.docx"));
        mvc.perform(post("/api/tools/csp-paper-studio/export")
                        .contentType("application/json")
                        .content("{\"markdown\":\"# sample\",\"filename\":\"中文试卷.md\",\"numbering\":\"auto\"}"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("UTF-8''")));

        given(client.analyze(anyString())).willThrow(new CspPaperStudioException(
                HttpStatus.SERVICE_UNAVAILABLE, "CSP Paper Studio 暂时不可用，请稍后重试"
        ));
        mvc.perform(post("/api/tools/csp-paper-studio/analyze")
                        .contentType("application/json")
                        .content("{\"markdown\":\"# sample\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("CSP Paper Studio 暂时不可用，请稍后重试"));
    }
}
