CREATE TABLE csp_sim_records (
    id VARCHAR(36) PRIMARY KEY,
    kind VARCHAR(24) NOT NULL,
    owner_key VARCHAR(40) NOT NULL,
    parent_id VARCHAR(36),
    unique_key VARCHAR(128) UNIQUE,
    state VARCHAR(24) NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    payload TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_csp_owner_kind ON csp_sim_records(owner_key, kind);
CREATE INDEX idx_csp_parent_kind ON csp_sim_records(parent_id, kind);
CREATE INDEX idx_csp_queue ON csp_sim_records(kind, state, lease_until);
