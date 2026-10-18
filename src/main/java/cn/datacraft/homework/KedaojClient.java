package cn.datacraft.homework;

import cn.datacraft.homework.HomeworkTypes.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class KedaojClient implements KedaojGateway {
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String base;
    private final long intervalMs;
    private final Map<String, Long> lastRequest = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    @Autowired public KedaojClient(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build(), "https://kedaoi.cn", 1000);
    }
    KedaojClient(ObjectMapper mapper, HttpClient http, String base, long intervalMs) {
        this.mapper = mapper; this.http = http; this.base = base; this.intervalMs = intervalMs;
    }
    public static String domain(String value) {
        String result = value == null || value.isBlank() ? "system" : value.trim();
        if (!result.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("题库域格式不正确");
        return result;
    }
    public static String credential(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 16384 || raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0)
            throw new IllegalArgumentException("请输入有效的 SID 或 Cookie");
        String value = raw.trim().replaceFirst("(?i)^cookie\\s*:\\s*", "");
        if (value.matches("(?is).*?(?:^|;)\\s*sid=.*") || value.contains(";") || raw.trim().toLowerCase(Locale.ROOT).startsWith("cookie:")) {
            String found = null;
            for (String part : value.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equalsIgnoreCase("sid")) {
                    if (found != null) throw new IllegalArgumentException("Cookie 中包含重复 SID");
                    found = kv[1];
                }
            }
            if (found == null) throw new IllegalArgumentException("Cookie 中没有找到 sid");
            value = found;
        }
        if (!value.matches("[\\x21-\\x7e]{1,4096}") || value.matches(".*[;\"'\\\\].*")
                || value.toLowerCase(Locale.ROOT).startsWith("bearer"))
            throw new IllegalArgumentException("SID 格式不正确");
        return value;
    }
    private JsonNode get(String owner, String sid, String domain, String path, Map<String, ?> query) {
        String prefix = domain(domain).equals("system") ? "" : "/d/" + domain(domain);
        String params = query.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(String.valueOf(e.getValue())))
                .collect(java.util.stream.Collectors.joining("&"));
        synchronized (locks.computeIfAbsent(owner, k -> new Object())) {
            try {
                long delay = intervalMs - (System.nanoTime() / 1_000_000 - lastRequest.getOrDefault(owner, 0L));
                if (delay > 0) Thread.sleep(delay);
                lastRequest.put(owner, System.nanoTime() / 1_000_000);
                var req = HttpRequest.newBuilder(URI.create(base + prefix + path + "?" + params))
                        .timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                        .header("Authorization", "Bearer " + sid).header("User-Agent", "DataForge-Homework/1.0").GET().build();
                var response = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    int status = response.statusCode();
                    if (status == 401 || status >= 300 && status < 400) throw new KedaojException("AUTH_REQUIRED", "OJ 身份未确认，请验证或更新登录凭据");
                    if (status == 403) throw new KedaojException("PERMISSION_DENIED", "OJ 拒绝访问，当前账号可能没有查看这些记录的权限");
                    if (status == 429) throw new KedaojException("RATE_LIMITED", "OJ 请求受限，请稍后手动更新");
                    if (status == 404) throw new KedaojException("NOT_FOUND", "OJ 资源不存在或当前账号不可见，请核对域和 ID");
                    if (status == 400 || status == 422) throw new KedaojException("REQUEST_REJECTED",
                            "OJ 拒绝了查询参数（HTTP " + status + "），请核对接口参数或联系管理员");
                    if (status != 200) throw new KedaojException("UPSTREAM_ERROR", "OJ 暂时不可用（HTTP " + status + "）");
                    byte[] bytes = in.readNBytes(4 * 1024 * 1024 + 1);
                    if (bytes.length > 4 * 1024 * 1024) throw invalid();
                    JsonNode node = mapper.readTree(bytes);
                    if (node == null || !node.isObject() || node.has("error")) throw invalid();
                    return node;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new KedaojException("INTERRUPTED", "更新已中断，请重新更新");
            } catch (com.fasterxml.jackson.core.JacksonException e) { throw invalid();
            } catch (IOException e) { throw new KedaojException("NETWORK_ERROR", "连接 OJ 失败或超时，请稍后手动更新"); }
        }
    }
    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static KedaojException invalid() { return new KedaojException("INVALID_RESPONSE", "OJ 返回的数据不完整或格式已改变，暂时无法核验"); }
    @Override public Identity identity(String owner, String sid, String domain) {
        var n = get(owner, sid, domain, "/api/user", Map.of("projection", "_id,uname"));
        if (!n.path("_id").isIntegralNumber()) throw invalid();
        if (n.path("_id").asLong() <= 0)
            throw new KedaojException("AUTH_REQUIRED", "OJ 将当前请求识别为游客，请更新登录凭据");
        if (n.path("uname").asText().isBlank()) throw invalid();
        return new Identity(n.get("_id").asLong(), n.path("uname").asText());
    }
    @Override public Identity user(String owner, String sid, String domain, long uid) {
        // Hydro validates API arguments without coercing URL strings into numbers.
        String args = mapper.createObjectNode().put("id", uid).toString();
        var n = get(owner, sid, domain, "/api/user", Map.of("args", args, "projection", "_id,uname"));
        if (!n.path("_id").canConvertToLong() || n.path("_id").asLong() != uid || n.path("uname").asText().isBlank()) throw invalid();
        return new Identity(uid, n.get("uname").asText());
    }
    @Override public Problem problem(String owner, String sid, String domain, String pid) {
        if (pid == null || !pid.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("题号格式不正确");
        var n = get(owner, sid, domain, "/api/problem", Map.of("id", pid, "projection", "domainId,docId,pid,title"));
        if (!n.path("docId").canConvertToLong() || n.path("docId").asLong() <= 0
                || !domain.equals(n.path("domainId").asText()) || n.path("title").asText().isBlank()) throw invalid();
        String actualPid = n.path("pid").asText(String.valueOf(n.path("docId").asLong()));
        if (!(pid.equals(actualPid) || pid.equals(String.valueOf(n.path("docId").asLong())))) throw invalid();
        return new Problem(actualPid, n.get("docId").asLong(), n.get("title").asText());
    }
    @Override public List<Submission> records(String owner, String sid, String domain, long uid, long docId, Runnable heartbeat) {
        return fetchRecords(owner, sid, domain, uid, docId, null, heartbeat).records();
    }
    @Override public RecordFetch recentRecords(String owner, String sid, String domain, long uid, long docId,
                                              String knownRid, Runnable heartbeat) {
        if (knownRid == null || !knownRid.matches("[0-9a-fA-F]{24}")) throw new IllegalArgumentException("增量记录标识无效");
        return fetchRecords(owner, sid, domain, uid, docId, knownRid, heartbeat);
    }
    private RecordFetch fetchRecords(String owner, String sid, String domain, long uid, long docId,
                                     String knownRid, Runnable heartbeat) {
        Map<String, Submission> records = new LinkedHashMap<>();
        String previousLast = null;
        for (int page = 1; page <= 10000; page++) {
            heartbeat.run();
            JsonNode data = get(owner, sid, domain, "/record", Map.of("uidOrName", uid, "pid", docId, "all", true, "page", page));
            if (!data.path("all").asBoolean() || !data.path("rdocs").isArray()
                    || data.path("page").asInt(-1) != page) throw invalid();
            if (data.get("rdocs").isEmpty()) return new RecordFetch(new ArrayList<>(records.values()), true);
            int added = 0;
            String last = null;
            for (JsonNode r : data.get("rdocs")) {
                String rid = r.path("_id").asText().toLowerCase(Locale.ROOT);
                if (!rid.matches("[0-9a-fA-F]{24}") || !domain.equals(r.path("domainId").asText())
                        || !r.path("uid").canConvertToLong() || r.path("uid").asLong() != uid
                        || !r.path("pid").canConvertToLong() || r.path("pid").asLong() != docId
                        || !r.path("status").isIntegralNumber() || !r.path("score").isNumber() || !Double.isFinite(r.path("score").asDouble())
                        || last != null && last.compareToIgnoreCase(rid) < 0) throw invalid();
                last = rid;
                String contest = r.path("contest").asText("");
                if (!contest.isBlank() && !contest.matches("[0-9a-fA-F]{24}")) throw invalid();
                Instant submitted = submissionTime(r);
                if (!records.containsKey(rid)) added++;
                records.put(rid, new Submission(rid, domain, uid, docId, r.get("status").asInt(),
                        r.get("score").asDouble(), submitted, contest));
            }
            if (added == 0 || previousLast != null && last.compareToIgnoreCase(previousLast) >= 0) throw invalid();
            // Include the complete boundary page to refresh recent verdicts without revisiting all history.
            if (knownRid != null && last.compareToIgnoreCase(knownRid) <= 0)
                return new RecordFetch(new ArrayList<>(records.values()), false);
            previousLast = last;
        }
        throw new KedaojException("INCOMPLETE", "提交记录过多，本次查询未完成，请联系管理员核查");
    }
    static Instant submissionTime(JsonNode r) {
        if (r.hasNonNull("submitAt")) {
            try { return Instant.parse(r.get("submitAt").asText()); }
            catch (RuntimeException e) { throw invalid(); }
        }
        try { return Instant.ofEpochSecond(Long.parseUnsignedLong(r.path("_id").asText().substring(0, 8), 16)); }
        catch (RuntimeException e) { throw invalid(); }
    }
}
