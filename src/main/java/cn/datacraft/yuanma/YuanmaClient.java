package cn.datacraft.yuanma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class YuanmaClient implements YuanmaGateway {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI identityUri;

    @Autowired public YuanmaClient(ObjectMapper json) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://tea.kedaoi.cn/prod-api/getInfo"));
    }
    YuanmaClient(ObjectMapper json, HttpClient http, URI identityUri) {
        this.json = json; this.http = http; this.identityUri = identityUri;
    }

    public static String credential(String raw) {
        if (raw == null || raw.length() > 8192 || !raw.matches("[A-Za-z0-9._~+/=-]{1,8192}")
                || raw.toLowerCase(Locale.ROOT).startsWith("bearer"))
            throw new IllegalArgumentException("请输入 Admin-Token 的纯值，不要包含 Cookie、Bearer 或空格");
        return raw;
    }

    @Override public Identity identity(String token) {
        credential(token);
        try {
            HttpRequest request = HttpRequest.newBuilder(identityUri).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", "DataForge-Yuanma-Heartbeat/1.0").GET().build();
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> boundedBody());
            try {
                HttpResponse<byte[]> response = pending.get(25, TimeUnit.SECONDS);
                int status = response.statusCode();
                if (status == 401 || status >= 300 && status < 400) throw auth();
                if (status == 403) throw permission();
                if (status == 429) throw limited();
                if (status != 200) throw upstream();
                JsonNode body = json.readTree(response.body());
                if (body == null || !body.isObject() || !body.path("code").isIntegralNumber()) throw invalid();
                int code = body.path("code").asInt();
                if (code == 401) throw auth();
                if (code == 403) throw permission();
                if (code == 429) throw limited();
                if (code != 0 && code != 200 || body.hasNonNull("error") && !body.path("error").isBoolean()
                        || body.path("error").asBoolean(false)
                        || body.path("success").isBoolean() && !body.path("success").asBoolean()
                        || body.path("ok").isBoolean() && !body.path("ok").asBoolean()) throw upstream();
                JsonNode user = body.path("user");
                if (!user.path("userId").isIntegralNumber() || !user.path("userId").canConvertToLong()
                        || user.path("userId").asLong() <= 0) throw invalid();
                return new Identity(user.path("userId").asLong(), name(user.path("userName")), name(user.path("nickName")));
            } catch (TimeoutException e) {
                pending.cancel(true);
                throw new YuanmaException("NETWORK_ERROR", "连接源码系统失败或超时，请稍后重试");
            } catch (ExecutionException e) {
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                if (cause instanceof ResponseTooLarge) throw invalid();
                throw new YuanmaException("NETWORK_ERROR", "连接源码系统失败或超时，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YuanmaException("INTERRUPTED", "源码系统检查已中断，请稍后重试");
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw invalid();
        } catch (IOException e) {
            throw new YuanmaException("NETWORK_ERROR", "连接源码系统失败或超时，请稍后重试");
        }
    }

    private static class ResponseTooLarge extends IOException {}
    private static HttpResponse.BodySubscriber<byte[]> boundedBody() {
        return new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> result = new CompletableFuture<>();
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            private Flow.Subscription subscription;
            @Override public CompletionStage<byte[]> getBody() { return result; }
            @Override public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(1); }
            @Override public void onNext(List<ByteBuffer> buffers) {
                for (ByteBuffer buffer : buffers) {
                    int length = buffer.remaining();
                    if (length > MAX_RESPONSE_BYTES - bytes.size()) {
                        subscription.cancel(); result.completeExceptionally(new ResponseTooLarge()); return;
                    }
                    byte[] chunk = new byte[length]; buffer.get(chunk); bytes.write(chunk, 0, chunk.length);
                }
                subscription.request(1);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        };
    }

    private static String name(JsonNode value) {
        if (!value.isTextual()) return null;
        String result = value.asText().replaceAll("[\\p{Cntrl}]", "").trim();
        return result.length() > 80 ? result.substring(0, 80) : result;
    }
    private static YuanmaException auth() { return new YuanmaException("AUTH_REQUIRED", "源码系统身份验证失败，请重新录入 Token"); }
    private static YuanmaException permission() { return new YuanmaException("PERMISSION_DENIED", "源码系统拒绝访问，当前账号可能没有权限"); }
    private static YuanmaException limited() { return new YuanmaException("RATE_LIMITED", "源码系统请求受限，将延迟重试"); }
    private static YuanmaException invalid() { return new YuanmaException("INVALID_RESPONSE", "源码系统返回格式异常，无法确认身份"); }
    private static YuanmaException upstream() { return new YuanmaException("UPSTREAM_ERROR", "源码系统暂时无法完成身份验证"); }
}
