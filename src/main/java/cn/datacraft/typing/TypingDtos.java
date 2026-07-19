package cn.datacraft.typing;

import java.util.List;

public final class TypingDtos {
    private TypingDtos() {}

    public static final class PublicRoomView {
        public final String roomId;
        public final String name;
        public final String ownerName;
        public final int onlineCount;
        public final int capacity;
        public final String state;

        public PublicRoomView(String roomId, String name, String ownerName, int onlineCount, int capacity, String state) {
            this.roomId = roomId;
            this.name = name;
            this.ownerName = ownerName;
            this.onlineCount = onlineCount;
            this.capacity = capacity;
            this.state = state;
        }
    }

    public static final class ArticleView {
        public final String id;
        public final String title;
        public final String category;
        public final int length;
        public final String content;

        public ArticleView(String id, String title, String category, int length, String content) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.length = length;
            this.content = content;
        }
    }

    public static final class MemberView {
        public final String memberId;
        public final String displayName;
        public final boolean owner;
        public final boolean online;
        public final String playerSide;

        public MemberView(String memberId, String displayName, boolean owner, boolean online, String playerSide) {
            this.memberId = memberId;
            this.displayName = displayName;
            this.owner = owner;
            this.online = online;
            this.playerSide = playerSide;
        }
    }

    public static final class PlayerView {
        public final String memberId;
        public final String displayName;
        public final boolean online;
        public final String input;
        public final int correctCount;
        public final int progress;
        public final int cpm;
        public final double accuracy;
        public final int errors;
        public final long elapsedMillis;
        public final boolean finished;

        public PlayerView(String memberId, String displayName, boolean online, String input,
                          int correctCount, int progress, int cpm, double accuracy,
                          int errors, long elapsedMillis, boolean finished) {
            this.memberId = memberId;
            this.displayName = displayName;
            this.online = online;
            this.input = input;
            this.correctCount = correctCount;
            this.progress = progress;
            this.cpm = cpm;
            this.accuracy = accuracy;
            this.errors = errors;
            this.elapsedMillis = elapsedMillis;
            this.finished = finished;
        }
    }

    public static final class BattleView {
        public final String roundId;
        public final Long countdownEndsAt;
        public final Long startedAt;
        public final Long endsAt;
        public final Long finishedAt;
        public final ArticleView article;
        public final PlayerView left;
        public final PlayerView right;
        public final String winnerId;
        public final String winnerName;
        public final String finishReason;

        public BattleView(String roundId, Long countdownEndsAt, Long startedAt, Long endsAt, Long finishedAt,
                          ArticleView article, PlayerView left, PlayerView right,
                          String winnerId, String winnerName, String finishReason) {
            this.roundId = roundId;
            this.countdownEndsAt = countdownEndsAt;
            this.startedAt = startedAt;
            this.endsAt = endsAt;
            this.finishedAt = finishedAt;
            this.article = article;
            this.left = left;
            this.right = right;
            this.winnerId = winnerId;
            this.winnerName = winnerName;
            this.finishReason = finishReason;
        }
    }

    public static final class HistoryView {
        public final String roundId;
        public final String articleTitle;
        public final long finishedAt;
        public final String winnerName;
        public final String finishReason;
        public final PlayerView left;
        public final PlayerView right;

        public HistoryView(String roundId, String articleTitle, long finishedAt, String winnerName,
                           String finishReason, PlayerView left, PlayerView right) {
            this.roundId = roundId;
            this.articleTitle = articleTitle;
            this.finishedAt = finishedAt;
            this.winnerName = winnerName;
            this.finishReason = finishReason;
            this.left = left;
            this.right = right;
        }
    }

    public static final class RoomView {
        public final long serverTime;
        public final String roomId;
        public final String name;
        public final String ownerName;
        public final String state;
        public final boolean owner;
        public final String selfMemberId;
        public final String inviteCode;
        public final boolean canType;
        public final List<MemberView> members;
        public final List<ArticleView> articles;
        public final BattleView battle;
        public final List<HistoryView> history;

        public RoomView(long serverTime, String roomId, String name, String ownerName, String state,
                        boolean owner, String selfMemberId, String inviteCode, boolean canType,
                        List<MemberView> members, List<ArticleView> articles,
                        BattleView battle, List<HistoryView> history) {
            this.serverTime = serverTime;
            this.roomId = roomId;
            this.name = name;
            this.ownerName = ownerName;
            this.state = state;
            this.owner = owner;
            this.selfMemberId = selfMemberId;
            this.inviteCode = inviteCode;
            this.canType = canType;
            this.members = members;
            this.articles = articles;
            this.battle = battle;
            this.history = history;
        }
    }

    public static final class JoinResponse {
        public final String memberId;
        public final String token;
        public final RoomView room;

        public JoinResponse(String memberId, String token, RoomView room) {
            this.memberId = memberId;
            this.token = token;
            this.room = room;
        }
    }

    public static final class ConnectionIdentity {
        public final String roomId;
        public final String memberId;
        public final String token;
        public final String principalName;
        public final String connectionId;

        public ConnectionIdentity(String roomId, String memberId, String token, String principalName, String connectionId) {
            this.roomId = roomId;
            this.memberId = memberId;
            this.token = token;
            this.principalName = principalName;
            this.connectionId = connectionId;
        }
    }
}
