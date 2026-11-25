-- Legacy physical rows have no backend ID. Keep NULL and route them through the legacy storage configuration.
ALTER TABLE r_pan_file ADD COLUMN storage_type VARCHAR(255) NULL AFTER real_path;
