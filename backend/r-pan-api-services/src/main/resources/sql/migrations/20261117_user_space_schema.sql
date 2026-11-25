-- Apply once before deploying quota-enabled code. Backfill follows in a separate migration.
ALTER TABLE r_pan_user_file ADD COLUMN space_bytes BIGINT NOT NULL DEFAULT 0;
CREATE TABLE r_pan_user_space (
    user_id BIGINT NOT NULL PRIMARY KEY,
    total_bytes BIGINT NOT NULL,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    reserved_bytes BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE r_pan_upload_reservation (
    upload_id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    bytes BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, request_id)
) ENGINE=InnoDB;
CREATE INDEX idx_reservation_owner_state ON r_pan_upload_reservation(user_id, state);
