-- 仅为已有数据库添加索引，不运行含 DROP TABLE 的 db.sql。
-- 执行前 SHOW INDEX FROM 对应表；已有同名索引时跳过该条。
ALTER TABLE r_pan_file ADD INDEX idx_md5 (md5);
ALTER TABLE r_pan_user_file ADD INDEX idx_file_reference (real_file_id, user_id, del_flag, folder_flag);
