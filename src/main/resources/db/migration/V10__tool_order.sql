CREATE TABLE site_tool_order (
    id BIGINT PRIMARY KEY,
    payload TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
