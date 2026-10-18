package cn.datacraft.homework;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

class KedaojClientTest {
    HttpServer server; KedaojClient client;
    int status = 200;
    Function<String, String> response;
    AtomicInteger calls = new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            calls.incrementAndGet(); byte[] body = response.apply(e.getRequestURI().toString()).getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(status, body.length);
            try (var out = e.getResponseBody()) { out.write(body); }
        });
        server.start(); client = new KedaojClient(new ObjectMapper(), HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort(), 0);
    }
    @AfterEach void stop() { server.stop(0); }
    String row(String rid, long uid, String domain) {
        return "{\"_id\":\"" + rid + "\",\"domainId\":\"" + domain + "\",\"uid\":" + uid + ",\"pid\":32,\"status\":1,\"score\":100,\"time\":999999,\"judgeAt\":\"2030-01-01T00:00:00Z\"}";
    }
    String page(int page, String records) { return "{\"all\":true,\"page\":" + page + ",\"rdocs\":[" + records + "]}"; }
    @Test void paginatesDeduplicatesAndUsesObjectIdTimeInsteadOfRuntimeOrJudgeTime() {
        String newer = row("6ad4457d2b71ec54920761b8", 42, "system"), older = row("68ff00002b71ec54920761b8", 42, "system");
        response = uri -> uri.contains("page=1") ? page(1, newer) : uri.contains("page=2") ? page(2, newer + "," + older) : page(3, "");
        AtomicInteger beats = new AtomicInteger(); var result = client.records("teacher", "dummy", "system", 42, 32, beats::incrementAndGet);
        assertThat(result).hasSize(2); assertThat(calls.get()).isEqualTo(3); assertThat(beats.get()).isEqualTo(3);
        assertThat(result.get(0).submittedAt()).isEqualTo(Instant.parse("2026-10-18T04:05:17Z"));
    }
    @Test void rejectsIgnoredFiltersWrongDomainAndRepeatedPages() {
        response = uri -> page(1, row("6ad4457d2b71ec54920761b8", 43, "system"));
        assertThatThrownBy(() -> client.records("t", "dummy", "system", 42, 32, () -> {})).isInstanceOf(KedaojException.class);
        response = uri -> page(1, row("6ad4457d2b71ec54920761b8", 42, "SummerCamp26"));
        assertThatThrownBy(() -> client.records("t", "dummy", "system", 42, 32, () -> {})).isInstanceOf(KedaojException.class);
        response = uri -> page(uri.contains("page=1") ? 1 : 2, row("6ad4457d2b71ec54920761b8", 42, "system"));
        assertThatThrownBy(() -> client.records("t", "dummy", "system", 42, 32, () -> {})).isInstanceOf(KedaojException.class);
        response = uri -> "{\"all\":false,\"page\":1,\"rdocs\":[]}";
        assertThatThrownBy(() -> client.records("t", "dummy", "system", 42, 32, () -> {})).isInstanceOf(KedaojException.class);
    }
    @Test void classifiesPermissionRateLimitAndNetworkResponsesWithoutEchoingBody() {
        response = uri -> "credential-must-never-appear"; status = 403;
        assertThatThrownBy(() -> client.identity("t", "dummy", "system")).isInstanceOfSatisfying(KedaojException.class, e -> {
            assertThat(e.code()).isEqualTo("PERMISSION_DENIED"); assertThat(e.getMessage()).doesNotContain("credential-must-never-appear");
        });
        status = 429;
        assertThatThrownBy(() -> client.identity("t", "dummy", "system")).isInstanceOfSatisfying(KedaojException.class, e -> assertThat(e.code()).isEqualTo("RATE_LIMITED"));
        status = 200; response = uri -> "{\"_id\":0,\"uname\":\"guest\"}";
        assertThatThrownBy(() -> client.identity("t", "dummy", "system")).isInstanceOfSatisfying(KedaojException.class, e -> assertThat(e.code()).isEqualTo("AUTH_REQUIRED"));
    }
    @Test void resolvesProblemDisplayIdAndDomainExactly() {
        response = uri -> "{\"domainId\":\"system\",\"docId\":32,\"pid\":\"P0032\",\"title\":\"题目\"}";
        assertThat(client.problem("t", "dummy", "system", "P0032").pid()).isEqualTo("P0032");
        assertThatThrownBy(() -> client.problem("t", "dummy", "system", "P0033")).isInstanceOf(KedaojException.class);
    }
    @Test void sendsStudentUidAsJsonNumberRatherThanUrlString() {
        response = uri -> {
            var query = new java.util.HashMap<String, String>();
            for (String part : URI.create(uri).getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            try {
                var args = new ObjectMapper().readTree(query.getOrDefault("args", "{}"));
                if (query.containsKey("id") || !args.path("id").isIntegralNumber() || args.path("id").asLong() != 3847) {
                    status = 400; return "{\"error\":{\"name\":\"BadRequestError\"}}";
                }
            } catch (Exception e) { status = 400; return "{}"; }
            return "{\"_id\":3847,\"uname\":\"聂楷芮\"}";
        };
        var user = client.user("t", "dummy", "system", 3847);
        assertThat(user.uid()).isEqualTo(3847); assertThat(user.uname()).isEqualTo("聂楷芮");
    }
    @Test void reportsRejectedParametersSeparatelyFromUpstreamOutage() {
        status = 400; response = uri -> "upstream body is not exposed";
        assertThatThrownBy(() -> client.user("t", "dummy", "system", 3847))
                .isInstanceOfSatisfying(KedaojException.class, e -> {
                    assertThat(e.code()).isEqualTo("REQUEST_REJECTED");
                    assertThat(e.getMessage()).contains("查询参数", "HTTP 400").doesNotContain("暂时不可用", "upstream body");
                });
    }
    @Test void incrementalQueryStopsAfterValidatedBoundaryPage() {
        String known = "68ff00002b71ec54920761b8";
        response = uri -> page(1, row("6ad4457d2b71ec54920761b8", 42, "system") + "," + row(known, 42, "system"));
        var result = client.recentRecords("t", "dummy", "system", 42, 32, known, () -> {});
        assertThat(result.records()).hasSize(2); assertThat(result.completeHistory()).isFalse(); assertThat(calls.get()).isEqualTo(1);
    }
    @Test void missingIncrementalBoundaryReturnsCompleteCurrentHistoryRatherThanMergingStaleRecords() {
        response = uri -> uri.contains("page=1") ? page(1, row("6ad4457d2b71ec54920761b8", 42, "system")) : page(2, "");
        var result = client.recentRecords("t", "dummy", "system", 42, 32, "68ff00002b71ec54920761b8", () -> {});
        assertThat(result.completeHistory()).isTrue(); assertThat(calls.get()).isEqualTo(2);
    }
}
