package cn.datacraft.web;

import cn.datacraft.user.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService users;
    private final AuthenticationManager authenticationManager;
    public AuthController(UserService users, AuthenticationManager authenticationManager) {
        this.users = users; this.authenticationManager = authenticationManager;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        users.register(request.username, request.password, request.inviteCode);
        return authenticate(request.username, request.password, servletRequest);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return authenticate(request.username, request.password, servletRequest);
    }

    private Map<String, Object> authenticate(String username, String password, HttpServletRequest request) {
        Authentication auth = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return view(users.requireByUsername(auth.getName()));
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) throw new BadCredentialsException("尚未登录");
        return view(users.requireByUsername(auth.getName()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    private Map<String, Object> view(UserAccount user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId()); result.put("username", user.getUsername()); result.put("role", user.getRole());
        return result;
    }
    public static class LoginRequest { @NotBlank public String username; @NotBlank public String password; }
    public static class RegisterRequest extends LoginRequest { @NotBlank public String inviteCode; }
}
