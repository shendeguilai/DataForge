package cn.datacraft.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import cn.datacraft.user.UserService;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:persistent-login;DB_CLOSE_DELAY=-1",
        "dataforge.admin.password=test-admin-password", "dataforge.crypto-secret=test-encryption-secret",
        "dataforge.homework.scheduling-enabled=false"})
class PersistentLoginIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    final HttpClient client = HttpClient.newHttpClient();
    static final java.nio.file.Path RUNTIME;
    static { try { RUNTIME=java.nio.file.Files.createTempDirectory("session-test-"); } catch(Exception e){throw new ExceptionInInitializerError(e);} }
    @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry r){r.add("dataforge.runtime-dir",RUNTIME::toString);}

    HttpResponse<String> request(String path, String method, String body, String cookie) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) builder.header("Cookie", cookie);
        if(cookie!=null && !method.equals("GET")) {
            var csrf=request("/api/auth/csrf","GET",null,cookie);
            var token=new com.fasterxml.jackson.databind.ObjectMapper().readTree(csrf.body());
            builder.header(token.get("headerName").asText(),token.get("token").asText());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    String login(String username) throws Exception {
        users.register(username, "test-password-123", "443322");
        var response = request("/api/auth/login", "POST", "{\"username\":\"" + username + "\",\"password\":\"test-password-123\"}", null);
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie).contains("DATAFORGE_SESSION=", "Max-Age=604800", "HttpOnly", "SameSite=Lax", "Path=/");
        return cookie.split(";", 2)[0];
    }
    @Test void survivesOriginalIdleTimeoutAndLogoutRevokesStoredSession() throws Exception {
        String cookie = login("idle_session");
        long accessed = System.currentTimeMillis() - Duration.ofMinutes(45).toMillis();
        jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=?, EXPIRY_TIME=? WHERE PRINCIPAL_NAME=?",
                accessed, accessed + Duration.ofDays(7).toMillis(), "idle_session");
        assertThat(request("/api/auth/me", "GET", null, cookie).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Integer.class, "idle_session"))
                .isEqualTo(604800);
        String sessionId=jdbc.queryForObject("SELECT SESSION_ID FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?",String.class,"idle_session");
        var fresh=new org.springframework.session.jdbc.JdbcIndexedSessionRepository(new JdbcTemplate(dataSource),new org.springframework.transaction.support.TransactionTemplate(transactions));
        org.springframework.session.Session restored=fresh.findById(sessionId);
        Object security=restored.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(security).isInstanceOf(org.springframework.security.core.context.SecurityContext.class);
        assertThat(request("/api/auth/logout", "POST", null, cookie).statusCode()).isEqualTo(204);
        assertThat(request("/api/auth/me", "GET", null, cookie).statusCode()).isEqualTo(401);
    }
    @Test void expiredAndDisabledSessionsCannotBeReused() throws Exception {
        String cookie = login("expired_session");
        jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=0, EXPIRY_TIME=0 WHERE PRINCIPAL_NAME=?", "expired_session");
        assertThat(request("/api/auth/me", "GET", null, cookie).statusCode()).isEqualTo(401);
        cookie = login("disabled_session");
        users.setEnabled(users.requireByUsername("disabled_session").getId(), false);
        assertThat(request("/api/auth/me", "GET", null, cookie).statusCode()).isEqualTo(401);
    }
    @Test void cookieAloneCannotMutateWithoutCsrfToken()throws Exception {
        String cookie=login("csrf_session");
        var r=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/logout"))
                .header("Cookie",cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(request("/api/auth/me","GET",null,cookie).statusCode()).isEqualTo(200);
    }
}
