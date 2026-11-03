package cn.datacraft.yuanma;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class YuanmaClientTest {
    HttpServer server;
    YuanmaClient client;
    int status;
    String response, auth;
    AtomicInteger calls;

    @BeforeEach void start() throws Exception {
        status = 200; response = "{\"code\":200,\"user\":{\"userId\":42,\"userName\":\"teacher\",\"nickName\":\"老师\"}}";
        calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (status == 302) exchange.getResponseHeaders().set("Location", "/redirect-target");
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        client = new YuanmaClient(new ObjectMapper(), HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/prod-api/getInfo"));
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void sendsTokenOnlyInAuthorizationAndReturnsMinimalIdentity() {
        var result = client.identity("offline-test-token");
        assertThat(result.userId()).isEqualTo(42); assertThat(result.nickName()).isEqualTo("老师");
        assertThat(auth).isEqualTo("Bearer offline-test-token"); assertThat(calls.get()).isEqualTo(1);
    }
    @Test void validatesCredentialBeforeAnyRequest() {
        for (String bad : new String[]{"", "Bearer secret", "Admin-Token=secret; sid=x", "abc\r\nX: x", "带中文"}) {
            assertThatThrownBy(() -> client.identity(bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("请输入 Admin-Token 的纯值，不要包含 Cookie、Bearer 或空格");
        }
        assertThat(calls.get()).isZero();
    }
    @Test void handlesBusiness401InHttp200AndNeverLeaksResponseOrFollowsRedirect() {
        response = "{\"code\":401,\"msg\":\"offline-test-token\"}";
        assertThatThrownBy(() -> client.identity("offline-test-token")).isInstanceOfSatisfying(YuanmaException.class, e -> {
            assertThat(e.code()).isEqualTo("AUTH_REQUIRED"); assertThat(e.getMessage()).doesNotContain("offline-test-token");
        });
        status = 302; response = "";
        assertThatThrownBy(() -> client.identity("offline-test-token")).isInstanceOfSatisfying(YuanmaException.class,
                e -> assertThat(e.code()).isEqualTo("AUTH_REQUIRED"));
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void rejectsGuestMalformedAndPermissionAndLimit() {
        for (String body : new String[]{"{}", "{\"code\":200,\"user\":{\"userId\":0}}", "{\"code\":200,\"user\":{\"userId\":true}}", "not-json"}) {
            response = body;
            assertThatThrownBy(() -> client.identity("dummy")).isInstanceOfSatisfying(YuanmaException.class,
                    e -> assertThat(e.code()).isEqualTo("INVALID_RESPONSE"));
        }
        response = "{\"code\":403}";
        assertThatThrownBy(() -> client.identity("dummy")).isInstanceOfSatisfying(YuanmaException.class, e -> assertThat(e.code()).isEqualTo("PERMISSION_DENIED"));
        status = 429;
        assertThatThrownBy(() -> client.identity("dummy")).isInstanceOfSatisfying(YuanmaException.class, e -> assertThat(e.code()).isEqualTo("RATE_LIMITED"));
    }
    @Test void capsResponsesAndAcceptsExplicitFalseError() {
        response = "{\"code\":200,\"error\":false,\"user\":{\"userId\":42}}";
        assertThat(client.identity("dummy").userId()).isEqualTo(42);
        response = " ".repeat(1024 * 1024 + 1);
        assertThatThrownBy(() -> client.identity("dummy")).isInstanceOfSatisfying(YuanmaException.class,
                e -> assertThat(e.code()).isEqualTo("INVALID_RESPONSE"));
    }
}
