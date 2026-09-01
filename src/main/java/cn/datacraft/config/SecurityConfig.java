package cn.datacraft.config;

import cn.datacraft.user.UserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/", "/index.html", "/algorithms.html", "/hanoi.html", "/fenwick.html", "/tools.html", "/csp-paper-studio.html",
                        "/atcoder.html", "/atcoder-leaderboard.html", "/atcoder-problems.html", "/typing-pk.html", "/quiz-join.html",
                        "/styles.css", "/portal.css", "/hanoi.css", "/fenwick.css", "/atcoder.css", "/atcoder-leaderboard.css", "/atcoder-problems.css", "/typing-pk.css", "/quiz.css",
                        "/auth.css", "/csp-paper-studio.css",
                        "/ui-core.js", "/app.js", "/portal.js", "/hanoi.js", "/fenwick.js", "/atcoder.js", "/atcoder-leaderboard.js", "/atcoder-problems.js", "/typing-pk.js", "/quiz-common.js", "/quiz-join.js", "/quiz-buzzer.js", "/csp-paper-studio.js",
                        "/quiz-cards/**", "/webjars/**", "/error", "/api/tools/atcoder/**", "/ws/tools/typing", "/ws/tools/quiz",
                        "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tools/atcoder-leaderboard").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tools/atcoder-problems", "/api/tools/atcoder-problems/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/tools/atcoder-leaderboard/refresh").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/tools/typing/rooms", "/api/tools/typing/rooms/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/tools/typing/rooms/*/join", "/api/tools/typing/rooms/*/leave").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tools/quiz/rooms/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/tools/quiz/rooms/*/join", "/api/tools/quiz/rooms/*/leave").permitAll()
                .requestMatchers("/admin.html", "/admin.js", "/admin.css", "/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> {
                            if (isBrowserHtmlRequest(request)) {
                                String target = request.getRequestURI();
                                if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
                                    target += "?" + request.getQueryString();
                                }
                                String next = URLEncoder.encode(target, StandardCharsets.UTF_8);
                                response.sendRedirect("/tools.html?auth=login&next=" + next);
                            } else {
                                response.sendError(401);
                            }
                        })
                        .accessDeniedHandler((request, response, ex) -> response.sendError(403)));
        return http.build();
    }

    static boolean isBrowserHtmlRequest(jakarta.servlet.http.HttpServletRequest request) {
        String uri = request.getRequestURI();
        String accept = request.getHeader("Accept");
        if (!"GET".equalsIgnoreCase(request.getMethod()) || uri == null || accept == null || !accept.toLowerCase(java.util.Locale.ROOT).contains("text/html")) {
            return false;
        }
        if (uri.startsWith("/api/") || uri.startsWith("/ws/") || uri.contains("." ) && !uri.endsWith(".html")) {
            return false;
        }
        return uri.endsWith(".html") || "/".equals(uri);
    }

    @Bean
    public AuthenticationManager authenticationManager(UserService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
