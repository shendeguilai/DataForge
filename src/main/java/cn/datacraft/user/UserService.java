package cn.datacraft.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

@Service
public class UserService implements UserDetailsService {
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final String inviteCode;
    private final String adminUsername;
    private final String adminPassword;
    private final boolean bootstrapEnabled;

    public UserService(UserAccountRepository users, PasswordEncoder encoder,
                       @Value("${dataforge.invite-code}") String inviteCode,
                       @Value("${dataforge.admin.username}") String adminUsername,
                       @Value("${dataforge.admin.password}") String adminPassword,
                       @Value("${dataforge.bootstrap-enabled:true}") boolean bootstrapEnabled) {
        this.users = users;
        this.encoder = encoder;
        this.inviteCode = inviteCode;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.bootstrapEnabled = bootstrapEnabled;
    }

    @PostConstruct
    public void initializeAdmin() {
        if (!bootstrapEnabled) return;
        if (!users.existsByUsername(adminUsername.toLowerCase(Locale.ROOT))) {
            create(adminUsername, adminPassword, "ADMIN");
        }
    }

    public UserAccount register(String username, String password, String providedInviteCode) {
        if (!inviteCode.equals(providedInviteCode)) throw new IllegalArgumentException("邀请码不正确");
        validateCredentials(username, password);
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (users.existsByUsername(normalized)) throw new IllegalArgumentException("用户名已存在");
        return create(normalized, password, "USER");
    }

    private UserAccount create(String username, String password, String role) {
        UserAccount user = new UserAccount();
        user.setUsername(username.trim().toLowerCase(Locale.ROOT));
        user.setPasswordHash(encoder.encode(password));
        user.setRole(role);
        return users.save(user);
    }

    private void validateCredentials(String username, String password) {
        if (username == null || !username.trim().matches("[A-Za-z0-9_]{3,24}"))
            throw new IllegalArgumentException("用户名需为 3～24 位字母、数字或下划线");
        if (password == null || password.length() < 8 || password.length() > 72)
            throw new IllegalArgumentException("密码长度需为 8～72 位");
    }

    public UserAccount resetAdminPassword(String username, String password) {
        if (password == null || password.length() < 12 || password.length() > 72) {
            throw new IllegalArgumentException("管理员密码长度需为 12～72 位");
        }
        UserAccount user = requireByUsername(username);
        if (!"ADMIN".equals(user.getRole())) throw new IllegalArgumentException("指定账号不是管理员");
        user.setPasswordHash(encoder.encode(password));
        return users.save(user);
    }

    public UserAccount requireByUsername(String username) {
        return users.findByUsername(username.toLowerCase(Locale.ROOT)).orElseThrow(() -> new NoSuchElementException("用户不存在"));
    }
    public List<UserAccount> all() { return users.findAll(); }
    public UserAccount setEnabled(Long id, boolean enabled) {
        return update(id, enabled, null);
    }
    public UserAccount update(Long id, Boolean enabled, Integer dailyGenerationLimit) {
        UserAccount user = users.findById(id).orElseThrow(() -> new NoSuchElementException("用户不存在"));
        if (enabled != null) {
            if ("ADMIN".equals(user.getRole()) && !enabled) throw new IllegalArgumentException("不能禁用管理员账号");
            user.setEnabled(enabled);
        }
        if (dailyGenerationLimit != null) {
            if (dailyGenerationLimit < 1 || dailyGenerationLimit > 10000) throw new IllegalArgumentException("每日生成次数限制必须在 1 到 10000 之间");
            user.setDailyGenerationLimit(dailyGenerationLimit);
        }
        return users.save(user);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount user = users.findByUsername(username.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("用户名或密码错误"));
        return User.withUsername(user.getUsername()).password(user.getPasswordHash())
                .roles(user.getRole()).disabled(!user.isEnabled()).build();
    }
}
