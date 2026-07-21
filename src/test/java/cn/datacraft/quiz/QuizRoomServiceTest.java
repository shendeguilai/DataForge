package cn.datacraft.quiz;

import cn.datacraft.quiz.QuizDtos.ConnectionIdentity;
import cn.datacraft.quiz.QuizDtos.JoinResponse;
import cn.datacraft.quiz.QuizDtos.RoomView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Collections;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class QuizRoomServiceTest {
    private QuizRoomService rooms;

    @AfterEach
    void tearDown() {
        if (rooms != null) rooms.shutdown();
    }

    @Test
    void firstServerReceivedBuzzWinsAndAnswerIsNotLeaked() throws Exception {
        rooms = service(0, 30);
        RoomView created = rooms.createRoom("算法课堂", "teacher", Collections.emptyList(),
                Collections.emptyList(), 2, 15);
        rooms.connect(created.roomCode, "teacher", null, "owner-socket");
        JoinResponse one = rooms.join(created.roomCode, "小明", created.inviteCode, "10.0.0.1");
        JoinResponse two = rooms.join(created.roomCode, "小红", created.inviteCode, "10.0.0.1");
        ConnectionIdentity oneIdentity = rooms.connect(created.roomCode, null, one.token, "one-socket");
        ConnectionIdentity twoIdentity = rooms.connect(created.roomCode, null, two.token, "two-socket");

        RoomView ready = rooms.prepareNext(created.roomCode, "teacher");
        assertNotNull(ready.referenceAnswer);
        assertNull(rooms.roomView(created.roomCode, null, one.token).referenceAnswer);
        RoomView guestViewInOwnerBrowser = rooms.roomView(created.roomCode, "teacher", one.token);
        assertEquals(one.memberId, guestViewInOwnerBrowser.selfMemberId);
        assertNull(guestViewInOwnerBrowser.referenceAnswer);
        assertNull(rooms.roomView(created.roomCode, null, one.token).revealedAnswer);
        RoomView open = rooms.openBuzz(created.roomCode, "teacher");
        assertEquals("OPEN", open.state);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Boolean> first = executor.submit(() -> attemptBuzz(start, oneIdentity, open.round.roundId));
        Future<Boolean> second = executor.submit(() -> attemptBuzz(start, twoIdentity, open.round.roundId));
        start.countDown();
        int winners = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
        executor.shutdownNow();
        assertEquals(1, winners);

        RoomView answering = rooms.roomView(created.roomCode, "teacher", null);
        assertEquals("ANSWERING", answering.state);
        assertNotNull(answering.round.winnerId);
        assertNull(rooms.roomView(created.roomCode, null, one.token).revealedAnswer);
    }

    @Test
    void wrongStudentIsExcludedThenCorrectAnswerScoresAndReveals() {
        rooms = service(0, 30);
        RoomView created = rooms.createRoom("重开测试", "teacher", null, null, 2, 15);
        JoinResponse one = rooms.join(created.roomCode, "学生甲", created.inviteCode, "127.0.0.1");
        JoinResponse two = rooms.join(created.roomCode, "学生乙", created.inviteCode, "127.0.0.1");
        ConnectionIdentity first = rooms.connect(created.roomCode, null, one.token, "first");
        ConnectionIdentity second = rooms.connect(created.roomCode, null, two.token, "second");
        rooms.prepareNext(created.roomCode, "teacher");
        RoomView open = rooms.openBuzz(created.roomCode, "teacher");

        assertTrue(rooms.buzz(first, 1, open.round.roundId));
        RoomView reopened = rooms.judge(created.roomCode, "teacher", "WRONG");
        assertEquals("OPEN", reopened.state);
        assertThrows(AccessDeniedException.class, () -> rooms.buzz(first, 2, reopened.round.roundId));
        assertTrue(rooms.buzz(second, 1, reopened.round.roundId));
        RoomView revealed = rooms.judge(created.roomCode, "teacher", "CORRECT");
        assertEquals("REVEALED", revealed.state);
        assertNotNull(rooms.roomView(created.roomCode, null, one.token).revealedAnswer);
        assertEquals(1, revealed.members.stream().filter(member -> member.memberId.equals(two.memberId))
                .findFirst().orElseThrow().score);

        RoomView next = rooms.prepareNext(created.roomCode, "teacher");
        assertEquals("READY", next.state);
        assertTrue(next.members.stream().noneMatch(member -> member.excluded));
    }

    @Test
    void selectedQuestionsRespectFiltersAndDoNotRepeat() {
        rooms = service(0, 30);
        RoomView created = rooms.createRoom("分类测试", "teacher", Collections.singletonList("算法"),
                Collections.singletonList("入门级"), 10, 15);
        assertEquals(10, created.availableQuestions.size());
        assertEquals(10, created.availableQuestions.stream().map(question -> question.id).distinct().count());
        assertTrue(created.availableQuestions.stream().allMatch(question -> "算法".equals(question.category)));
    }

    @Test
    void answeringStudentLeavingReopensForTheOthers() {
        rooms = service(0, 30);
        RoomView created = rooms.createRoom("离开测试", "teacher", null, null, 1, 15);
        JoinResponse one = rooms.join(created.roomCode, "学生甲", created.inviteCode, "127.0.0.1");
        JoinResponse two = rooms.join(created.roomCode, "学生乙", created.inviteCode, "127.0.0.1");
        ConnectionIdentity first = rooms.connect(created.roomCode, null, one.token, "first");
        ConnectionIdentity second = rooms.connect(created.roomCode, null, two.token, "second");
        rooms.prepareNext(created.roomCode, "teacher");
        RoomView open = rooms.openBuzz(created.roomCode, "teacher");

        assertTrue(rooms.buzz(first, 1, open.round.roundId));
        rooms.leave(created.roomCode, one.token);

        RoomView reopened = rooms.roomView(created.roomCode, null, two.token);
        assertEquals("OPEN", reopened.state);
        assertTrue(rooms.buzz(second, 1, reopened.round.roundId));
    }

    private boolean attemptBuzz(CountDownLatch start, ConnectionIdentity identity, String roundId) {
        try {
            start.await(1, TimeUnit.SECONDS);
            return rooms.buzz(identity, 1, roundId);
        } catch (RuntimeException | InterruptedException exception) {
            return false;
        }
    }

    private QuizRoomService service(long countdownSeconds, long buzzSeconds) {
        return new QuizRoomService(new QuizQuestionLibrary(new ObjectMapper()), event -> {},
                countdownSeconds, buzzSeconds, 30, 60);
    }
}
