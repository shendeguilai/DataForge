package cn.datacraft.quiz;

public final class QuizRoomEvent {
    private final String roomCode;
    private final boolean closed;

    private QuizRoomEvent(String roomCode, boolean closed) {
        this.roomCode = roomCode;
        this.closed = closed;
    }

    public static QuizRoomEvent changed(String roomCode) { return new QuizRoomEvent(roomCode, false); }
    public static QuizRoomEvent closed(String roomCode) { return new QuizRoomEvent(roomCode, true); }
    public String getRoomCode() { return roomCode; }
    public boolean isClosed() { return closed; }
}
