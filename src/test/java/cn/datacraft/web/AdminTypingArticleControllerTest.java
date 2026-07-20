package cn.datacraft.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:typing-article-admin-test;DB_CLOSE_DELAY=-1",
        "dataforge.admin.password=test-admin-password",
        "dataforge.crypto-secret=test-encryption-secret"
})
@AutoConfigureMockMvc
class AdminTypingArticleControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void adminCanCreateUpdateAndDeleteTypingArticle() throws Exception {
        mvc.perform(get("/api/admin/typing-articles").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/typing-articles").with(user("student").roles("USER")))
                .andExpect(status().isForbidden());

        String createdJson = mvc.perform(post("/api/admin/typing-articles")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"title\":\"API 新增文章\",\"category\":\"英文\",\"content\":\"A short article created by the administration test.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("英文"))
                .andExpect(jsonPath("$.length").value(51))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(createdJson);
        String id = created.path("id").asText();

        mvc.perform(put("/api/admin/typing-articles/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"title\":\"API 修改文章\",\"category\":\"代码\",\"content\":\"int main() { return 0; }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("API 修改文章"))
                .andExpect(jsonPath("$.category").value("代码"));

        mvc.perform(delete("/api/admin/typing-articles/" + id)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/admin/typing-articles/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"title\":\"不存在\",\"category\":\"中文\",\"content\":\"正文\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void articleValidationRejectsUnknownCategory() throws Exception {
        mvc.perform(post("/api/admin/typing-articles")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"title\":\"错误分类\",\"category\":\"其他\",\"content\":\"正文\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("文章分类只能是中文、英文或代码"));
    }
}
