package cn.datacraft;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataforge-test;DB_CLOSE_DELAY=-1",
        "dataforge.admin.password=test-admin-password",
        "dataforge.crypto-secret=test-encryption-secret"
})
@AutoConfigureMockMvc
class DataForgeApplicationTest {
    @Autowired MockMvc mvc;

    @Test
    void contextLoadsWithDatabaseAndSecurity() {}

    @Test
    void registrationRequiresInvitationAndCreatesSession() throws Exception {
        String bad = "{\"username\":\"invited_user\",\"password\":\"password123\",\"inviteCode\":\"wrong\"}";
        mvc.perform(post("/api/auth/register").contentType("application/json").content(bad))
                .andExpect(status().isBadRequest());

        String good = "{\"username\":\"invited_user\",\"password\":\"password123\",\"inviteCode\":\"443322\"}";
        mvc.perform(post("/api/auth/register").contentType("application/json").content(good))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("invited_user"))
                .andExpect(jsonPath("$.role").value("USER"));
    }
}
