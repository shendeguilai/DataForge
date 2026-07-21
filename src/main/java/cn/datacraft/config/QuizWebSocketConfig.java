package cn.datacraft.config;

import cn.datacraft.quiz.QuizWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

@Configuration
public class QuizWebSocketConfig implements WebSocketConfigurer {
    private final QuizWebSocketHandler handler;

    public QuizWebSocketConfig(QuizWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/tools/quiz").addInterceptors(new SameOriginHandshakeInterceptor());
    }

    private static final class SameOriginHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String origin = request.getHeaders().getOrigin();
            String hostHeader = request.getHeaders().getFirst(HttpHeaders.HOST);
            if (origin == null || hostHeader == null) return true;
            try {
                URI originUri = URI.create(origin);
                String scheme = request.getURI().getScheme() == null ? "http" : request.getURI().getScheme();
                URI requestHost = URI.create(scheme + "://" + hostHeader);
                return originUri.getHost() != null && requestHost.getHost() != null
                        && originUri.getHost().equalsIgnoreCase(requestHost.getHost())
                        && effectivePort(originUri) == effectivePort(requestHost);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {}

        private static int effectivePort(URI uri) {
            if (uri.getPort() >= 0) return uri.getPort();
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
    }
}
