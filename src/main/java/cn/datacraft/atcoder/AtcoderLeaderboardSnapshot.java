package cn.datacraft.atcoder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "atcoder_leaderboard_snapshots")
class AtcoderLeaderboardSnapshot {
    @Id
    @Column(name = "contest_id", nullable = false, length = 64)
    private String contestId;

    @Column(name = "standings_json", columnDefinition = "TEXT")
    private String standingsJson;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    protected AtcoderLeaderboardSnapshot() {}

    AtcoderLeaderboardSnapshot(String contestId) {
        this.contestId = contestId;
    }

    void attempted(Instant now) {
        lastAttemptAt = now;
    }

    void succeeded(String standingsJson, Instant now) {
        this.standingsJson = standingsJson;
        syncedAt = now;
        lastAttemptAt = now;
        lastError = null;
    }

    void failed(String message, Instant now) {
        lastAttemptAt = now;
        lastError = message;
    }

    String getContestId() { return contestId; }
    String getStandingsJson() { return standingsJson; }
    Instant getSyncedAt() { return syncedAt; }
    Instant getLastAttemptAt() { return lastAttemptAt; }
    String getLastError() { return lastError; }
}
