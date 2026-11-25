-- Preserve the original submission even after a logical file is renamed or purged.
ALTER TABLE r_pan_workspace_submission ADD COLUMN filename VARCHAR(255) DEFAULT NULL;
ALTER TABLE r_pan_workspace_submission ADD COLUMN file_bytes BIGINT DEFAULT NULL;
UPDATE r_pan_workspace_submission SET
    filename = COALESCE(filename, (SELECT t.filename FROM r_pan_upload_task t WHERE t.upload_id=r_pan_workspace_submission.upload_id),
                        (SELECT f.filename FROM r_pan_user_file f WHERE f.file_id=r_pan_workspace_submission.file_id)),
    file_bytes = COALESCE(file_bytes, (SELECT t.file_size FROM r_pan_upload_task t WHERE t.upload_id=r_pan_workspace_submission.upload_id),
                          (SELECT f.space_bytes FROM r_pan_user_file f WHERE f.file_id=r_pan_workspace_submission.file_id))
WHERE filename IS NULL OR file_bytes IS NULL;
CREATE UNIQUE INDEX uk_submission_file ON r_pan_workspace_submission(file_id);
