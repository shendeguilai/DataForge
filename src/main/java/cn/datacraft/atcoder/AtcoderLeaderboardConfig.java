package cn.datacraft.atcoder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "atcoder_leaderboard_config")
class AtcoderLeaderboardConfig {
    static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "contest_id", nullable = false, length = 64)
    private String contestId;

    @Column(name = "display_title", nullable = false, length = 160)
    private String displayTitle;

    @Column(name = "official_title", nullable = false, length = 240)
    private String officialTitle;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "tasks_json", nullable = false, columnDefinition = "TEXT")
    private String tasksJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AtcoderLeaderboardConfig() {}

    AtcoderLeaderboardConfig(String contestId, String displayTitle, String officialTitle,
                              Instant startAt, Instant endAt, String tasksJson, Instant updatedAt) {
        this.id = SINGLETON_ID;
        update(contestId, displayTitle, officialTitle, startAt, endAt, tasksJson, updatedAt);
    }

    void update(String contestId, String displayTitle, String officialTitle,
                Instant startAt, Instant endAt, String tasksJson, Instant updatedAt) {
        this.contestId = contestId;
        this.displayTitle = displayTitle;
        this.officialTitle = officialTitle;
        this.startAt = startAt;
        this.endAt = endAt;
        this.tasksJson = tasksJson;
        this.updatedAt = updatedAt;
    }

    String getContestId() { return contestId; }
    String getDisplayTitle() { return displayTitle; }
    String getOfficialTitle() { return officialTitle; }
    Instant getStartAt() { return startAt; }
    Instant getEndAt() { return endAt; }
    String getTasksJson() { return tasksJson; }
    Instant getUpdatedAt() { return updatedAt; }
}
