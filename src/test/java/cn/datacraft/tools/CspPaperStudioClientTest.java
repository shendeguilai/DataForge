package cn.datacraft.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CspPaperStudioClientTest {
    private HttpServer server;
    private CspPaperStudioProperties properties;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new CspPaperStudioProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setConnectTimeout(Duration.ofMillis(250));
        properties.setAnalyzeTimeout(Duration.ofSeconds(1));
        properties.setExportTimeout(Duration.ofSeconds(1));
        properties.setMaxWordBytes(25 * 1024 * 1024);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void forwardsJsonAndPreservesParserErrors() {
        server.createContext("/api/analyze", exchange -> json(exchange, 422, "{\"error\":\"第 16 题没有 cpp 代码块。\"}"));
        server.start();
        CspPaperStudioClient client = new CspPaperStudioClient(properties, new ObjectMapper());

        CspPaperStudioException error = assertThrows(
                CspPaperStudioException.class,
                () -> client.analyze("# sample")
        );
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
        assertEquals("第 16 题没有 cpp 代码块。", error.getMessage());
    }

    @Test
    void mapsTimeoutToGatewayTimeout() {
        properties.setAnalyzeTimeout(Duration.ofMillis(30));
        server.createContext("/api/analyze", exchange -> {
            try {
                Thread.sleep(150);
                json(exchange, 200, "{}");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        CspPaperStudioClient client = new CspPaperStudioClient(properties, new ObjectMapper());

        CspPaperStudioException error = assertThrows(
                CspPaperStudioException.class,
                () -> client.analyze("# sample")
        );
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, error.getStatus());
    }

    @Test
    void mapsConnectionFailureToServiceUnavailable() {
        server.stop(0);
        server = null;
        properties.setBaseUrl(URI.create("http://127.0.0.1:1"));
        CspPaperStudioClient client = new CspPaperStudioClient(properties, new ObjectMapper());

        CspPaperStudioException error = assertThrows(
                CspPaperStudioException.class,
                () -> client.analyze("# sample")
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
