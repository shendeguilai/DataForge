package cn.datacraft.config;

import cn.datacraft.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityConfig.class)
            .withBean(UserService.class, () -> mock(UserService.class));

    @Test
    void nonWebMaintenanceContextKeepsPasswordEncoderWithoutCreatingFilterChain() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PasswordEncoder.class);
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
        });
    }

    @Test
    void browserHtmlDetectionDoesNotRedirectApiAssetsOrWebSockets() {
        MockHttpServletRequest html = new MockHttpServletRequest("GET", "/admin.html");
        html.addHeader("Accept", "text/html,application/xhtml+xml");
        assertThat(SecurityConfig.isBrowserHtmlRequest(html)).isTrue();

        MockHttpServletRequest api = new MockHttpServletRequest("GET", "/api/jobs");
        api.addHeader("Accept", "text/html");
        assertThat(SecurityConfig.isBrowserHtmlRequest(api)).isFalse();

        MockHttpServletRequest asset = new MockHttpServletRequest("GET", "/admin.js");
        asset.addHeader("Accept", "text/html");
        assertThat(SecurityConfig.isBrowserHtmlRequest(asset)).isFalse();

        MockHttpServletRequest websocket = new MockHttpServletRequest("GET", "/ws/tools/quiz");
        websocket.addHeader("Accept", "text/html");
        assertThat(SecurityConfig.isBrowserHtmlRequest(websocket)).isFalse();
    }
}
