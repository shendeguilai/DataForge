package cn.datacraft.config;

import cn.datacraft.user.UserService;
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

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/", "/index.html", "/algorithms.html", "/hanoi.html", "/fenwick.html", "/tools.html",
                        "/atcoder.html", "/typing-pk.html", "/quiz-join.html",
                        "/styles.css", "/portal.css", "/hanoi.css", "/fenwick.css", "/atcoder.css", "/typing-pk.css", "/quiz.css",
                        "/ui-fixes.css", "/auth.css",
                        "/app.js", "/portal.js", "/hanoi.js", "/fenwick.js", "/atcoder.js", "/typing-pk.js", "/quiz-common.js", "/quiz-join.js",
                        "/quiz-cards/**", "/error", "/api/auth/**", "/api/tools/atcoder/**", "/ws/tools/typing", "/ws/tools/quiz",
                        "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tools/typing/rooms", "/api/tools/typing/rooms/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/tools/typing/rooms/*/join", "/api/tools/typing/rooms/*/leave").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tools/quiz/rooms/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/tools/quiz/rooms/*/join", "/api/tools/quiz/rooms/*/leave").permitAll()
                .requestMatchers("/admin.html", "/admin.js", "/admin.css", "/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> response.sendError(401))
                        .accessDeniedHandler((request, response, ex) -> response.sendError(403)));
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
