ALTER TABLE generation_jobs ADD COLUMN validator_code TEXT;
ALTER TABLE generation_jobs ADD COLUMN brute_code TEXT;
CREATE TABLE generation_task_queue (
    id VARCHAR(36) PRIMARY KEY,
    phase VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    token VARCHAR(255),
    lease_until TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_generation_queue_state ON generation_task_queue(state, updated_at);
-- Resume previously persisted in-flight jobs after the first upgrade.
INSERT INTO generation_task_queue(id,phase,state,updated_at,version)
SELECT id, CASE WHEN status IN ('QUEUED','GENERATING','PACKAGING') OR (status='COMPILING' AND progress>=22) THEN 'GENERATE' ELSE 'ANALYZE' END,
       'QUEUED', updated_at, 0
FROM generation_jobs WHERE status IN ('CHECKING_CODE','ANALYZING','QUEUED','COMPILING','GENERATING','PACKAGING');
