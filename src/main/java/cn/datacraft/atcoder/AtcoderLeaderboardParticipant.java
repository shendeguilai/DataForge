package cn.datacraft.atcoder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "atcoder_leaderboard_participants")
class AtcoderLeaderboardParticipant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false, length = 40)
    private String displayName;

    @Column(name = "atcoder_username", nullable = false, length = 32)
    private String atcoderUsername;

    @Column(name = "atcoder_username_key", nullable = false, unique = true, length = 32)
    private String atcoderUsernameKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AtcoderLeaderboardParticipant() {}

    AtcoderLeaderboardParticipant(String displayName, String atcoderUsername,
                                  String atcoderUsernameKey, int sortOrder, Instant createdAt) {
        this.displayName = displayName;
        this.atcoderUsername = atcoderUsername;
        this.atcoderUsernameKey = atcoderUsernameKey;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }

    void update(String displayName, String atcoderUsername, String atcoderUsernameKey) {
        this.displayName = displayName;
        this.atcoderUsername = atcoderUsername;
        this.atcoderUsernameKey = atcoderUsernameKey;
    }

    Long getId() { return id; }
    String getDisplayName() { return displayName; }
    String getAtcoderUsername() { return atcoderUsername; }
    String getAtcoderUsernameKey() { return atcoderUsernameKey; }
    int getSortOrder() { return sortOrder; }
    Instant getCreatedAt() { return createdAt; }
}
