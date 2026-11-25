-- 增量迁移：只新建上传任务表，执行前确认尚不存在。
CREATE TABLE r_pan_upload_task (
    upload_id VARCHAR(36) NOT NULL COMMENT '服务端生成的独立上传任务ID',
    user_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    expected_md5 CHAR(32) NOT NULL COMMENT '客户端声明的整文件摘要，最终完成时须由服务端校验',
    file_size BIGINT NOT NULL,
    chunk_size INT NOT NULL,
    total_chunks INT NOT NULL,
    storage_type VARCHAR(64) NOT NULL,
    task_state VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
    expires_at DATETIME(3) NOT NULL,
    final_path VARCHAR(700) DEFAULT NULL COMMENT '收尾阶段正式文件的恢复定位',
    real_file_id BIGINT DEFAULT NULL,
    user_file_id BIGINT DEFAULT NULL,
    last_error VARCHAR(1000) DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(upload_id),
    KEY idx_upload_owner_state(user_id,task_state,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复上传任务；分片字节单独保存';
