package cn.datacraft.atcoder;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Component
class AtcoderProblemSourceClient implements AtcoderProblemSourceGateway {
    private static final String TASK_URL = "https://atcoder.jp/contests/%s/tasks/%s?lang=en";
    private static final String CONTEST_URL = "https://atcoder.jp/contests/%s?lang=en";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final AtcoderCookieStore cookieStore;
    private final HttpClient http;

    @Autowired
    AtcoderProblemSourceClient(AtcoderCookieStore cookieStore) {
        this(cookieStore, directHttpClient());
    }

    AtcoderProblemSourceClient(AtcoderCookieStore cookieStore, HttpClient http) {
        this.cookieStore = cookieStore;
        this.http = http;
    }

    @Override
    public String fetchTaskPage(String contestId, String taskId) {
        String cookie = cookieStore.current().value();
        if (cookie.isBlank()) throw new IllegalStateException("AtCoder Cookie 未配置，无法读取赛题");

        HttpResponse<String> response = fetch(TASK_URL.formatted(contestId, taskId), cookie);
        String path = response.uri().getPath();
        if (path != null && path.contains("/login")) {
            throw new IllegalStateException("AtCoder Cookie 已失效，请在后台更新后重试");
        }
        if (response.statusCode() == 404) throw diagnoseTaskNotFound(contestId, cookie);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalStateException("AtCoder Cookie 已失效或无权读取赛题");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("AtCoder 题面暂时不可用，状态码：" + response.statusCode());
        }
        return response.body();
    }

    private HttpResponse<String> fetch(String url, String cookie) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/html")
                .header("Accept-Encoding", "gzip")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", "Mozilla/5.0 (compatible; DataForge/0.1; AtCoder classroom translator)")
                .header("Cookie", cookie)
                .GET()
                .build();
        try {
            return http.send(request, responseInfo -> HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofByteArray(),
                    body -> decodeBody(responseInfo.headers(), body)
            ));
        } catch (IOException ex) {
            throw new IllegalStateException("连接 AtCoder 超时或失败：" + friendlyMessage(ex), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AtCoder 题面获取被中断", ex);
        }
    }

    private RuntimeException diagnoseTaskNotFound(String contestId, String cookie) {
        try {
            HttpResponse<String> contest = fetch(CONTEST_URL.formatted(contestId), cookie);
            String path = contest.uri().getPath();
            if ((path != null && path.contains("/login")) || isSignedOutPage(contest.body())) {
                return new IllegalStateException("AtCoder Cookie 已失效，请在后台更新后重试");
            }
            if (contest.statusCode() >= 200 && contest.statusCode() < 300
                    && hasRegistrationLink(contest.body(), contestId)) {
                return new IllegalStateException("AtCoder Cookie 对应账号尚未报名当前比赛；请先用该账号打开 AtCoder 比赛页并点击 Register，然后重试翻译");
            }
        } catch (RuntimeException ignored) {
            // Keep the original task response as the primary diagnostic.
        }
        return new IllegalArgumentException("AtCoder 题目不存在或尚未开放；若比赛正在进行，请确认 Cookie 对应账号已报名该比赛");
    }

    static boolean isSignedOutPage(String html) {
        String body = html == null ? "" : html.toLowerCase();
        return body.contains("href=\"/login?") || body.contains("href='/login?");
    }

    static boolean hasRegistrationLink(String html, String contestId) {
        String body = html == null ? "" : html.toLowerCase();
        String path = "/contests/" + contestId.toLowerCase() + "/register";
        return body.contains("href=\"" + path + "\"") || body.contains("href='" + path + "'");
    }

    private static HttpClient directHttpClient() {
        ProxySelector direct = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException error) {
                // The caller reports the request error.
            }
        };
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(direct)
                .build();
    }

    static String decodeBody(HttpHeaders headers, byte[] body) {
        boolean gzip = headers.firstValue("Content-Encoding")
                .map(value -> value.toLowerCase().contains("gzip"))
                .orElse(false);
        if (!gzip) return new String(body, StandardCharsets.UTF_8);
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("AtCoder 题面响应解压失败", ex);
        }
    }

    private static String friendlyMessage(IOException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "网络连接失败" : ex.getMessage();
    }
}
