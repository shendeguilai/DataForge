CREATE TABLE kedaoj_homework_records (
    id VARCHAR(36) PRIMARY KEY,
    owner_key VARCHAR(40) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    state VARCHAR(24) NOT NULL,
    active_key VARCHAR(100) UNIQUE,
    lease_until TIMESTAMP WITH TIME ZONE,
    payload TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_hw_owner_kind ON kedaoj_homework_records(owner_key, kind);
CREATE INDEX idx_hw_queue ON kedaoj_homework_records(kind, state, lease_until);
