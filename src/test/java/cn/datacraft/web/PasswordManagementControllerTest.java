package cn.datacraft.web;

import cn.datacraft.user.UserAccount;
import cn.datacraft.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:password-management-test;DB_CLOSE_DELAY=-1",
        "dataforge.admin.password=test-admin-password",
        "dataforge.crypto-secret=test-encryption-secret"
})
@AutoConfigureMockMvc
class PasswordManagementControllerTest {
    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired PasswordEncoder encoder;

    @Test
    void authenticatedUserCanChangePasswordAndOldPasswordStopsWorking() throws Exception {
        String username = "password_user";
        String oldPassword = "old-password-123";
        String newPassword = "new-password-456";
        users.register(username, oldPassword, "443322");

        mvc.perform(put("/api/auth/password")
                        .with(user(username).roles("USER"))
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + oldPassword + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andExpect(status().isNoContent());

        UserAccount changed = users.requireByUsername(username);
        assertThat(encoder.matches(newPassword, changed.getPasswordHash())).isTrue();
        assertThat(encoder.matches(oldPassword, changed.getPasswordHash())).isFalse();

        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + oldPassword + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void passwordChangeRequiresAuthenticationAndCorrectCurrentPassword() throws Exception {
        String username = "wrong_password_user";
        String oldPassword = "old-password-123";
        users.register(username, oldPassword, "443322");
        String body = "{\"currentPassword\":\"incorrect-password\",\"newPassword\":\"new-password-456\"}";

        mvc.perform(put("/api/auth/password")
                        .with(anonymous())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/auth/password")
                        .with(user(username).roles("USER"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("当前密码不正确"));

        assertThat(encoder.matches(oldPassword, users.requireByUsername(username).getPasswordHash())).isTrue();
    }

    @Test
    void onlyAdminCanResetAnotherUsersPassword() throws Exception {
        String targetUsername = "reset_target";
        String oldPassword = "target-password-123";
        String newPassword = "reset-password-456";
        UserAccount target = users.register(targetUsername, oldPassword, "443322");
        users.register("regular_operator", "operator-password-123", "443322");
        String body = "{\"newPassword\":\"" + newPassword + "\"}";

        mvc.perform(put("/api/admin/users/" + target.getId() + "/password")
                        .with(user("regular_operator").roles("USER"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());
        assertThat(encoder.matches(oldPassword, users.requireByUsername(targetUsername).getPasswordHash())).isTrue();

        mvc.perform(put("/api/admin/users/" + target.getId() + "/password")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNoContent());
        assertThat(encoder.matches(newPassword, users.requireByUsername(targetUsername).getPasswordHash())).isTrue();
    }

    @Test
    void adminMustUseAuthenticatedChangeFlowForOwnPassword() throws Exception {
        Long adminId = users.requireByUsername("admin").getId();

        mvc.perform(put("/api/admin/users/" + adminId + "/password")
                        .with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"newPassword\":\"another-admin-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请通过“修改密码”功能修改自己的密码"));
    }
}
