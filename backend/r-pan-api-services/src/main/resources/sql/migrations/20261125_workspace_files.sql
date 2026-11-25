-- Existing personal references keep their real user_id. Team references use user_id=0,
-- workspace_id identifies their owner and create_user identifies the actor.
ALTER TABLE r_pan_user_file ADD COLUMN workspace_id BIGINT DEFAULT NULL;
ALTER TABLE r_pan_user_file ADD COLUMN recycle_root_id BIGINT DEFAULT NULL;
CREATE INDEX idx_workspace_tree ON r_pan_user_file(workspace_id,parent_id,del_flag);
ALTER TABLE r_pan_upload_task
    ADD COLUMN workspace_id BIGINT DEFAULT NULL;
ALTER TABLE r_pan_upload_task ADD COLUMN membership_generation BIGINT DEFAULT NULL;
CREATE TABLE r_pan_workspace_reservation (
    upload_id VARCHAR(36) NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    bytes BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
    UNIQUE KEY uk_workspace_request(workspace_id,actor_id,request_id),
    CHECK (bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
