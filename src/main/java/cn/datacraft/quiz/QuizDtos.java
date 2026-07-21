package cn.datacraft.quiz;

import java.util.List;

public final class QuizDtos {
    private QuizDtos() {}

    public static final class CatalogView {
        public final int total;
        public final List<String> categories;
        public final List<String> difficulties;
        public final List<QuestionSummaryView> questions;

        public CatalogView(int total, List<String> categories, List<String> difficulties,
                           List<QuestionSummaryView> questions) {
            this.total = total;
            this.categories = categories;
            this.difficulties = difficulties;
            this.questions = questions;
        }
    }

    public static final class QuestionSummaryView {
        public final String id;
        public final String category;
        public final String difficulty;
        public final String promptText;

        public QuestionSummaryView(String id, String category, String difficulty, String promptText) {
            this.id = id;
            this.category = category;
            this.difficulty = difficulty;
            this.promptText = promptText;
        }
    }

    public static final class QuestionView {
        public final String id;
        public final String type;
        public final String category;
        public final String difficulty;
        public final String promptText;
        public final String imageUrl;
        public final List<QuizQuestionLibrary.Option> options;

        public QuestionView(String id, String type, String category, String difficulty, String promptText,
                            String imageUrl, List<QuizQuestionLibrary.Option> options) {
            this.id = id;
            this.type = type;
            this.category = category;
            this.difficulty = difficulty;
            this.promptText = promptText;
            this.imageUrl = imageUrl;
            this.options = options;
        }
    }

    public static final class AnswerView {
        public final String answer;
        public final String explanation;
        public final String example;
        public final String pitfall;

        public AnswerView(String answer, String explanation, String example, String pitfall) {
            this.answer = answer;
            this.explanation = explanation;
            this.example = example;
            this.pitfall = pitfall;
        }
    }

    public static final class MemberView {
        public final String memberId;
        public final String displayName;
        public final boolean owner;
        public final boolean online;
        public final int score;
        public final boolean excluded;

        public MemberView(String memberId, String displayName, boolean owner, boolean online,
                          int score, boolean excluded) {
            this.memberId = memberId;
            this.displayName = displayName;
            this.owner = owner;
            this.online = online;
            this.score = score;
            this.excluded = excluded;
        }
    }

    public static final class AttemptView {
        public final String memberId;
        public final String displayName;
        public final String result;
        public final long buzzedAt;

        public AttemptView(String memberId, String displayName, String result, long buzzedAt) {
            this.memberId = memberId;
            this.displayName = displayName;
            this.result = result;
            this.buzzedAt = buzzedAt;
        }
    }

    public static final class RoundView {
        public final String roundId;
        public final int questionNumber;
        public final int questionTotal;
        public final String state;
        public final Long countdownEndsAt;
        public final Long openedAt;
        public final Long endsAt;
        public final Long finishedAt;
        public final String winnerId;
        public final String winnerName;
        public final List<AttemptView> attempts;

        public RoundView(String roundId, int questionNumber, int questionTotal, String state,
                         Long countdownEndsAt, Long openedAt, Long endsAt, Long finishedAt,
                         String winnerId, String winnerName, List<AttemptView> attempts) {
            this.roundId = roundId;
            this.questionNumber = questionNumber;
            this.questionTotal = questionTotal;
            this.state = state;
            this.countdownEndsAt = countdownEndsAt;
            this.openedAt = openedAt;
            this.endsAt = endsAt;
            this.finishedAt = finishedAt;
            this.winnerId = winnerId;
            this.winnerName = winnerName;
            this.attempts = attempts;
        }
    }

    public static final class RoomView {
        public final long serverTime;
        public final String roomCode;
        public final String name;
        public final String ownerName;
        public final String state;
        public final boolean owner;
        public final String selfMemberId;
        public final String inviteCode;
        public final String joinUrl;
        public final int onlineCount;
        public final int capacity;
        public final int buzzSeconds;
        public final boolean canBuzz;
        public final List<MemberView> members;
        public final QuestionView question;
        public final RoundView round;
        public final AnswerView referenceAnswer;
        public final AnswerView revealedAnswer;
        public final List<QuestionSummaryView> availableQuestions;

        public RoomView(long serverTime, String roomCode, String name, String ownerName, String state,
                        boolean owner, String selfMemberId, String inviteCode, String joinUrl,
                        int onlineCount, int capacity, int buzzSeconds, boolean canBuzz,
                        List<MemberView> members, QuestionView question, RoundView round,
                        AnswerView referenceAnswer, AnswerView revealedAnswer,
                        List<QuestionSummaryView> availableQuestions) {
            this.serverTime = serverTime;
            this.roomCode = roomCode;
            this.name = name;
            this.ownerName = ownerName;
            this.state = state;
            this.owner = owner;
            this.selfMemberId = selfMemberId;
            this.inviteCode = inviteCode;
            this.joinUrl = joinUrl;
            this.onlineCount = onlineCount;
            this.capacity = capacity;
            this.buzzSeconds = buzzSeconds;
            this.canBuzz = canBuzz;
            this.members = members;
            this.question = question;
            this.round = round;
            this.referenceAnswer = referenceAnswer;
            this.revealedAnswer = revealedAnswer;
            this.availableQuestions = availableQuestions;
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
        public final String roomCode;
        public final String memberId;
        public final String token;
        public final String principalName;
        public final String connectionId;

        public ConnectionIdentity(String roomCode, String memberId, String token,
                                  String principalName, String connectionId) {
            this.roomCode = roomCode;
            this.memberId = memberId;
            this.token = token;
            this.principalName = principalName;
            this.connectionId = connectionId;
        }
    }
}
