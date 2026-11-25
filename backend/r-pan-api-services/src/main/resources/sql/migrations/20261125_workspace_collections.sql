ALTER TABLE r_pan_upload_task ADD COLUMN collection_member_generation BIGINT DEFAULT NULL;
ALTER TABLE r_pan_upload_task ADD COLUMN collection_id VARCHAR(36) DEFAULT NULL;
CREATE TABLE r_pan_workspace_collection (
    collection_id VARCHAR(36) NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL,
    creator_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    deadline TIMESTAMP NOT NULL,
    max_file_bytes BIGINT NOT NULL,
    allowed_suffix VARCHAR(32) NOT NULL DEFAULT '',
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (max_file_bytes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE r_pan_workspace_collection_invite (
    collection_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    generation BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY(collection_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE r_pan_workspace_submission (
    upload_id VARCHAR(36) NOT NULL PRIMARY KEY,
    collection_id VARCHAR(36) NOT NULL,
    actor_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_collection_submissions(collection_id,actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
