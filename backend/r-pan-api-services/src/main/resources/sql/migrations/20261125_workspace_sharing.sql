CREATE TABLE r_pan_workspace_share (
    share_id VARCHAR(36) NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    creator_id BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_team_share(workspace_id,recipient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
