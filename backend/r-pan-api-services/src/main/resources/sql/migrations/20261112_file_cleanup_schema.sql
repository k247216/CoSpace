-- 第一步：仅增加数据模型，尚不启用异步清理。
-- 旧库执行一次；先确认 lifecycle_state 和任务表尚不存在。
-- MySQL DDL 存在隐式提交，不要把本脚本视为可整体事务回滚的迁移。
ALTER TABLE r_pan_file ADD COLUMN lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    COMMENT 'ACTIVE允许引用，DELETING清理中，DELETED内容已删除';

CREATE TABLE r_pan_file_cleanup_task (
    task_id BIGINT NOT NULL COMMENT '清理任务ID，同时作为消息业务键',
    real_file_id BIGINT NOT NULL COMMENT '物理文件ID，一份内容一个清理生命周期',
    storage_type VARCHAR(64) NOT NULL COMMENT '存储实现标识，创建任务后不可改变',
    storage_locator VARCHAR(700) NOT NULL COMMENT '存储定位快照，创建任务后不可改变',
    execution_state VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/RETRY/SUCCEEDED/MANUAL',
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT，与执行状态独立',
    delivery_attempts INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
    execution_attempts INT NOT NULL DEFAULT 0 COMMENT '存储清理尝试次数',
    next_delivery_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下一次允许投递时间',
    lease_owner VARCHAR(64) DEFAULT NULL COMMENT '执行令牌，防止过期执行者覆盖结果',
    lease_until DATETIME(3) DEFAULT NULL COMMENT '执行租约截止时间',
    last_error VARCHAR(1000) DEFAULT NULL COMMENT '有界且脱敏的最近失败原因',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) DEFAULT NULL,
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_cleanup_real_file (real_file_id),
    KEY idx_cleanup_delivery (delivery_state, next_delivery_at),
    KEY idx_cleanup_execution (execution_state, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物理文件清理任务及Outbox';
