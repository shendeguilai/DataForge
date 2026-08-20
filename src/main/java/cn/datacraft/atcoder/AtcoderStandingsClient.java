package cn.datacraft.atcoder;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.HtmlUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Component
public class AtcoderStandingsClient implements AtcoderStandingsGateway {
    private static final String STANDINGS_URL = "https://atcoder.jp/contests/%s/standings/json";
    private static final String CONTEST_URL = "https://atcoder.jp/contests/%s";
    private static final String SETTINGS_URL = "https://atcoder.jp/settings";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern TITLE = Pattern.compile("(?is)<title>\\s*(.*?)\\s*(?:-\\s*AtCoder)?\\s*</title>");
    private static final Pattern START = Pattern.compile("(?i)startTime\\s*=\\s*moment\\(\"([^\"]+)\"\\)");
    private static final Pattern END = Pattern.compile("(?i)endTime\\s*=\\s*moment\\(\"([^\"]+)\"\\)");

    private final AtcoderStandingsParser parser;
    private final AtcoderCookieStore cookieStore;
    private final HttpClient http;
    private volatile CookieStatus lastCookieStatus;

    @Autowired
    public AtcoderStandingsClient(AtcoderStandingsParser parser, AtcoderCookieStore cookieStore) {
        this(parser, cookieStore, directHttpClient());
    }

    AtcoderStandingsClient(AtcoderStandingsParser parser, AtcoderCookieStore cookieStore, HttpClient http) {
        this.parser = parser;
        this.cookieStore = cookieStore;
        this.lastCookieStatus = CookieStatus.AVAILABLE;
        this.http = http;
    }

    private static HttpClient directHttpClient() {
        ProxySelector direct = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException error) {
                // The request itself reports the useful connection error to the caller.
            }
        };
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(direct)
                .build();
    }

    @Override
    public AtcoderStandings.Snapshot fetchStandings(String contestId) {
        String cookie = requireCookie(cookieStore.current().value());
        HttpResponse<String> response = fetch(STANDINGS_URL.formatted(contestId), "application/json", cookie);
        return parseStandingsResponse(response);
    }

    private AtcoderStandings.Snapshot parseStandingsResponse(HttpResponse<String> response) {
        if (isLoginResponse(response)) {
            lastCookieStatus = CookieStatus.INVALID;
            throw new IllegalStateException("AtCoder Cookie 已失效，请在后台更新后重试");
        }
        if (response.statusCode() == 404) {
            throw new IllegalArgumentException("没有找到该 AtCoder 比赛或榜单尚未开放");
        }
        requireSuccess(response);
        String body = response.body().stripLeading();
        if (body.startsWith("<")) {
            lastCookieStatus = CookieStatus.INVALID;
            throw new IllegalStateException("AtCoder Cookie 已失效，官方榜单返回了登录页面");
        }
        AtcoderStandings.Snapshot snapshot = parser.parse(body);
        lastCookieStatus = CookieStatus.AVAILABLE;
        return snapshot;
    }

    @Override
    public AtcoderStandings.ContestMetadata fetchMetadata(String contestId) {
        try {
            HttpResponse<String> response = fetch(CONTEST_URL.formatted(contestId), "text/html", "");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return fallbackMetadata(contestId);
            }
            String html = response.body();
            String title = match(TITLE, html);
            if (!title.isBlank()) {
                title = HtmlUtils.htmlUnescape(title).replaceFirst("(?i)\\s*-\\s*AtCoder\\s*$", "").trim();
            }
            Instant startAt = parseInstant(match(START, html));
            Instant endAt = parseInstant(match(END, html));
            return new AtcoderStandings.ContestMetadata(
                    title.isBlank() ? contestId.toUpperCase() : title,
                    startAt,
                    endAt
            );
        } catch (RuntimeException ex) {
            return fallbackMetadata(contestId);
        }
    }

    @Override
    public CookieStatus cookieStatus() {
        return cookieStore.current().value().isBlank() ? CookieStatus.MISSING : lastCookieStatus;
    }

    @Override
    public String cookieSource() {
        return cookieStore.current().source();
    }

    @Override
    public Instant cookieUpdatedAt() {
        return cookieStore.current().updatedAt();
    }

    @Override
    public AtcoderStandings.Snapshot updateCookie(String rawCookie, String contestId) {
        String cookie = AtcoderCookieStore.normalizeRequired(rawCookie);
        AtcoderStandings.Snapshot snapshot = null;
        if (contestId != null && !contestId.isBlank()) {
            snapshot = parseStandingsResponse(fetch(
                    STANDINGS_URL.formatted(contestId), "application/json", cookie
            ));
        } else {
            HttpResponse<String> response = fetch(SETTINGS_URL, "text/html", cookie);
            if (isLoginResponse(response)) {
                lastCookieStatus = CookieStatus.INVALID;
                throw new IllegalArgumentException("AtCoder Cookie 无效或已经过期");
            }
            requireSuccess(response);
        }
        cookieStore.save(cookie);
        lastCookieStatus = CookieStatus.AVAILABLE;
        return snapshot;
    }

    @Override
    public void clearManagedCookie() {
        cookieStore.clear();
        lastCookieStatus = cookieStore.current().value().isBlank()
                ? CookieStatus.MISSING : CookieStatus.AVAILABLE;
    }

    private HttpResponse<String> fetch(String url, String accept, String cookie) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", accept)
                .header("Accept-Encoding", "gzip")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("User-Agent", "Mozilla/5.0 (compatible; DataForge/0.1; AtCoder classroom leaderboard)")
                .GET();
        if (cookie != null && !cookie.isBlank()) request.header("Cookie", cookie);
        try {
            return http.send(request.build(), responseInfo -> HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofByteArray(),
                    body -> decodeBody(responseInfo.headers(), body)
            ));
        } catch (IOException ex) {
            throw new IllegalStateException("连接 AtCoder 超时或失败：" + friendlyMessage(ex), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AtCoder 榜单刷新被中断", ex);
        }
    }

    private String requireCookie(String cookie) {
        if (cookie.isBlank()) {
            lastCookieStatus = CookieStatus.MISSING;
            throw new IllegalStateException("AtCoder Cookie 未配置，无法读取官方比赛榜单");
        }
        return cookie;
    }

    private void requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() == 403 && isEdgeBlocked(response)) {
            throw new IllegalStateException("AtCoder 拒绝了当前代理或网络出口，请关闭代理后重试");
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            lastCookieStatus = CookieStatus.INVALID;
            throw new IllegalStateException("AtCoder Cookie 已失效或无权访问官方榜单");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("AtCoder 榜单暂时不可用，状态码：" + response.statusCode());
        }
    }

    private static boolean isLoginResponse(HttpResponse<String> response) {
        String path = response.uri().getPath();
        return path != null && path.contains("/login");
    }

    static boolean isEdgeBlocked(HttpResponse<String> response) {
        if (response.statusCode() != 403) return false;
        String body = response.body() == null ? "" : response.body().toLowerCase();
        return body.contains("request blocked")
                || body.contains("request could not be satisfied")
                || body.contains("generated by cloudfront");
    }

    static String decodeBody(HttpHeaders headers, byte[] body) {
        boolean gzip = headers.firstValue("Content-Encoding")
                .map(value -> value.toLowerCase().contains("gzip"))
                .orElse(false);
        if (!gzip) return new String(body, StandardCharsets.UTF_8);
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("AtCoder 响应解压失败", ex);
        }
    }

    private static String match(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source == null ? "" : source);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (RuntimeException ignored) { return null; }
    }

    private static AtcoderStandings.ContestMetadata fallbackMetadata(String contestId) {
        return new AtcoderStandings.ContestMetadata(contestId.toUpperCase(), null, null);
    }

    private static String friendlyMessage(IOException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "网络连接失败" : message;
    }
}
