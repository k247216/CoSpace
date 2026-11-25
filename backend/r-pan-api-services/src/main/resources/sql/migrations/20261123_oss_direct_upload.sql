ALTER TABLE r_pan_upload_task
    ADD COLUMN direct_upload BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN multipart_id VARCHAR(255) DEFAULT NULL;

CREATE TABLE r_pan_upload_part (
    upload_id VARCHAR(36) NOT NULL,
    part_number INT NOT NULL,
    md5 CHAR(32) NOT NULL,
    PRIMARY KEY (upload_id,part_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对象存储直传分片授权摘要';
