-- Recipient inbox queries start from user_id; the primary key starts from collection_id.
CREATE INDEX idx_collection_invitee ON r_pan_workspace_collection_invite(user_id,active,collection_id);
