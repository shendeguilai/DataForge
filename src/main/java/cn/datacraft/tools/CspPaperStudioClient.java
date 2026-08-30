package cn.datacraft.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Service
public class CspPaperStudioClient {
    private static final int MAX_JSON_RESPONSE_BYTES = 25 * 1024 * 1024;

    private final CspPaperStudioProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CspPaperStudioClient(CspPaperStudioProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public JsonNode sample(String name) {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/sample/" + name))
                .timeout(properties.getAnalyzeTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        return sendJson(request);
    }

    public JsonNode analyze(String markdown) {
        return sendJson(jsonRequest(
                "/api/analyze",
                Map.of("markdown", markdown),
                properties.getAnalyzeTimeout()
        ));
    }

    public ExportedWord export(String markdown, String filename, String numbering) {
        HttpRequest request = jsonRequest(
                "/api/export",
                Map.of("markdown", markdown, "filename", filename, "numbering", numbering),
                properties.getExportTimeout()
        );
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw upstreamError(response);
        }
        if (response.body().length > properties.getMaxWordBytes()) {
            throw new CspPaperStudioException(
                    HttpStatus.BAD_GATEWAY,
                    "CSP Paper Studio 返回的 Word 文件超过 25 MiB"
            );
        }
        return new ExportedWord(response.body(), sanitizeStem(filename) + ".docx");
    }

    private HttpRequest jsonRequest(String path, Object payload, Duration timeout) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            return HttpRequest.newBuilder(uri(path))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (IOException ex) {
            throw new CspPaperStudioException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "无法准备 CSP Paper Studio 请求",
                    ex
            );
        }
    }

    private JsonNode sendJson(HttpRequest request) {
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw upstreamError(response);
        }
        if (response.body().length > MAX_JSON_RESPONSE_BYTES) {
            throw new CspPaperStudioException(HttpStatus.BAD_GATEWAY, "CSP Paper Studio 返回内容过大");
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException ex) {
            throw new CspPaperStudioException(
                    HttpStatus.BAD_GATEWAY,
                    "CSP Paper Studio 返回了无效内容",
                    ex
            );
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException ex) {
            throw unavailable(ex);
        } catch (HttpTimeoutException ex) {
            throw new CspPaperStudioException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "CSP Paper Studio 处理超时，请稍后重试",
                    ex
            );
        } catch (ConnectException ex) {
            throw unavailable(ex);
        } catch (IOException ex) {
            throw unavailable(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CspPaperStudioException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CSP Paper Studio 请求已中断，请稍后重试",
                    ex
            );
        }
    }

    private CspPaperStudioException unavailable(Exception cause) {
        return new CspPaperStudioException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CSP Paper Studio 暂时不可用，请稍后重试",
                cause
        );
    }

    private CspPaperStudioException upstreamError(HttpResponse<byte[]> response) {
        int code = response.statusCode();
        HttpStatus status = switch (code) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 404 -> HttpStatus.NOT_FOUND;
            case 413 -> HttpStatus.PAYLOAD_TOO_LARGE;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> code >= 500 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        };
        String message = "CSP Paper Studio 请求失败";
        try {
            JsonNode body = objectMapper.readTree(response.body());
            if (body != null && body.path("error").isTextual()) {
                message = body.path("error").asText();
            }
        } catch (IOException ignored) {
            // 上游异常体不是 JSON 时，不把内部内容回显给用户。
        }
        return new CspPaperStudioException(status, message);
    }

    private URI uri(String path) {
        String base = properties.getBaseUrl().toString().replaceAll("/+$", "");
        return URI.create(base + path);
    }

    public static String sanitizeStem(String filename) {
        String value = filename == null ? "" : filename;
        value = value.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).trim();
        value = value.replaceFirst("(?i)\\.md$", "");
        value = value.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]+", "_").replaceAll("^[ .]+|[ .]+$", "");
        return value.isBlank() ? "CSP试卷" : value;
    }

    public record ExportedWord(byte[] content, String filename) {}
}
