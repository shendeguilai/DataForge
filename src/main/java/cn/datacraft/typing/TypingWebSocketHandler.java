package cn.datacraft.typing;

import cn.datacraft.typing.TypingDtos.ConnectionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class TypingWebSocketHandler extends TextWebSocketHandler {
    private static final int MAX_MESSAGE_BYTES = 4096;
    private final TypingRoomService rooms;
    private final ObjectMapper mapper;
    private final ConcurrentMap<String, ClientSession> clients = new ConcurrentHashMap<>();

    public TypingWebSocketHandler(TypingRoomService rooms, ObjectMapper mapper) {
        this.rooms = rooms;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.setTextMessageSizeLimit(MAX_MESSAGE_BYTES);
        String roomId = queryParameter(session, "roomId");
        String token = queryParameter(session, "token");
        Principal principal = session.getPrincipal();
        String principalName = principal == null ? null : principal.getName();
        if (roomId == null || roomId.isBlank()) {
            session.close(CloseStatus.BAD_DATA.withReason("缺少房间编号"));
            return;
        }

        try {
            ConnectionIdentity identity = rooms.connect(roomId, principalName, token, session.getId());
            ConcurrentWebSocketSessionDecorator safeSession =
                    new ConcurrentWebSocketSessionDecorator(session, 5_000, 64 * 1024);
            ClientSession client = new ClientSession(safeSession, identity);
            clients.put(session.getId(), client);
            sendSnapshot(client);
        } catch (RuntimeException exception) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(shortReason(exception)));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ClientSession client = clients.get(session.getId());
        if (client == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            ClientMessage input = mapper.readValue(message.getPayload(), ClientMessage.class);
            if ("ping".equals(input.type)) {
                send(client, "pong", Collections.singletonMap("serverTime", System.currentTimeMillis()));
                return;
            }
            if (!"typing.input".equals(input.type)) throw new IllegalArgumentException("不支持的消息类型");
            rooms.submitInput(client.identity, input.sequence, input.text);
        } catch (RuntimeException exception) {
            send(client, "room.error", Collections.singletonMap("message", shortReason(exception)));
            try {
                sendSnapshot(client);
            } catch (RuntimeException ignored) {
                // A revoked or closed room will be handled by the next room event.
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ClientSession client = clients.remove(session.getId());
        if (client != null) rooms.disconnect(client.identity);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    @EventListener
    public void onRoomEvent(TypingRoomEvent event) {
        clients.values().stream()
                .filter(client -> client.identity.roomId.equals(event.getRoomId()))
                .forEach(client -> {
                    if (event.isClosed()) {
                        sendAndClose(client, "room.closed", Collections.singletonMap("message", "房间已关闭"));
                    } else {
                        try {
                            sendSnapshot(client);
                        } catch (AccessDeniedException | NoSuchElementException exception) {
                            sendAndClose(client, "room.revoked", Collections.singletonMap("message", shortReason(exception)));
                        }
                    }
                });
    }

    private void sendSnapshot(ClientSession client) {
        send(client, "room.snapshot", rooms.roomView(client.identity));
    }

    private void send(ClientSession client, String type, Object payload) {
        if (!client.session.isOpen()) return;
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("payload", payload);
        try {
            client.session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
        } catch (IOException exception) {
            try {
                client.session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
                // Connection cleanup is completed by afterConnectionClosed.
            }
        }
    }

    private void sendAndClose(ClientSession client, String type, Object payload) {
        send(client, type, payload);
        try {
            client.session.close(CloseStatus.NORMAL);
        } catch (IOException ignored) {
            // The client may already have disconnected.
        }
    }

    private static String queryParameter(WebSocketSession session, String name) {
        if (session.getUri() == null) return null;
        return UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst(name);
    }

    private static String shortReason(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) return "操作失败";
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    private static final class ClientSession {
        final ConcurrentWebSocketSessionDecorator session;
        final ConnectionIdentity identity;

        ClientSession(ConcurrentWebSocketSessionDecorator session, ConnectionIdentity identity) {
            this.session = session;
            this.identity = identity;
        }
    }

    public static final class ClientMessage {
        public String type;
        public long sequence;
        public String text;
    }
}
