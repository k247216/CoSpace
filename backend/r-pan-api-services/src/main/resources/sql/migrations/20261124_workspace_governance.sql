-- Additive foundation. Existing personal file and quota tables remain authoritative.
CREATE TABLE r_pan_workspace (
    workspace_id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    owner_id BIGINT NOT NULL,
    total_bytes BIGINT NOT NULL,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    reserved_bytes BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (total_bytes >= 0 AND used_bytes >= 0 AND reserved_bytes >= 0
        AND used_bytes <= total_bytes AND reserved_bytes <= total_bytes - used_bytes)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE r_pan_workspace_member (
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    generation BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (workspace_id,user_id),
    INDEX idx_workspace_user(user_id,active),
    CHECK (role='OWNER' OR role='ADMIN' OR role='MEMBER' OR role='VIEWER'),
    CHECK (generation > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
