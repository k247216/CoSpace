package com.rubin.rpan.modules.workspace;

import com.rubin.rpan.common.exception.RPanException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@ConditionalOnProperty(name = "rpan.workspace.enabled", havingValue = "true")
public class WorkspaceSharing {
  private final JdbcTemplate jdbc;
  private final WorkspaceFiles files;
  private final WorkspaceUploads uploads;

  public WorkspaceSharing(JdbcTemplate jdbc, WorkspaceFiles files, WorkspaceUploads uploads) {
    this.jdbc = jdbc;
    this.files = files;
    this.uploads = uploads;
  }

  public record Share(
      String id, String fileId, String recipientId, Instant expiresAt, boolean revoked) {}

  public java.util.List<Share> list(long workspace, long actor) {
    if (!files.access(workspace, actor).managesMembers()) throw denied();
    return jdbc.query(
        "SELECT * FROM r_pan_workspace_share WHERE workspace_id=? ORDER BY expires_at DESC",
        (rs, row) ->
            new Share(
                rs.getString("share_id"),
                rs.getString("file_id"),
                rs.getString("recipient_id"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getBoolean("revoked")),
        workspace);
  }

  public String create(long workspace, long actor, long file, long recipient, Instant expires) {
    if (!files.access(workspace, actor).managesMembers()) throw denied();
    files.downloadable(workspace, actor, file);
    if (expires == null
        || !expires.isAfter(Instant.now())
        || recipient <= 0
        || jdbc.queryForObject(
                "SELECT COUNT(*) FROM r_pan_user WHERE user_id=?", Integer.class, recipient)
            != 1) throw new RPanException("接收人或到期时间不合法");
    String id = UUID.randomUUID().toString();
    jdbc.update(
        """
INSERT INTO r_pan_workspace_share(share_id,workspace_id,file_id,recipient_id,creator_id,expires_at)
VALUES(?,?,?,?,?,?)
""",
        id,
        workspace,
        file,
        recipient,
        actor,
        Timestamp.from(expires));
    return id;
  }

  public void revoke(long workspace, long actor, String share) {
    if (!files.access(workspace, actor).managesMembers()) throw denied();
    if (jdbc.update(
            "UPDATE r_pan_workspace_share SET revoked=TRUE WHERE workspace_id=? AND share_id=?",
            workspace,
            share)
        != 1) throw denied();
  }

  public long downloadable(String share, long recipient) {
    // Find the space first, then follow the workspace -> share -> file lock order.
    var spaces =
        jdbc.queryForList(
            "SELECT workspace_id FROM r_pan_workspace_share WHERE share_id=?", Long.class, share);
    if (spaces.isEmpty()) throw denied();
    long workspace = spaces.getFirst();
    jdbc.queryForList(
        "SELECT workspace_id FROM r_pan_workspace WHERE workspace_id=? FOR UPDATE", workspace);
    var rows =
        jdbc.queryForList(
            """
SELECT file_id FROM r_pan_workspace_share WHERE share_id=? AND recipient_id=? AND revoked=FALSE
  AND expires_at>CURRENT_TIMESTAMP FOR UPDATE
""",
            Long.class,
            share,
            recipient);
    if (rows.isEmpty()) throw denied();
    long id = rows.getFirst();
    var parents =
        jdbc.queryForList(
            """
            SELECT parent_id FROM r_pan_user_file WHERE workspace_id=? AND file_id=? AND del_flag=0
              AND folder_flag=0 FOR UPDATE
            """,
            Long.class,
            workspace,
            id);
    if (parents.isEmpty()) throw denied();
    uploads.validateParent(workspace, parents.getFirst());
    return id;
  }

  private static RPanException denied() {
    return new RPanException("分享不可用或未授权给当前用户");
  }
}
