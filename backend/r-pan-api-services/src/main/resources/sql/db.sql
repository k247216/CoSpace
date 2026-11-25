SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for r_pan_file
-- ----------------------------
DROP TABLE IF EXISTS `r_pan_file`;
CREATE TABLE `r_pan_file`
(
    `file_id`                   bigint                                                 NOT NULL COMMENT '文件id',
    `filename`                  varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '文件名称',
    `real_path`                 varchar(700) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '文件物理路径',
    `storage_type`              varchar(255) NULL COMMENT '存储类型，旧记录为空',
    `file_size`                 varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '文件实际大小',
    `file_size_desc`            varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '文件大小展示字符',
    `file_suffix`               varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '文件后缀',
    `file_preview_content_type` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '文件预览的响应头Content-Type的值',
    `md5`                       varchar(255) COLLATE utf8mb4_bin                       NOT NULL DEFAULT '' COMMENT '文件唯一标识',
    `lifecycle_state`           varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '物理文件生命周期',
    `create_user`               bigint                                                 NOT NULL COMMENT '创建人',
    `create_time`               datetime                                               NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`file_id`) USING BTREE,
    INDEX `idx_md5` (`md5`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin
  ROW_FORMAT = DYNAMIC COMMENT ='物理文件信息表';

-- ----------------------------
-- Table structure for r_pan_share
-- ----------------------------
DROP TABLE IF EXISTS `r_pan_share`;
CREATE TABLE `r_pan_share`
(
    `share_id`       bigint(0)                                              NOT NULL COMMENT '分享id',
    `share_name`     varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '分享名称',
    `share_type`     tinyint(1)                                             NOT NULL DEFAULT 0 COMMENT '分享类型（0 有提取码）',
    `share_day_type` tinyint(1)                                             NOT NULL DEFAULT 0 COMMENT '分享类型（0 永久有效；1 7天有效；2 30天有效）',
    `share_day`      tinyint(1)                                             NOT NULL DEFAULT 0 COMMENT '分享有效天数（永久有效为0）',
    `share_end_time` datetime(0)                                            NOT NULL DEFAULT CURRENT_TIMESTAMP(0) COMMENT '分享结束时间',
    `share_url`      varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '分享链接地址',
    `share_code`     varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '分享提取码',
    `share_status`   tinyint(1)                                             NOT NULL DEFAULT 0 COMMENT '分享状态（0 正常；1 有文件被删除）',
    `create_user`    bigint(0)                                              NOT NULL COMMENT '分享创建人',
    `create_time`    datetime(0)                                            NOT NULL DEFAULT CURRENT_TIMESTAMP(0) COMMENT '创建时间',
    PRIMARY KEY (`share_id`) USING BTREE,
    UNIQUE INDEX `uk_create_user_time` (`create_user`, `create_time`) USING BTREE COMMENT '创建人、创建时间唯一索引'
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '用户分享表'
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for r_pan_share_file
-- ----------------------------
DROP TABLE IF EXISTS `r_pan_share_file`;
CREATE TABLE `r_pan_share_file`
(
    `id`          bigint(0)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `share_id`    bigint(0)   NOT NULL COMMENT '分享id',
    `file_id`     bigint(0)   NOT NULL COMMENT '文件记录ID',
    `create_user` bigint(0)   NOT NULL COMMENT '分享创建人',
    `create_time` datetime(0) NOT NULL DEFAULT CURRENT_TIMESTAMP(0) COMMENT '创建时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_share_id_file_id` (`share_id`, `file_id`) USING BTREE COMMENT '分享ID、文件ID联合唯一索引'
) ENGINE = InnoDB
  AUTO_INCREMENT = 1
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '用户分享文件表'
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for r_pan_user
-- ----------------------------
DROP TABLE IF EXISTS `r_pan_user`;
CREATE TABLE `r_pan_user`
(
    `user_id`     bigint(0)                                              NOT NULL COMMENT '用户id',
    `username`    varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '用户名',
    `password`    varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '密码',
    `salt`        varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '随机盐值',
    `question`    varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '密保问题',
    `answer`      varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '密保答案',
    `create_time` datetime(0)                                            NOT NULL DEFAULT CURRENT_TIMESTAMP(0) COMMENT '创建时间',
    `update_time` datetime(0)                                            NOT NULL DEFAULT CURRENT_TIMESTAMP(0) COMMENT '更新时间',
    PRIMARY KEY (`user_id`) USING BTREE,
    UNIQUE INDEX `uk_username` (`username`) USING BTREE COMMENT '用户名唯一索引'
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '用户信息表'
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for r_pan_user_file
-- ----------------------------
DROP TABLE IF EXISTS `r_pan_user_file`;
CREATE TABLE `r_pan_user_file`
(
    `space_bytes` BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑文件计费字节数，目录为0',
    `file_id`        bigint(0)                                              NOT NULL COMMENT '文件记录ID',
    `user_id`        bigint(0)                                              NOT NULL COMMENT '用户ID',
    `parent_id`      bigint(0)                                              NOT NULL COMMENT '上级文件夹ID,顶级文件夹为0',
    `real_file_id`   bigint(0)                                              NOT NULL DEFAULT 0 COMMENT '真实文件id',
    `filename`       varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '文件名',
    `folder_flag`    tinyint(1)                                             NOT NULL DEFAULT 0 COMMENT '是否是文件夹 （0 否 1 是）',
    `file_size_desc` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '文件大小展示字符',
    `file_type`      tinyint(1)                                             NOT NULL DEFAULT 0 COMMENT '文件类型（1 普通文件 2 压缩文件 3 excel 4 word 5 pdf 6 txt 7 图片 8 音频 9 视频 10 ppt 11 源码文件 12 csv）',
    `del_flag`       tinyint(1)                                             NOT NULL DEFAULT 0 COMMENT '删除标识（0 否 1 是）',
    `create_user`    bigint(0)                                              NOT NULL COMMENT '创建人',
    `create_time`    datetime(0)                                            NOT NULL DEFAULT CURRENT_TIMESTAMP(0) ON UPDATE CURRENT_TIMESTAMP(0) COMMENT '创建时间',
    `update_user`    bigint(0)                                              NOT NULL COMMENT '更新人',
    `update_time`    datetime(0)                                            NOT NULL DEFAULT CURRENT_TIMESTAMP(0) COMMENT '更新时间',
    PRIMARY KEY (`file_id`) USING BTREE,
    INDEX `idx_file_reference` (`real_file_id`, `user_id`, `del_flag`, `folder_flag`),
    INDEX `index_file_list` (`user_id`, `del_flag`, `parent_id`, `file_type`, `file_id`, `filename`, `folder_flag`,
                             `file_size_desc`, `create_time`, `update_time`) USING BTREE COMMENT '查询文件列表索引'
) ENGINE = InnoDB
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '用户文件信息表'
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for r_pan_user_search_history
-- ----------------------------
DROP TABLE IF EXISTS `r_pan_user_search_history`;
CREATE TABLE `r_pan_user_search_history`
(
    `id`             bigint(0)                                              NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        bigint(0)                                              NOT NULL COMMENT '用户id',
    `search_content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '搜索文案',
    `create_time`    datetime(0)                                            NOT NULL DEFAULT CURRENT_TIMESTAMP(0) COMMENT '创建时间',
    `update_time`    datetime(0)                                            NOT NULL DEFAULT CURRENT_TIMESTAMP(0) COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_user_id_search_content_update_time` (`user_id`, `search_content`, `update_time`) USING BTREE COMMENT '用户id、搜索内容和更新时间唯一索引',
    UNIQUE INDEX `uk_user_id_search_content` (`user_id`, `search_content`) USING BTREE COMMENT '用户id和搜索内容唯一索引'
) ENGINE = InnoDB
  AUTO_INCREMENT = 1
  CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '用户搜索历史表'
  ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
-- 物理文件清理任务；生产/已有库使用增量迁移，勿重跑本建表脚本。
DROP TABLE IF EXISTS r_pan_file_cleanup_task;
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

-- 新库上传任务表；已有数据库使用增量迁移。
DROP TABLE IF EXISTS r_pan_upload_task;
CREATE TABLE r_pan_upload_task (
    direct_upload BOOLEAN NOT NULL DEFAULT FALSE,
    multipart_id VARCHAR(255) DEFAULT NULL,
    artifacts_cleaned_at DATETIME(3) DEFAULT NULL COMMENT '上传中间数据清理完成时间',
    artifact_cleanup_error VARCHAR(1000) DEFAULT NULL COMMENT '上传中间数据清理失败原因',
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
    KEY idx_upload_artifact_gc(task_state,artifacts_cleaned_at,expires_at),
    KEY idx_upload_owner_state(user_id,task_state,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复上传任务；分片字节单独保存';

DROP TABLE IF EXISTS r_pan_upload_part;
CREATE TABLE r_pan_upload_part (
    upload_id VARCHAR(36) NOT NULL,
    part_number INT NOT NULL,
    md5 CHAR(32) NOT NULL,
    PRIMARY KEY (upload_id,part_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对象存储直传分片授权摘要';

-- User space accounts and upload reservations
-- Apply once before deploying quota-enabled code. Backfill follows in a separate migration.
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

-- Team workspace foundation for fresh databases.

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
    PRIMARY KEY(collection_id,user_id),
    INDEX idx_collection_invitee(user_id,active,collection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE r_pan_workspace_submission (
    upload_id VARCHAR(36) NOT NULL PRIMARY KEY,
    collection_id VARCHAR(36) NOT NULL,
    actor_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    filename VARCHAR(255) DEFAULT NULL,
    file_bytes BIGINT DEFAULT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_submission_file(file_id),
    INDEX idx_collection_submissions(collection_id,actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
