package cn.datacraft.config;

import cn.datacraft.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

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
}
