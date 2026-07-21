package cn.datacraft.migration;

import cn.datacraft.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dataforge.admin-reset.enabled", havingValue = "true")
public class AdminPasswordResetRunner implements ApplicationRunner {
    private final UserService users;
    private final ConfigurableApplicationContext context;
    private final String username;
    private final String password;

    public AdminPasswordResetRunner(UserService users,
                                    ConfigurableApplicationContext context,
                                    @Value("${dataforge.admin-reset.username}") String username,
                                    @Value("${dataforge.admin-reset.password}") String password) {
        this.users = users;
        this.context = context;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            users.resetAdminPassword(username, password);
            System.out.println("管理员密码已重置：" + username);
        } finally {
            context.close();
        }
    }
}
