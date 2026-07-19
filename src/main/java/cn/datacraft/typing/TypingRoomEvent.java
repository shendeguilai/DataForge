package cn.datacraft.typing;

public final class TypingRoomEvent {
    private final String roomId;
    private final boolean closed;

    private TypingRoomEvent(String roomId, boolean closed) {
        this.roomId = roomId;
        this.closed = closed;
    }

    public static TypingRoomEvent changed(String roomId) {
        return new TypingRoomEvent(roomId, false);
    }

    public static TypingRoomEvent closed(String roomId) {
        return new TypingRoomEvent(roomId, true);
    }

    public String getRoomId() { return roomId; }
    public boolean isClosed() { return closed; }
}
