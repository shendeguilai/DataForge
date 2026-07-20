package cn.datacraft.typing;

import cn.datacraft.typing.TypingDtos.ConnectionIdentity;
import cn.datacraft.typing.TypingDtos.JoinResponse;
import cn.datacraft.typing.TypingDtos.RoomView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class TypingRoomServiceTest {
    private TypingRoomService rooms;

    @AfterEach
    void tearDown() {
        if (rooms != null) rooms.shutdown();
    }

    @Test
    void ownerAndGuestCanRaceWhileMistakesRemainInAccuracy() throws Exception {
        rooms = service(0, 5);
        RoomView created = rooms.createRoom("算法一班", "teacher");
        ConnectionIdentity owner = rooms.connect(created.roomId, "teacher", null, "owner-connection");
        JoinResponse joined = rooms.join(created.roomId, "小明", created.inviteCode, "127.0.0.1");
        rooms.connect(created.roomId, null, joined.token, "guest-connection");

        rooms.start(created.roomId, "teacher", created.selfMemberId, joined.memberId, "binary-search");
        RoomView running = await(() -> rooms.roomView(created.roomId, "teacher", null), "RUNNING", 1000);
        String article = running.battle.article.content;
        String wrong = article.startsWith("错") ? "误" : "错";

        assertTrue(rooms.submitInput(owner, 1, wrong));
        RoomView mistaken = rooms.roomView(created.roomId, "teacher", null);
        assertEquals(1, mistaken.battle.left.errors);
        assertEquals(0, mistaken.battle.left.correctCount);

        Thread.sleep(15);
        rooms.submitInput(owner, 2, "");
        Thread.sleep(15);
        rooms.submitInput(owner, 3, article.substring(0, 1));
        RoomView corrected = rooms.roomView(created.roomId, "teacher", null);
        assertEquals(1, corrected.battle.left.correctCount);
        assertEquals(50.0, corrected.battle.left.accuracy);

        int sequence = 4;
        int length = 1;
        while (length < article.length()) {
            Thread.sleep(15);
            length = Math.min(article.length(), length + 20);
            rooms.submitInput(owner, sequence++, article.substring(0, length));
        }

        RoomView finished = rooms.roomView(created.roomId, "teacher", null);
        assertEquals("FINISHED", finished.state);
        assertEquals(created.selfMemberId, finished.battle.winnerId);
        assertEquals("COMPLETED", finished.battle.finishReason);
        assertEquals(1, finished.history.size());
    }

    @Test
    void timeoutProducesDrawWhenNeitherPlayerTypes() throws Exception {
        rooms = service(0, 1);
        RoomView created = rooms.createRoom("计时测试", "owner");
        rooms.connect(created.roomId, "owner", null, "owner-connection");
        JoinResponse joined = rooms.join(created.roomId, "guest", created.inviteCode, "127.0.0.2");
        rooms.connect(created.roomId, null, joined.token, "guest-connection");
        rooms.start(created.roomId, "owner", created.selfMemberId, joined.memberId, "testing");

        RoomView finished = await(() -> rooms.roomView(created.roomId, "owner", null), "FINISHED", 2000);
        assertNull(finished.battle.winnerId);
        assertEquals("TIMEOUT", finished.battle.finishReason);
        assertEquals(1, finished.history.size());
    }

    @Test
    void onlyOwnerCanManuallyFinishCountdownOrRunningBattle() {
        rooms = service(3, 300);
        RoomView created = rooms.createRoom("手动结束测试", "owner");
        rooms.connect(created.roomId, "owner", null, "owner-manual-finish");
        JoinResponse joined = rooms.join(created.roomId, "guest", created.inviteCode, "127.0.0.6");
        rooms.connect(created.roomId, null, joined.token, "guest-manual-finish");
        rooms.start(created.roomId, "owner", created.selfMemberId, joined.memberId, "testing");

        assertThrows(AccessDeniedException.class, () -> rooms.manualFinish(created.roomId, "guest"));
        RoomView finished = rooms.manualFinish(created.roomId, "owner");
        assertEquals("FINISHED", finished.state);
        assertEquals("MANUAL", finished.battle.finishReason);
        assertNull(finished.battle.winnerId);
        assertEquals("MANUAL", finished.history.get(0).finishReason);

        RoomView waiting = rooms.reset(created.roomId, "owner");
        assertEquals("WAITING", waiting.state);
        assertNull(waiting.battle);
    }

    @Test
    void invitationAndRoomMembershipRulesAreEnforced() {
        rooms = service(3, 300);
        RoomView created = rooms.createRoom("公开训练", "owner");
        assertThrows(IllegalArgumentException.class,
                () -> rooms.join(created.roomId, "访客", "000000", "127.0.0.3"));
        JoinResponse joined = rooms.join(created.roomId, "访客", created.inviteCode, "127.0.0.3");
        assertThrows(IllegalArgumentException.class,
                () -> rooms.join(created.roomId, "访客", created.inviteCode, "127.0.0.4"));
        assertEquals(joined.memberId, joined.room.selfMemberId);
        assertNull(joined.room.inviteCode);
        assertThrows(IllegalStateException.class, () -> rooms.createRoom("另一个房间", "owner"));
    }

    @Test
    void emojiMistakeCanBeDeletedAndTypingCanContinueImmediately() throws Exception {
        rooms = service(0, 5);
        RoomView created = rooms.createRoom("Unicode 测试", "teacher");
        ConnectionIdentity owner = rooms.connect(created.roomId, "teacher", null, "owner-unicode");
        JoinResponse joined = rooms.join(created.roomId, "guest", created.inviteCode, "127.0.0.5");
        rooms.connect(created.roomId, null, joined.token, "guest-unicode");
        rooms.start(created.roomId, "teacher", created.selfMemberId, joined.memberId, "binary-search");

        RoomView running = await(() -> rooms.roomView(created.roomId, "teacher", null), "RUNNING", 1000);
        String article = running.battle.article.content;

        assertTrue(rooms.submitInput(owner, 1, "🌲"));
        RoomView mistaken = rooms.roomView(created.roomId, "teacher", null);
        assertEquals("🌲", mistaken.battle.left.input);
        assertEquals(1, mistaken.battle.left.errors);
        assertEquals(0, mistaken.battle.left.correctCount);

        assertTrue(rooms.submitInput(owner, 2, ""));
        assertTrue(rooms.submitInput(owner, 3, article.substring(0, 1)));
        RoomView recovered = rooms.roomView(created.roomId, "teacher", null);
        assertEquals(article.substring(0, 1), recovered.battle.left.input);
        assertEquals(1, recovered.battle.left.correctCount);
        assertEquals(50.0, recovered.battle.left.accuracy);

        assertTrue(rooms.submitInput(owner, 4, article.substring(0, 1) + "\uD83C"));
        RoomView malformed = rooms.roomView(created.roomId, "teacher", null);
        assertEquals(article.substring(0, 1) + "\uFFFD", malformed.battle.left.input);
        assertTrue(rooms.submitInput(owner, 5, article.substring(0, 1)));
        assertTrue(rooms.submitInput(owner, 6, article.substring(0, 2)));
        assertEquals(2, rooms.roomView(created.roomId, "teacher", null).battle.left.correctCount);
    }

    @Test
    void bundledArticlesCoverChineseEnglishAndCode() {
        TypingArticleLibrary library = new TypingArticleLibrary();
        assertEquals(18, library.all().size());
        assertEquals(10, library.all().stream().filter(article -> "中文".equals(article.getCategory())).count());
        assertEquals(4, library.all().stream().filter(article -> "英文".equals(article.getCategory())).count());
        assertEquals(4, library.all().stream().filter(article -> "代码".equals(article.getCategory())).count());
        library.all().forEach(article -> {
            assertFalse(article.getTitle().isBlank(), article.getId());
            assertFalse(article.getContent().isBlank(), article.getId());
        });
        assertTrue(library.require("code-prefix-sum").getContent().contains("sum[r] - sum[l - 1]"));
        library.all().stream().filter(article -> "代码".equals(article.getCategory())).forEach(article -> {
            assertFalse(article.getContent().contains("vector<"), article.getId());
            assertFalse(article.getContent().matches("(?s).*\\bauto\\b.*"), article.getId());
            assertFalse(article.getContent().matches("(?s).*for \\([^;]*:.*"), article.getId());
            assertTrue(article.getContent().contains("\n    "), article.getId());
        });
    }

    private TypingRoomService service(long countdownSeconds, long roundSeconds) {
        return new TypingRoomService(new TypingArticleLibrary(), event -> {}, countdownSeconds, roundSeconds, 30);
    }

    private RoomView await(Supplier<RoomView> view, String expectedState, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        RoomView current = view.get();
        while (!expectedState.equals(current.state) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
            current = view.get();
        }
        assertEquals(expectedState, current.state);
        return current;
    }
}
