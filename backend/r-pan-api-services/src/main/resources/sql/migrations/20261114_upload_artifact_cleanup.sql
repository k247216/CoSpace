-- 上传暂存清理：保留任务记录追踪清理完成与失败；不删除已有业务数据。
ALTER TABLE r_pan_upload_task
    ADD COLUMN artifacts_cleaned_at DATETIME(3) DEFAULT NULL COMMENT '分片、合并临时文件和Bitmap清理完成时间';
ALTER TABLE r_pan_upload_task
    ADD COLUMN artifact_cleanup_error VARCHAR(1000) DEFAULT NULL COMMENT '清理失败原因，与上传失败原因分开';
CREATE INDEX idx_upload_artifact_gc ON r_pan_upload_task(task_state,artifacts_cleaned_at,expires_at);
