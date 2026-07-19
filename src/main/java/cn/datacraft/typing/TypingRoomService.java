package cn.datacraft.typing;

import cn.datacraft.typing.TypingArticleLibrary.Article;
import cn.datacraft.typing.TypingDtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PreDestroy;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TypingRoomService {
    public static final int ROOM_CAPACITY = 30;
    private static final int HISTORY_LIMIT = 5;
    private static final int MAX_INPUT_DELTA = 24;
    private static final Pattern DISPLAY_NAME = Pattern.compile("[\\p{IsHan}A-Za-z0-9_.·-]{1,16}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TypingArticleLibrary articles;
    private final ApplicationEventPublisher events;
    private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> ownerRooms = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AttemptWindow> joinAttempts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final long countdownMillis;
    private final long roundMillis;
    private final long roomExpiryMillis;

    public TypingRoomService(TypingArticleLibrary articles, ApplicationEventPublisher events,
                             @Value("${dataforge.typing.countdown-seconds:3}") long countdownSeconds,
                             @Value("${dataforge.typing.round-seconds:300}") long roundSeconds,
                             @Value("${dataforge.typing.room-expiry-minutes:30}") long roomExpiryMinutes) {
        this.articles = articles;
        this.events = events;
        this.countdownMillis = Math.max(0, countdownSeconds) * 1000;
        this.roundMillis = Math.max(1, roundSeconds) * 1000;
        this.roomExpiryMillis = Math.max(1, roomExpiryMinutes) * 60_000;
        AtomicInteger threadNumber = new AtomicInteger();
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "typing-pk-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::cleanupExpiredRooms, 60, 60, TimeUnit.SECONDS);
    }

    public List<PublicRoomView> listRooms() {
        return rooms.values().stream()
                .filter(room -> room.state != RoomState.CLOSED)
                .sorted(Comparator.comparingInt((Room room) -> stateOrder(room.state))
                        .thenComparing(room -> room.name.toLowerCase(Locale.ROOT)))
                .map(this::toPublicView)
                .collect(Collectors.toList());
    }

    public RoomView createRoom(String rawName, String ownerUsername) {
        String name = normalizeRoomName(rawName);
        String ownerKey = ownerUsername.toLowerCase(Locale.ROOT);
        String existingId = ownerRooms.get(ownerKey);
        if (existingId != null && rooms.containsKey(existingId)) {
            throw new IllegalStateException("每个账号只能创建一个活动房间");
        }

        String roomId = newRoomId();
        Room room = new Room(roomId, name, ownerKey, inviteCode());
        Member owner = new Member(newId(), ownerUsername, true, null);
        room.members.put(owner.id, owner);
        room.ownerMemberId = owner.id;
        rooms.put(roomId, room);
        String raced = ownerRooms.putIfAbsent(ownerKey, roomId);
        if (raced != null && rooms.containsKey(raced)) {
            rooms.remove(roomId);
            throw new IllegalStateException("每个账号只能创建一个活动房间");
        }
        ownerRooms.put(ownerKey, roomId);
        publishChanged(roomId);
        return roomView(roomId, ownerUsername, null);
    }

    public JoinResponse join(String roomId, String rawDisplayName, String providedInviteCode, String remoteAddress) {
        Room room = requireRoom(roomId);
        String displayName = normalizeDisplayName(rawDisplayName);
        String attemptKey = roomId + "|" + String.valueOf(remoteAddress);
        checkJoinRateLimit(attemptKey);
        String token;
        String memberId;

        synchronized (room) {
            requireOpen(room);
            if (!constantTimeEquals(room.inviteCode, providedInviteCode == null ? "" : providedInviteCode.trim())) {
                recordFailedJoin(attemptKey);
                throw new IllegalArgumentException("邀请码不正确");
            }
            if (room.members.size() >= ROOM_CAPACITY) throw new IllegalStateException("房间人数已满");
            boolean duplicate = room.members.values().stream()
                    .anyMatch(member -> member.displayName.equalsIgnoreCase(displayName));
            if (duplicate) throw new IllegalArgumentException("该昵称已在房间中使用");

            token = newToken();
            Member member = new Member(newId(), displayName, false, token);
            room.members.put(member.id, member);
            room.tokens.put(token, member.id);
            room.lastActivity = System.currentTimeMillis();
            memberId = member.id;
        }
        joinAttempts.remove(attemptKey);
        publishChanged(roomId);
        return new JoinResponse(memberId, token, roomView(roomId, null, token));
    }

    public RoomView roomView(String roomId, String principalName, String token) {
        Room room = requireRoom(roomId);
        synchronized (room) {
            Member self = resolveAccess(room, principalName, token);
            return toRoomView(room, self, principalName != null && room.ownerUsername.equalsIgnoreCase(principalName));
        }
    }

    public RoomView roomView(ConnectionIdentity identity) {
        Room room = requireRoom(identity.roomId);
        synchronized (room) {
            Member self = room.members.get(identity.memberId);
            if (self == null) throw new AccessDeniedException("房间身份已失效");
            boolean owner = self.owner && identity.principalName != null
                    && room.ownerUsername.equalsIgnoreCase(identity.principalName);
            return toRoomView(room, self, owner);
        }
    }

    public ConnectionIdentity connect(String roomId, String principalName, String token, String connectionId) {
        Room room = requireRoom(roomId);
        Member member;
        synchronized (room) {
            member = resolveAccess(room, principalName, token);
            member.connections.add(connectionId);
            member.sequences.put(connectionId, -1L);
            member.lastSeen = System.currentTimeMillis();
            room.lastActivity = member.lastSeen;
        }
        publishChanged(roomId);
        return new ConnectionIdentity(roomId, member.id, token, principalName, connectionId);
    }

    public void disconnect(ConnectionIdentity identity) {
        Room room = rooms.get(identity.roomId);
        if (room == null) return;
        synchronized (room) {
            Member member = room.members.get(identity.memberId);
            if (member == null) return;
            member.connections.remove(identity.connectionId);
            member.sequences.remove(identity.connectionId);
            member.lastSeen = System.currentTimeMillis();
            room.lastActivity = member.lastSeen;
        }
        publishChanged(identity.roomId);
    }

    public void leave(String roomId, String token) {
        Room room = requireRoom(roomId);
        synchronized (room) {
            Member member = resolveAccess(room, null, token);
            if (member.owner) throw new IllegalStateException("房主需要关闭房间");
            if (isActivePlayer(room, member.id)) throw new IllegalStateException("当前参赛者不能在比赛中离开房间");
            removeMember(room, member);
            room.lastActivity = System.currentTimeMillis();
        }
        publishChanged(roomId);
    }

    public RoomView start(String roomId, String ownerUsername, String leftMemberId,
                          String rightMemberId, String articleId) {
        Room room = requireOwnedRoom(roomId, ownerUsername);
        String roundId;
        synchronized (room) {
            if (room.state != RoomState.WAITING) throw new IllegalStateException("当前房间状态不能开始比赛");
            if (leftMemberId == null || leftMemberId.equals(rightMemberId)) {
                throw new IllegalArgumentException("请选择两名不同的参赛者");
            }
            Member left = requireMember(room, leftMemberId);
            Member right = requireMember(room, rightMemberId);
            if (!left.isOnline() || !right.isOnline()) throw new IllegalStateException("两名参赛者都必须在线");
            Article article = "random".equals(articleId) ? articles.random() : articles.require(articleId);
            long now = System.currentTimeMillis();
            room.battle = new Battle(newId(), article.getId(), left.id, right.id, now + countdownMillis);
            roundId = room.battle.roundId;
            room.state = RoomState.COUNTDOWN;
            room.lastActivity = now;
            scheduler.schedule(() -> beginRound(room.id, roundId), countdownMillis, TimeUnit.MILLISECONDS);
        }
        publishChanged(roomId);
        return roomView(roomId, ownerUsername, null);
    }

    public RoomView reset(String roomId, String ownerUsername) {
        Room room = requireOwnedRoom(roomId, ownerUsername);
        synchronized (room) {
            if (room.state != RoomState.FINISHED) throw new IllegalStateException("比赛尚未结束");
            room.state = RoomState.WAITING;
            room.battle = null;
            room.lastActivity = System.currentTimeMillis();
        }
        publishChanged(roomId);
        return roomView(roomId, ownerUsername, null);
    }

    public RoomView rotateInviteCode(String roomId, String ownerUsername) {
        Room room = requireOwnedRoom(roomId, ownerUsername);
        synchronized (room) {
            room.inviteCode = inviteCode();
            room.lastActivity = System.currentTimeMillis();
        }
        publishChanged(roomId);
        return roomView(roomId, ownerUsername, null);
    }

    public RoomView kick(String roomId, String ownerUsername, String memberId) {
        Room room = requireOwnedRoom(roomId, ownerUsername);
        synchronized (room) {
            Member member = requireMember(room, memberId);
            if (member.owner) throw new IllegalArgumentException("不能移除房主");
            if (isActivePlayer(room, member.id)) throw new IllegalStateException("比赛期间不能移除参赛者");
            removeMember(room, member);
            room.lastActivity = System.currentTimeMillis();
        }
        publishChanged(roomId);
        return roomView(roomId, ownerUsername, null);
    }

    public void close(String roomId, String ownerUsername) {
        Room room = requireOwnedRoom(roomId, ownerUsername);
        synchronized (room) {
            room.state = RoomState.CLOSED;
        }
        rooms.remove(roomId, room);
        ownerRooms.remove(room.ownerUsername, roomId);
        events.publishEvent(TypingRoomEvent.closed(roomId));
    }

    public boolean submitInput(ConnectionIdentity identity, long sequence, String requestedInput) {
        Room room = requireRoom(identity.roomId);
        boolean changed = false;
        synchronized (room) {
            if (room.state != RoomState.RUNNING || room.battle == null) {
                throw new IllegalStateException("比赛尚未开始");
            }
            Member member = room.members.get(identity.memberId);
            if (member == null) throw new AccessDeniedException("房间身份已失效");
            PlayerStats stats = room.battle.statsFor(member.id);
            if (stats == null) throw new AccessDeniedException("你不是本局参赛者");
            Long lastSequence = member.sequences.get(identity.connectionId);
            if (lastSequence == null) throw new AccessDeniedException("连接已失效");
            if (sequence <= lastSequence) return false;
            long now = System.currentTimeMillis();
            if (stats.lastInputAt > 0 && now - stats.lastInputAt < 12) return false;
            member.sequences.put(identity.connectionId, sequence);
            stats.lastInputAt = now;

            Article article = articles.require(room.battle.articleId);
            String input = requestedInput == null ? "" : requestedInput;
            if (input.length() > article.getLength() + 1) throw new IllegalArgumentException("输入内容过长");
            String previous = stats.input;
            if (!input.startsWith(previous) && !previous.startsWith(input)) {
                throw new IllegalArgumentException("只能从末尾继续输入或退格修正");
            }

            if (previous.startsWith(input)) {
                stats.input = input;
                stats.correctCount = commonPrefix(input, article.getContent());
                changed = true;
            } else {
                String added = input.substring(previous.length());
                if (added.length() > MAX_INPUT_DELTA) throw new IllegalArgumentException("单次输入字符过多");
                if (commonPrefix(previous, article.getContent()) < previous.length()) {
                    throw new IllegalStateException("请先修正当前错字");
                }
                int acceptedLength = previous.length();
                boolean mismatch = false;
                for (int index = 0; index < added.length(); index++) {
                    int articleIndex = previous.length() + index;
                    stats.insertedCount++;
                    if (!mismatch && articleIndex < article.getLength()
                            && added.charAt(index) == article.getContent().charAt(articleIndex)) {
                        acceptedLength++;
                    } else {
                        stats.errors++;
                        if (!mismatch) acceptedLength++;
                        mismatch = true;
                    }
                }
                stats.input = input.substring(0, Math.min(input.length(), acceptedLength));
                stats.correctCount = commonPrefix(stats.input, article.getContent());
                changed = true;
            }
            room.lastActivity = now;
            if (stats.correctCount == article.getLength() && stats.input.equals(article.getContent())) {
                stats.finishedAt = now;
                finishLocked(room, member.id, "COMPLETED", now);
            }
        }
        if (changed) publishChanged(identity.roomId);
        return changed;
    }

    private void beginRound(String roomId, String roundId) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        boolean started = false;
        synchronized (room) {
            if (room.state == RoomState.COUNTDOWN && room.battle != null
                    && room.battle.roundId.equals(roundId)) {
                long now = System.currentTimeMillis();
                room.state = RoomState.RUNNING;
                room.battle.startedAt = now;
                room.battle.endsAt = now + roundMillis;
                room.lastActivity = now;
                started = true;
                scheduler.schedule(() -> timeoutRound(roomId, roundId), roundMillis, TimeUnit.MILLISECONDS);
            }
        }
        if (started) publishChanged(roomId);
    }

    private void timeoutRound(String roomId, String roundId) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        boolean finished = false;
        synchronized (room) {
            if (room.state == RoomState.RUNNING && room.battle != null
                    && room.battle.roundId.equals(roundId)) {
                String winnerId = timeoutWinner(room.battle);
                finishLocked(room, winnerId, "TIMEOUT", System.currentTimeMillis());
                finished = true;
            }
        }
        if (finished) publishChanged(roomId);
    }

    private String timeoutWinner(Battle battle) {
        int comparison = Integer.compare(battle.left.correctCount, battle.right.correctCount);
        if (comparison == 0) comparison = Double.compare(accuracy(battle.left), accuracy(battle.right));
        if (comparison == 0) comparison = Integer.compare(battle.right.errors, battle.left.errors);
        if (comparison > 0) return battle.left.memberId;
        if (comparison < 0) return battle.right.memberId;
        return null;
    }

    private void finishLocked(Room room, String winnerId, String reason, long now) {
        Battle battle = room.battle;
        if (battle == null || room.state == RoomState.FINISHED) return;
        room.state = RoomState.FINISHED;
        battle.finishedAt = now;
        battle.winnerId = winnerId;
        battle.finishReason = reason;
        room.lastActivity = now;
        Article article = articles.require(battle.articleId);
        Member winner = winnerId == null ? null : room.members.get(winnerId);
        room.history.addFirst(new HistoryView(battle.roundId, article.getTitle(), now,
                winner == null ? null : winner.displayName, reason,
                toPlayerView(room, battle, battle.left, now),
                toPlayerView(room, battle, battle.right, now)));
        while (room.history.size() > HISTORY_LIMIT) room.history.removeLast();
    }

    private RoomView toRoomView(Room room, Member self, boolean ownerAccess) {
        long now = System.currentTimeMillis();
        List<MemberView> members = room.members.values().stream()
                .sorted(Comparator.comparing((Member member) -> !member.owner)
                        .thenComparing(member -> member.displayName.toLowerCase(Locale.ROOT)))
                .map(member -> new MemberView(member.id, member.displayName, member.owner, member.isOnline(), playerSide(room, member.id)))
                .collect(Collectors.toList());
        List<ArticleView> articleViews = articles.all().stream()
                .map(article -> articleView(article, false))
                .collect(Collectors.toList());
        BattleView battleView = room.battle == null ? null : toBattleView(room, now);
        boolean canType = room.state == RoomState.RUNNING && room.battle != null
                && (self.id.equals(room.battle.left.memberId) || self.id.equals(room.battle.right.memberId));
        return new RoomView(now, room.id, room.name, room.ownerUsername, room.state.name(), ownerAccess,
                self.id, ownerAccess ? room.inviteCode : null, canType, members, articleViews,
                battleView, new ArrayList<>(room.history));
    }

    private BattleView toBattleView(Room room, long now) {
        Battle battle = room.battle;
        Article article = articles.require(battle.articleId);
        boolean revealContent = room.state == RoomState.RUNNING || room.state == RoomState.FINISHED;
        Member winner = battle.winnerId == null ? null : room.members.get(battle.winnerId);
        return new BattleView(battle.roundId, battle.countdownEndsAt, battle.startedAt, battle.endsAt,
                battle.finishedAt, articleView(article, revealContent),
                toPlayerView(room, battle, battle.left, now), toPlayerView(room, battle, battle.right, now),
                battle.winnerId, winner == null ? null : winner.displayName, battle.finishReason);
    }

    private PlayerView toPlayerView(Room room, Battle battle, PlayerStats stats, long now) {
        Member member = room.members.get(stats.memberId);
        long elapsed = 0;
        if (battle.startedAt != null) {
            long stop = stats.finishedAt != null ? stats.finishedAt
                    : battle.finishedAt != null ? battle.finishedAt : now;
            elapsed = Math.max(0, Math.min(stop, battle.endsAt == null ? stop : battle.endsAt) - battle.startedAt);
        }
        int length = articles.require(battle.articleId).getLength();
        int progress = length == 0 ? 0 : (int) Math.round(stats.correctCount * 100.0 / length);
        int cpm = elapsed <= 0 ? 0 : (int) Math.round(stats.correctCount * 60_000.0 / Math.max(1000, elapsed));
        return new PlayerView(stats.memberId, member == null ? "已离开" : member.displayName,
                member != null && member.isOnline(), stats.input, stats.correctCount, progress,
                cpm, accuracy(stats), stats.errors, elapsed, stats.finishedAt != null);
    }

    private ArticleView articleView(Article article, boolean includeContent) {
        return new ArticleView(article.getId(), article.getTitle(), article.getCategory(),
                article.getLength(), includeContent ? article.getContent() : null);
    }

    private PublicRoomView toPublicView(Room room) {
        synchronized (room) {
            int online = (int) room.members.values().stream().filter(Member::isOnline).count();
            return new PublicRoomView(room.id, room.name, room.ownerUsername, online, ROOM_CAPACITY, room.state.name());
        }
    }

    private Member resolveAccess(Room room, String principalName, String token) {
        requireOpen(room);
        if (principalName != null && room.ownerUsername.equalsIgnoreCase(principalName)) {
            return requireMember(room, room.ownerMemberId);
        }
        String memberId = token == null ? null : room.tokens.get(token);
        if (memberId == null) throw new AccessDeniedException("请先输入昵称和邀请码加入房间");
        return requireMember(room, memberId);
    }

    private Room requireOwnedRoom(String roomId, String ownerUsername) {
        Room room = requireRoom(roomId);
        if (ownerUsername == null || !room.ownerUsername.equalsIgnoreCase(ownerUsername)) {
            throw new AccessDeniedException("只有房主可以执行此操作");
        }
        return room;
    }

    private Room requireRoom(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null || room.state == RoomState.CLOSED) throw new NoSuchElementException("房间不存在或已关闭");
        return room;
    }

    private Member requireMember(Room room, String memberId) {
        Member member = room.members.get(memberId);
        if (member == null) throw new NoSuchElementException("房间成员不存在");
        return member;
    }

    private void requireOpen(Room room) {
        if (room.state == RoomState.CLOSED) throw new NoSuchElementException("房间已关闭");
    }

    private boolean isActivePlayer(Room room, String memberId) {
        return (room.state == RoomState.COUNTDOWN || room.state == RoomState.RUNNING) && room.battle != null
                && (memberId.equals(room.battle.left.memberId) || memberId.equals(room.battle.right.memberId));
    }

    private String playerSide(Room room, String memberId) {
        if (room.battle == null) return null;
        if (memberId.equals(room.battle.left.memberId)) return "LEFT";
        if (memberId.equals(room.battle.right.memberId)) return "RIGHT";
        return null;
    }

    private void removeMember(Room room, Member member) {
        room.members.remove(member.id);
        if (member.token != null) room.tokens.remove(member.token);
    }

    private void publishChanged(String roomId) {
        events.publishEvent(TypingRoomEvent.changed(roomId));
    }

    private void cleanupExpiredRooms() {
        long cutoff = System.currentTimeMillis() - roomExpiryMillis;
        for (Room room : rooms.values()) {
            boolean expired;
            synchronized (room) {
                Member owner = room.members.get(room.ownerMemberId);
                expired = room.state != RoomState.RUNNING && room.state != RoomState.COUNTDOWN
                        && (owner == null || !owner.isOnline()) && room.lastActivity < cutoff;
                if (expired) room.state = RoomState.CLOSED;
            }
            if (expired && rooms.remove(room.id, room)) {
                ownerRooms.remove(room.ownerUsername, room.id);
                events.publishEvent(TypingRoomEvent.closed(room.id));
            }
        }
        long attemptCutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);
        joinAttempts.entrySet().removeIf(entry -> entry.getValue().startedAt < attemptCutoff);
    }

    private void checkJoinRateLimit(String key) {
        AttemptWindow attempt = joinAttempts.get(key);
        if (attempt != null && attempt.blocked(System.currentTimeMillis())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "邀请码尝试过于频繁，请稍后再试");
        }
    }

    private void recordFailedJoin(String key) {
        long now = System.currentTimeMillis();
        AttemptWindow attempt = joinAttempts.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt > TimeUnit.MINUTES.toMillis(10)) {
                return new AttemptWindow(now, 1);
            }
            current.failures++;
            return current;
        });
        if (attempt.blocked(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "邀请码尝试过于频繁，请稍后再试");
        }
    }

    private static double accuracy(PlayerStats stats) {
        if (stats.insertedCount == 0) return 0;
        return Math.round((stats.insertedCount - stats.errors) * 10_000.0 / stats.insertedCount) / 100.0;
    }

    private static int commonPrefix(String left, String right) {
        int length = Math.min(left.length(), right.length());
        int index = 0;
        while (index < length && left.charAt(index) == right.charAt(index)) index++;
        return index;
    }

    private static int stateOrder(RoomState state) {
        if (state == RoomState.WAITING) return 0;
        if (state == RoomState.COUNTDOWN || state == RoomState.RUNNING) return 1;
        return 2;
    }

    private static String normalizeRoomName(String value) {
        String name = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 30 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("房间名需要 2 至 30 个字符");
        }
        return name;
    }

    private static String normalizeDisplayName(String value) {
        String name = value == null ? "" : value.trim();
        if (!DISPLAY_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("昵称需要 1 至 16 位中文、字母、数字或常用符号");
        }
        return name;
    }

    private String newRoomId() {
        String id;
        do {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        } while (rooms.containsKey(id));
        return id;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String inviteCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        byte[] left = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private enum RoomState { WAITING, COUNTDOWN, RUNNING, FINISHED, CLOSED }

    private static final class Room {
        final String id;
        final String name;
        final String ownerUsername;
        final Map<String, Member> members = new LinkedHashMap<>();
        final Map<String, String> tokens = new HashMap<>();
        final Deque<HistoryView> history = new ArrayDeque<>();
        String ownerMemberId;
        String inviteCode;
        RoomState state = RoomState.WAITING;
        Battle battle;
        long lastActivity = System.currentTimeMillis();

        Room(String id, String name, String ownerUsername, String inviteCode) {
            this.id = id;
            this.name = name;
            this.ownerUsername = ownerUsername;
            this.inviteCode = inviteCode;
        }
    }

    private static final class Member {
        final String id;
        final String displayName;
        final boolean owner;
        final String token;
        final Set<String> connections = new HashSet<>();
        final Map<String, Long> sequences = new HashMap<>();
        long lastSeen = System.currentTimeMillis();

        Member(String id, String displayName, boolean owner, String token) {
            this.id = id;
            this.displayName = displayName;
            this.owner = owner;
            this.token = token;
        }

        boolean isOnline() { return !connections.isEmpty(); }
    }

    private static final class Battle {
        final String roundId;
        final String articleId;
        final PlayerStats left;
        final PlayerStats right;
        final long countdownEndsAt;
        Long startedAt;
        Long endsAt;
        Long finishedAt;
        String winnerId;
        String finishReason;

        Battle(String roundId, String articleId, String leftId, String rightId, long countdownEndsAt) {
            this.roundId = roundId;
            this.articleId = articleId;
            this.left = new PlayerStats(leftId);
            this.right = new PlayerStats(rightId);
            this.countdownEndsAt = countdownEndsAt;
        }

        PlayerStats statsFor(String memberId) {
            if (left.memberId.equals(memberId)) return left;
            if (right.memberId.equals(memberId)) return right;
            return null;
        }
    }

    private static final class PlayerStats {
        final String memberId;
        String input = "";
        int correctCount;
        int insertedCount;
        int errors;
        long lastInputAt;
        Long finishedAt;

        PlayerStats(String memberId) { this.memberId = memberId; }
    }

    private static final class AttemptWindow {
        final long startedAt;
        int failures;

        AttemptWindow(long startedAt, int failures) {
            this.startedAt = startedAt;
            this.failures = failures;
        }

        boolean blocked(long now) {
            return failures >= 5 && now - startedAt <= TimeUnit.MINUTES.toMillis(10);
        }
    }
}
