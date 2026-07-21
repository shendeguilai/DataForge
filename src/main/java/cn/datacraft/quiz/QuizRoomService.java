package cn.datacraft.quiz;

import cn.datacraft.quiz.QuizDtos.*;
import cn.datacraft.quiz.QuizQuestionLibrary.Question;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PreDestroy;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class QuizRoomService {
    private static final int MAX_QUESTION_COUNT = 50;
    private static final int IDENTITY_JOIN_FAILURE_LIMIT = 5;
    private static final int NETWORK_JOIN_FAILURE_LIMIT = 100;
    private static final long JOIN_ATTEMPT_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final Pattern DISPLAY_NAME = Pattern.compile("[\\p{IsHan}A-Za-z0-9_.·-]{1,16}");
    private static final String ROOM_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final QuizQuestionLibrary questions;
    private final ApplicationEventPublisher events;
    private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> ownerRooms = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AttemptWindow> joinAttempts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final long countdownMillis;
    private final long defaultBuzzMillis;
    private final long roomExpiryMillis;
    private final int capacity;

    public QuizRoomService(QuizQuestionLibrary questions, ApplicationEventPublisher events,
                           @Value("${dataforge.quiz.countdown-seconds:3}") long countdownSeconds,
                           @Value("${dataforge.quiz.buzz-seconds:15}") long buzzSeconds,
                           @Value("${dataforge.quiz.room-expiry-minutes:30}") long roomExpiryMinutes,
                           @Value("${dataforge.quiz.room-capacity:60}") int capacity) {
        this.questions = questions;
        this.events = events;
        this.countdownMillis = Math.max(0, countdownSeconds) * 1000;
        this.defaultBuzzMillis = Math.max(1, buzzSeconds) * 1000;
        this.roomExpiryMillis = Math.max(1, roomExpiryMinutes) * 60_000;
        this.capacity = Math.max(2, capacity);
        AtomicInteger threadNumber = new AtomicInteger();
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "quiz-room-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::cleanupExpiredRooms, 60, 60, TimeUnit.SECONDS);
    }

    public CatalogView catalog() {
        List<QuestionSummaryView> summaries = questions.all().stream().map(QuizRoomService::summaryView)
                .collect(Collectors.toList());
        return new CatalogView(summaries.size(), questions.categories(), questions.difficulties(), summaries);
    }

    public RoomView createRoom(String rawName, String ownerUsername, Collection<String> categories,
                               Collection<String> difficulties, int questionCount, Integer requestedBuzzSeconds) {
        String name = normalizeRoomName(rawName);
        if (questionCount < 1 || questionCount > MAX_QUESTION_COUNT) {
            throw new IllegalArgumentException("题目数量需要在 1 至 50 之间");
        }
        List<Question> candidates = new ArrayList<>(questions.select(categories, difficulties));
        if (candidates.size() < questionCount) throw new IllegalArgumentException("符合筛选条件的题目数量不足");
        Collections.shuffle(candidates, RANDOM);
        List<String> questionIds = candidates.stream().limit(questionCount).map(question -> question.id)
                .collect(Collectors.toCollection(ArrayList::new));
        int buzzSeconds = requestedBuzzSeconds == null ? (int) (defaultBuzzMillis / 1000)
                : Math.max(5, Math.min(120, requestedBuzzSeconds));

        String ownerKey = ownerUsername.toLowerCase(Locale.ROOT);
        String existing = ownerRooms.get(ownerKey);
        if (existing != null && rooms.containsKey(existing)) {
            throw new IllegalStateException("每个账号只能创建一个活动抢答房间");
        }

        String roomCode = newRoomCode();
        Room room = new Room(roomCode, name, ownerKey, ownerUsername, inviteCode(), questionIds,
                buzzSeconds * 1000L);
        Member owner = new Member(newId(), ownerUsername, true, null);
        room.members.put(owner.id, owner);
        room.ownerMemberId = owner.id;
        rooms.put(roomCode, room);
        String raced = ownerRooms.putIfAbsent(ownerKey, roomCode);
        if (raced != null && rooms.containsKey(raced)) {
            rooms.remove(roomCode);
            throw new IllegalStateException("每个账号只能创建一个活动抢答房间");
        }
        ownerRooms.put(ownerKey, roomCode);
        publishChanged(roomCode);
        return roomView(roomCode, ownerUsername, null);
    }

    public JoinResponse join(String rawRoomCode, String rawDisplayName, String suppliedInviteCode,
                             String remoteAddress) {
        Room room = requireRoom(rawRoomCode);
        String displayName = normalizeDisplayName(rawDisplayName);
        String networkKey = "network|" + room.code + "|" + normalizeRemoteAddress(remoteAddress);
        String identityKey = "identity|" + room.code + "|" + normalizeRemoteAddress(remoteAddress)
                + "|" + displayName.toLowerCase(Locale.ROOT);
        checkJoinRateLimit(identityKey, IDENTITY_JOIN_FAILURE_LIMIT);
        checkJoinRateLimit(networkKey, NETWORK_JOIN_FAILURE_LIMIT);
        String token;
        String memberId;

        synchronized (room) {
            requireOpen(room);
            if (!constantTimeEquals(room.inviteCode, suppliedInviteCode == null ? "" : suppliedInviteCode.trim())) {
                boolean blocked = recordFailedJoin(identityKey, IDENTITY_JOIN_FAILURE_LIMIT);
                blocked |= recordFailedJoin(networkKey, NETWORK_JOIN_FAILURE_LIMIT);
                if (blocked) throw joinRateLimitException();
                throw new IllegalArgumentException("邀请码不正确");
            }
            if (room.members.size() >= capacity) throw new IllegalStateException("房间人数已满");
            boolean duplicate = room.members.values().stream()
                    .anyMatch(member -> member.displayName.equalsIgnoreCase(displayName));
            if (duplicate) throw new IllegalArgumentException("该姓名已在房间中使用");
            token = newToken();
            Member member = new Member(newId(), displayName, false, token);
            room.members.put(member.id, member);
            room.tokens.put(token, member.id);
            room.lastActivity = System.currentTimeMillis();
            memberId = member.id;
        }
        joinAttempts.remove(identityKey);
        publishChanged(room.code);
        return new JoinResponse(memberId, token, roomView(room.code, null, token));
    }

    public RoomView roomView(String roomCode, String principalName, String token) {
        Room room = requireRoom(roomCode);
        synchronized (room) {
            Member self = resolveAccess(room, principalName, token);
            boolean ownerAccess = self.owner && principalName != null
                    && room.ownerUsername.equalsIgnoreCase(principalName);
            return toRoomView(room, self, ownerAccess);
        }
    }

    public RoomView roomView(ConnectionIdentity identity) {
        Room room = requireRoom(identity.roomCode);
        synchronized (room) {
            Member self = room.members.get(identity.memberId);
            if (self == null) throw new AccessDeniedException("房间身份已失效");
            boolean ownerAccess = self.owner && identity.principalName != null
                    && room.ownerUsername.equalsIgnoreCase(identity.principalName);
            return toRoomView(room, self, ownerAccess);
        }
    }

    public ConnectionIdentity connect(String roomCode, String principalName, String token, String connectionId) {
        Room room = requireRoom(roomCode);
        Member member;
        synchronized (room) {
            member = resolveAccess(room, principalName, token);
            member.connections.add(connectionId);
            member.sequences.put(connectionId, -1L);
            member.lastSeen = System.currentTimeMillis();
            room.lastActivity = member.lastSeen;
        }
        publishChanged(room.code);
        return new ConnectionIdentity(room.code, member.id, token, principalName, connectionId);
    }

    public void disconnect(ConnectionIdentity identity) {
        Room room = rooms.get(identity.roomCode);
        if (room == null) return;
        synchronized (room) {
            Member member = room.members.get(identity.memberId);
            if (member == null) return;
            member.connections.remove(identity.connectionId);
            member.sequences.remove(identity.connectionId);
            member.lastSeen = System.currentTimeMillis();
            room.lastActivity = member.lastSeen;
        }
        publishChanged(room.code);
    }

    public void leave(String roomCode, String token) {
        Room room = requireRoom(roomCode);
        String roundId;
        synchronized (room) {
            Member member = resolveAccess(room, null, token);
            if (member.owner) throw new IllegalStateException("房主需要关闭房间");
            roundId = releaseAnsweringMemberLocked(room, member);
            removeMember(room, member);
            room.lastActivity = System.currentTimeMillis();
        }
        publishChanged(room.code);
        if (roundId != null) scheduleBegin(room.code, roundId);
    }

    public RoomView prepareNext(String roomCode, String ownerUsername) {
        Room room = requireOwnedRoom(roomCode, ownerUsername);
        synchronized (room) {
            if (room.round == null) {
                room.cursor = 0;
            } else {
                if (room.round.state != RoundState.REVEALED) {
                    throw new IllegalStateException("请先完成并揭晓当前题目");
                }
                room.cursor++;
            }
            if (room.cursor >= room.questionIds.size()) throw new IllegalStateException("本节课题目已经全部完成");
            room.round = new Round(newId(), room.questionIds.get(room.cursor));
            room.lastActivity = System.currentTimeMillis();
        }
        publishChanged(room.code);
        return roomView(room.code, ownerUsername, null);
    }

    public RoomView chooseCurrent(String roomCode, String ownerUsername, String questionId) {
        Room room = requireOwnedRoom(roomCode, ownerUsername);
        questions.require(questionId);
        synchronized (room) {
            if (room.round != null && room.round.state != RoundState.READY) {
                throw new IllegalStateException("只能在抢答开始前更换题目");
            }
            if (room.round == null) room.cursor = 0;
            int found = -1;
            for (int index = Math.max(0, room.cursor); index < room.questionIds.size(); index++) {
                if (room.questionIds.get(index).equals(questionId)) {
                    found = index;
                    break;
                }
            }
            if (found < 0) throw new IllegalArgumentException("只能选择本节课尚未使用的题目");
            Collections.swap(room.questionIds, room.cursor, found);
            room.round = new Round(newId(), room.questionIds.get(room.cursor));
            room.lastActivity = System.currentTimeMillis();
        }
        publishChanged(room.code);
        return roomView(room.code, ownerUsername, null);
    }

    public RoomView openBuzz(String roomCode, String ownerUsername) {
        Room room = requireOwnedRoom(roomCode, ownerUsername);
        String roundId;
        synchronized (room) {
            Round round = requireRound(room);
            if (round.state != RoundState.READY && round.state != RoundState.TIMED_OUT) {
                throw new IllegalStateException("当前题目不能开始抢答");
            }
            roundId = startCountdownLocked(room, round);
        }
        publishChanged(room.code);
        scheduleBegin(room.code, roundId);
        return roomView(room.code, ownerUsername, null);
    }

    public RoomView judge(String roomCode, String ownerUsername, String result) {
        Room room = requireOwnedRoom(roomCode, ownerUsername);
        String roundId = null;
        synchronized (room) {
            Round round = requireRound(room);
            if (round.state != RoundState.ANSWERING || round.winnerId == null) {
                throw new IllegalStateException("当前没有等待判定的回答");
            }
            Member winner = requireMember(room, round.winnerId);
            String normalized = result == null ? "" : result.trim().toUpperCase(Locale.ROOT);
            if ("CORRECT".equals(normalized)) {
                round.attempts.add(new Attempt(winner.id, "CORRECT", round.winnerBuzzedAt));
                winner.score++;
                round.state = RoundState.REVEALED;
                round.finishedAt = System.currentTimeMillis();
            } else if ("WRONG".equals(normalized)) {
                round.attempts.add(new Attempt(winner.id, "WRONG", round.winnerBuzzedAt));
                round.excludedMemberIds.add(winner.id);
                round.winnerId = null;
                round.winnerBuzzedAt = 0;
                roundId = startCountdownLocked(room, round);
            } else {
                throw new IllegalArgumentException("判定结果只能是 CORRECT 或 WRONG");
            }
            room.lastActivity = System.currentTimeMillis();
        }
        publishChanged(room.code);
        if (roundId != null) scheduleBegin(room.code, roundId);
        return roomView(room.code, ownerUsername, null);
    }

    public RoomView reveal(String roomCode, String ownerUsername) {
        Room room = requireOwnedRoom(roomCode, ownerUsername);
        synchronized (room) {
            Round round = requireRound(room);
            if (round.state == RoundState.REVEALED) return toRoomView(room, requireMember(room, room.ownerMemberId), true);
            if (round.winnerId != null) {
                round.attempts.add(new Attempt(round.winnerId, "SKIPPED", round.winnerBuzzedAt));
            }
            round.state = RoundState.REVEALED;
            round.finishedAt = System.currentTimeMillis();
            room.lastActivity = round.finishedAt;
        }
        publishChanged(room.code);
        return roomView(room.code, ownerUsername, null);
    }

    public RoomView rotateInviteCode(String roomCode, String ownerUsername) {
        Room room = requireOwnedRoom(roomCode, ownerUsername);
        synchronized (room) {
            room.inviteCode = inviteCode();
            room.lastActivity = System.currentTimeMillis();
        }
        publishChanged(room.code);
        return roomView(room.code, ownerUsername, null);
    }

    public RoomView kick(String roomCode, String ownerUsername, String memberId) {
        Room room = requireOwnedRoom(roomCode, ownerUsername);
        String roundId;
        synchronized (room) {
            Member member = requireMember(room, memberId);
            if (member.owner) throw new IllegalArgumentException("不能移除房主");
            roundId = releaseAnsweringMemberLocked(room, member);
            removeMember(room, member);
            room.lastActivity = System.currentTimeMillis();
        }
        publishChanged(room.code);
        if (roundId != null) scheduleBegin(room.code, roundId);
        return roomView(room.code, ownerUsername, null);
    }

    public void close(String roomCode, String ownerUsername) {
        Room room = requireOwnedRoom(roomCode, ownerUsername);
        synchronized (room) { room.closed = true; }
        rooms.remove(room.code, room);
        ownerRooms.remove(room.ownerUsername, room.code);
        events.publishEvent(QuizRoomEvent.closed(room.code));
    }

    public boolean buzz(ConnectionIdentity identity, long sequence, String roundId) {
        Room room = requireRoom(identity.roomCode);
        synchronized (room) {
            Member member = room.members.get(identity.memberId);
            if (member == null) throw new AccessDeniedException("房间身份已失效");
            if (member.owner) throw new AccessDeniedException("房主不能参与抢答");
            Long lastSequence = member.sequences.get(identity.connectionId);
            if (lastSequence == null) throw new AccessDeniedException("连接已失效");
            if (sequence <= lastSequence) return false;
            member.sequences.put(identity.connectionId, sequence);
            Round round = requireRound(room);
            if (!round.id.equals(roundId)) throw new IllegalArgumentException("题目轮次已经变化");
            if (round.state != RoundState.OPEN) throw new IllegalStateException("当前未开放抢答");
            if (round.excludedMemberIds.contains(member.id)) throw new AccessDeniedException("本题已经回答过，不能再次抢答");
            round.winnerId = member.id;
            round.winnerBuzzedAt = System.currentTimeMillis();
            round.state = RoundState.ANSWERING;
            room.lastActivity = round.winnerBuzzedAt;
        }
        publishChanged(room.code);
        return true;
    }

    private void scheduleBegin(String roomCode, String roundId) {
        if (countdownMillis == 0) beginRound(roomCode, roundId);
        else scheduler.schedule(() -> beginRound(roomCode, roundId), countdownMillis, TimeUnit.MILLISECONDS);
    }

    private void beginRound(String roomCode, String roundId) {
        Room room = rooms.get(roomCode);
        if (room == null) return;
        boolean changed = false;
        synchronized (room) {
            Round round = room.round;
            if (round != null && round.id.equals(roundId) && round.state == RoundState.COUNTDOWN) {
                long now = System.currentTimeMillis();
                round.state = RoundState.OPEN;
                round.openedAt = now;
                round.endsAt = now + room.buzzMillis;
                room.lastActivity = now;
                changed = true;
                scheduler.schedule(() -> timeoutRound(roomCode, roundId), room.buzzMillis, TimeUnit.MILLISECONDS);
            }
        }
        if (changed) publishChanged(roomCode);
    }

    private void timeoutRound(String roomCode, String roundId) {
        Room room = rooms.get(roomCode);
        if (room == null) return;
        boolean changed = false;
        synchronized (room) {
            Round round = room.round;
            if (round != null && round.id.equals(roundId) && round.state == RoundState.OPEN) {
                round.state = RoundState.TIMED_OUT;
                room.lastActivity = System.currentTimeMillis();
                changed = true;
            }
        }
        if (changed) publishChanged(roomCode);
    }

    private String startCountdownLocked(Room room, Round round) {
        long now = System.currentTimeMillis();
        round.state = RoundState.COUNTDOWN;
        round.countdownEndsAt = now + countdownMillis;
        round.openedAt = null;
        round.endsAt = null;
        round.winnerId = null;
        round.winnerBuzzedAt = 0;
        room.lastActivity = now;
        return round.id;
    }

    private RoomView toRoomView(Room room, Member self, boolean ownerAccess) {
        Round round = room.round;
        Question question = round == null ? null : questions.require(round.questionId);
        boolean revealed = round != null && round.state == RoundState.REVEALED;
        List<MemberView> members = room.members.values().stream()
                .sorted(Comparator.comparing((Member member) -> !member.owner)
                        .thenComparing(Comparator.comparingInt((Member member) -> member.score).reversed())
                        .thenComparing(member -> member.displayName.toLowerCase(Locale.ROOT)))
                .map(member -> new MemberView(member.id, member.displayName, member.owner, member.isOnline(),
                        member.score, round != null && round.excludedMemberIds.contains(member.id)))
                .collect(Collectors.toList());
        int online = (int) room.members.values().stream().filter(Member::isOnline).count();
        boolean canBuzz = !self.owner && round != null && round.state == RoundState.OPEN
                && !round.excludedMemberIds.contains(self.id);
        List<QuestionSummaryView> available = null;
        if (ownerAccess) {
            int start = Math.max(0, room.cursor);
            available = room.questionIds.subList(start, room.questionIds.size()).stream()
                    .map(questions::require).map(QuizRoomService::summaryView).collect(Collectors.toList());
        }
        return new RoomView(System.currentTimeMillis(), room.code, room.name, room.ownerDisplayName,
                round == null ? "LOBBY" : round.state.name(), ownerAccess, self.id,
                ownerAccess ? room.inviteCode : null, "/quiz-join.html?room=" + room.code,
                online, capacity, (int) (room.buzzMillis / 1000), canBuzz, members,
                question == null ? null : questionView(question), round == null ? null : roundView(room, round),
                ownerAccess && question != null ? answerView(question) : null,
                revealed && question != null ? answerView(question) : null, available);
    }

    private RoundView roundView(Room room, Round round) {
        Member winner = round.winnerId == null ? null : room.members.get(round.winnerId);
        List<AttemptView> attempts = round.attempts.stream().map(attempt -> {
            Member member = room.members.get(attempt.memberId);
            return new AttemptView(attempt.memberId, member == null ? "已离开" : member.displayName,
                    attempt.result, attempt.buzzedAt);
        }).collect(Collectors.toList());
        return new RoundView(round.id, room.cursor + 1, room.questionIds.size(), round.state.name(),
                round.countdownEndsAt, round.openedAt, round.endsAt, round.finishedAt,
                round.winnerId, winner == null ? null : winner.displayName, attempts);
    }

    private static QuestionView questionView(Question question) {
        return new QuestionView(question.id, question.type, question.category, question.difficulty,
                question.promptText, question.imageUrl, question.options);
    }

    private static AnswerView answerView(Question question) {
        return new AnswerView(question.answer, question.explanation, question.example, question.pitfall);
    }

    private static QuestionSummaryView summaryView(Question question) {
        return new QuestionSummaryView(question.id, question.category, question.difficulty, question.promptText);
    }

    private Member resolveAccess(Room room, String principalName, String token) {
        requireOpen(room);
        String memberId = token == null ? null : room.tokens.get(token);
        if (memberId != null) {
            return requireMember(room, memberId);
        }
        if (principalName != null && room.ownerUsername.equalsIgnoreCase(principalName)) {
            return requireMember(room, room.ownerMemberId);
        }
        throw new AccessDeniedException("请先输入房间号、姓名和邀请码加入房间");
    }

    private Room requireOwnedRoom(String roomCode, String ownerUsername) {
        Room room = requireRoom(roomCode);
        if (ownerUsername == null || !room.ownerUsername.equalsIgnoreCase(ownerUsername)) {
            throw new AccessDeniedException("只有房主可以执行此操作");
        }
        return room;
    }

    private Room requireRoom(String rawRoomCode) {
        String roomCode = normalizeRoomCode(rawRoomCode);
        Room room = rooms.get(roomCode);
        if (room == null || room.closed) throw new NoSuchElementException("房间不存在或已关闭");
        return room;
    }

    private static Round requireRound(Room room) {
        if (room.round == null) throw new IllegalStateException("请先准备一道题目");
        return room.round;
    }

    private static Member requireMember(Room room, String memberId) {
        Member member = room.members.get(memberId);
        if (member == null) throw new NoSuchElementException("房间成员不存在");
        return member;
    }

    private static void requireOpen(Room room) {
        if (room.closed) throw new NoSuchElementException("房间已关闭");
    }

    private static void removeMember(Room room, Member member) {
        room.members.remove(member.id);
        if (member.token != null) room.tokens.remove(member.token);
    }

    private String releaseAnsweringMemberLocked(Room room, Member member) {
        Round round = room.round;
        if (round == null || round.state != RoundState.ANSWERING || !member.id.equals(round.winnerId)) {
            return null;
        }
        round.attempts.add(new Attempt(member.id, "WRONG", round.winnerBuzzedAt));
        round.winnerId = null;
        round.winnerBuzzedAt = 0;
        return startCountdownLocked(room, round);
    }

    private void publishChanged(String roomCode) {
        events.publishEvent(QuizRoomEvent.changed(roomCode));
    }

    private void cleanupExpiredRooms() {
        long cutoff = System.currentTimeMillis() - roomExpiryMillis;
        for (Room room : rooms.values()) {
            boolean expired;
            synchronized (room) {
                Member owner = room.members.get(room.ownerMemberId);
                boolean active = room.round != null && (room.round.state == RoundState.COUNTDOWN
                        || room.round.state == RoundState.OPEN || room.round.state == RoundState.ANSWERING);
                expired = !active && (owner == null || !owner.isOnline()) && room.lastActivity < cutoff;
                if (expired) room.closed = true;
            }
            if (expired && rooms.remove(room.code, room)) {
                ownerRooms.remove(room.ownerUsername, room.code);
                events.publishEvent(QuizRoomEvent.closed(room.code));
            }
        }
        long attemptCutoff = System.currentTimeMillis() - JOIN_ATTEMPT_WINDOW_MILLIS;
        joinAttempts.entrySet().removeIf(entry -> entry.getValue().startedAt < attemptCutoff);
    }

    private void checkJoinRateLimit(String key, int limit) {
        AttemptWindow attempt = joinAttempts.get(key);
        if (attempt != null && attempt.blocked(System.currentTimeMillis(), limit)) throw joinRateLimitException();
    }

    private boolean recordFailedJoin(String key, int limit) {
        long now = System.currentTimeMillis();
        AttemptWindow attempt = joinAttempts.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt > JOIN_ATTEMPT_WINDOW_MILLIS) {
                return new AttemptWindow(now, 1);
            }
            return new AttemptWindow(current.startedAt, current.failures + 1);
        });
        return attempt.blocked(now, limit);
    }

    private static ResponseStatusException joinRateLimitException() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "邀请码尝试过于频繁，请稍后再试");
    }

    private String newRoomCode() {
        String code;
        do {
            StringBuilder value = new StringBuilder(6);
            for (int index = 0; index < 6; index++) {
                value.append(ROOM_ALPHABET.charAt(RANDOM.nextInt(ROOM_ALPHABET.length())));
            }
            code = value.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    private static String normalizeRoomName(String value) {
        String name = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 30 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("课堂名称需要 2 至 30 个字符");
        }
        return name;
    }

    private static String normalizeRoomCode(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (code.length() != 6) throw new NoSuchElementException("房间号格式不正确");
        return code;
    }

    private static String normalizeDisplayName(String value) {
        String name = value == null ? "" : value.trim();
        if (!DISPLAY_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("姓名需要 1 至 16 位中文、字母、数字或常用符号");
        }
        return name;
    }

    private static String normalizeRemoteAddress(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String inviteCode() { return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000)); }
    private static String newId() { return UUID.randomUUID().toString().replace("-", ""); }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @PreDestroy
    public void shutdown() { scheduler.shutdownNow(); }

    private enum RoundState { READY, COUNTDOWN, OPEN, ANSWERING, TIMED_OUT, REVEALED }

    private static final class Room {
        final String code;
        final String name;
        final String ownerUsername;
        final String ownerDisplayName;
        final List<String> questionIds;
        final long buzzMillis;
        final Map<String, Member> members = new LinkedHashMap<>();
        final Map<String, String> tokens = new HashMap<>();
        String ownerMemberId;
        String inviteCode;
        int cursor = -1;
        Round round;
        boolean closed;
        long lastActivity = System.currentTimeMillis();

        Room(String code, String name, String ownerUsername, String ownerDisplayName, String inviteCode,
             List<String> questionIds, long buzzMillis) {
            this.code = code;
            this.name = name;
            this.ownerUsername = ownerUsername;
            this.ownerDisplayName = ownerDisplayName;
            this.inviteCode = inviteCode;
            this.questionIds = questionIds;
            this.buzzMillis = buzzMillis;
        }
    }

    private static final class Member {
        final String id;
        final String displayName;
        final boolean owner;
        final String token;
        final Set<String> connections = new HashSet<>();
        final Map<String, Long> sequences = new HashMap<>();
        int score;
        long lastSeen = System.currentTimeMillis();

        Member(String id, String displayName, boolean owner, String token) {
            this.id = id;
            this.displayName = displayName;
            this.owner = owner;
            this.token = token;
        }

        boolean isOnline() { return !connections.isEmpty(); }
    }

    private static final class Round {
        final String id;
        final String questionId;
        final Set<String> excludedMemberIds = new LinkedHashSet<>();
        final List<Attempt> attempts = new ArrayList<>();
        RoundState state = RoundState.READY;
        Long countdownEndsAt;
        Long openedAt;
        Long endsAt;
        Long finishedAt;
        String winnerId;
        long winnerBuzzedAt;

        Round(String id, String questionId) {
            this.id = id;
            this.questionId = questionId;
        }
    }

    private static final class Attempt {
        final String memberId;
        final String result;
        final long buzzedAt;

        Attempt(String memberId, String result, long buzzedAt) {
            this.memberId = memberId;
            this.result = result;
            this.buzzedAt = buzzedAt;
        }
    }

    private static final class AttemptWindow {
        final long startedAt;
        final int failures;

        AttemptWindow(long startedAt, int failures) {
            this.startedAt = startedAt;
            this.failures = failures;
        }

        boolean blocked(long now, int limit) {
            return failures >= limit && now - startedAt <= JOIN_ATTEMPT_WINDOW_MILLIS;
        }
    }
}
