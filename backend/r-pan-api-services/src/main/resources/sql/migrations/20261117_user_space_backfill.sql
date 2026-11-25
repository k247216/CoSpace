-- Run once with writers stopped, after schema migration and invalid-size checks.
-- Recycle-bin records are deliberately included. See scripts/migrate-user-space.py.
UPDATE r_pan_user_file u SET space_bytes = COALESCE(
    (SELECT CAST(p.file_size AS SIGNED) FROM r_pan_file p WHERE p.file_id=u.real_file_id),0)
WHERE u.folder_flag=0;

INSERT INTO r_pan_upload_reservation(upload_id,user_id,request_id,bytes,state)
SELECT upload_id,user_id,upload_id,file_size,
    CASE WHEN task_state='COMPLETED' THEN 'COMMITTED'
         WHEN task_state IN ('FAILED','EXPIRED') THEN 'RELEASED' ELSE 'RESERVED' END
FROM r_pan_upload_task;

-- Existing users are grandfathered only if their current occupancy exceeds the default.
-- Every owner is included, even historical rows whose user record is no longer present.
INSERT INTO r_pan_user_space(user_id,total_bytes,used_bytes,reserved_bytes)
SELECT owners.user_id,
    GREATEST(10737418240,COALESCE(used.used_bytes,0)+COALESCE(reserved.reserved_bytes,0)),
    COALESCE(used.used_bytes,0),COALESCE(reserved.reserved_bytes,0)
FROM (
    SELECT user_id FROM r_pan_user
    UNION SELECT user_id FROM r_pan_user_file
    UNION SELECT user_id FROM r_pan_upload_task
) owners
LEFT JOIN (SELECT user_id,SUM(space_bytes) AS used_bytes FROM r_pan_user_file GROUP BY user_id) used ON used.user_id=owners.user_id
LEFT JOIN (SELECT user_id,SUM(bytes) AS reserved_bytes FROM r_pan_upload_reservation WHERE state='RESERVED' GROUP BY user_id) reserved ON reserved.user_id=owners.user_id;
