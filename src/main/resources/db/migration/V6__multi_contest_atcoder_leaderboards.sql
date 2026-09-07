CREATE SEQUENCE atcoder_leaderboard_config_seq START WITH 2 INCREMENT BY 1;

ALTER TABLE atcoder_leaderboard_config
    ADD CONSTRAINT uk_atcoder_leaderboard_config_contest UNIQUE (contest_id);

CREATE TABLE atcoder_leaderboard_snapshots (
    contest_id VARCHAR(64) PRIMARY KEY,
    standings_json TEXT,
    synced_at TIMESTAMP WITH TIME ZONE,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    CONSTRAINT fk_atcoder_leaderboard_snapshot_contest
        FOREIGN KEY (contest_id) REFERENCES atcoder_leaderboard_config (contest_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_atcoder_leaderboard_config_time
    ON atcoder_leaderboard_config (start_at DESC, updated_at DESC);
