package cn.datacraft.config;

import cn.datacraft.user.UserService;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
        http.csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED).and()
                .authorizeRequests()
                .antMatchers("/", "/index.html", "/algorithms.html", "/hanoi.html", "/fenwick.html", "/tools.html",
                        "/atcoder.html",
                        "/styles.css", "/portal.css", "/hanoi.css", "/fenwick.css", "/atcoder.css", "/ui-fixes.css", "/auth.css",
                        "/app.js", "/portal.js", "/hanoi.js", "/fenwick.js", "/atcoder.js",
                        "/error", "/api/auth/**", "/api/tools/atcoder/**").permitAll()
                .antMatchers("/admin.html", "/admin.js", "/admin.css", "/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
                .and().exceptionHandling()
                .authenticationEntryPoint((request, response, ex) -> response.sendError(401))
                .accessDeniedHandler((request, response, ex) -> response.sendError(403));
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http, UserService users, PasswordEncoder encoder) throws Exception {
        return http.getSharedObject(AuthenticationManagerBuilder.class)
                .userDetailsService(users).passwordEncoder(encoder).and().build();
    }
}
