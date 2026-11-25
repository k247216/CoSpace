package com.rubin.rpan.modules.workspace;

import com.rubin.rpan.common.exception.RPanException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Service
@Transactional
@ConditionalOnProperty(name = "rpan.workspace.enabled", havingValue = "true")
public class WorkspaceCollections {
  private final JdbcTemplate jdbc;
  private final WorkspaceFiles files;
  private final WorkspaceUploads uploads;

  public WorkspaceCollections(JdbcTemplate jdbc, WorkspaceFiles files, WorkspaceUploads uploads) {
    this.jdbc = jdbc;
    this.files = files;
    this.uploads = uploads;
  }

  public record Destination(long workspace, long parent) {}

  public record Submission(
      String uploadId,
      @JsonSerialize(using = ToStringSerializer.class) long actor,
      @JsonSerialize(using = ToStringSerializer.class) long fileId,
      Instant submittedAt,
      String username,
      String filename,
      Long bytes,
      boolean available) {}

  public record Collection(
      String id,
      @JsonSerialize(using = ToStringSerializer.class) long workspaceId,
      @JsonSerialize(using = ToStringSerializer.class) long parentId,
      String title,
      Instant deadline,
      long maxFileBytes,
      String allowedSuffix,
      boolean closed,
      boolean canManage,
      boolean canSubmit,
      int invitedCount,
      int submittedPeople,
      int submittedFiles) {}

  public record Recipient(
      @JsonSerialize(using = ToStringSerializer.class) long id, String username) {}

  public record Participant(
      @JsonSerialize(using = ToStringSerializer.class) long id,
      String username,
      boolean active,
      int submittedCount,
      Instant lastSubmittedAt) {}

  public record Received(
      String id,
      String title,
      String workspaceName,
      Instant deadline,
      boolean closed,
      boolean invited,
      boolean canSubmit,
      int submittedCount) {}

  public List<Recipient> recipients(long workspace, long actor, String query) {
    manage(workspace, actor);
    String search = query == null ? "" : query.trim();
    if (search.length() > 255) throw new RPanException("查询内容过长");
    Long userId = null;
    try {
      userId = Long.valueOf(search);
    } catch (NumberFormatException ignored) {
    }
    return jdbc.query(
        """
        SELECT u.user_id,u.username FROM r_pan_user u
        LEFT JOIN r_pan_workspace_member m ON m.workspace_id=? AND m.user_id=u.user_id
        WHERE (m.user_id IS NULL OR (m.active=TRUE AND m.role<>'VIEWER')) AND
          ((m.active=TRUE AND (?='' OR LOCATE(?,u.username)>0)) OR
           (?<>'' AND (u.username=? OR u.user_id=?)))
        ORDER BY u.username,u.user_id LIMIT 100
        """,
        (rs, row) -> new Recipient(rs.getLong(1), rs.getString(2)),
        workspace,
        search,
        search,
        search,
        search,
        userId);
  }

  public List<Participant> invitees(long workspace, long actor, String collection) {
    manage(workspace, actor);
    requireCollection(workspace, collection);
    return jdbc.query(
        """
SELECT u.user_id,u.username,i.active,COUNT(s.upload_id) AS submitted_count,MAX(s.submitted_at) AS last_submitted
FROM r_pan_workspace_collection_invite i
JOIN r_pan_user u ON u.user_id=i.user_id
LEFT JOIN r_pan_workspace_submission s ON s.collection_id=i.collection_id AND s.actor_id=i.user_id
WHERE i.collection_id=? GROUP BY u.user_id,u.username,i.active ORDER BY u.username,u.user_id
""",
        (rs, row) ->
            new Participant(
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getBoolean("active"),
                rs.getInt("submitted_count"),
                rs.getTimestamp("last_submitted") == null
                    ? null
                    : rs.getTimestamp("last_submitted").toInstant()),
        collection);
  }

  public List<Received> received(long actor) {
    return jdbc.query(
        """
SELECT c.collection_id,c.title,w.name,c.deadline,c.closed,i.active,
  (m.user_id IS NULL OR (m.active=TRUE AND m.role<>'VIEWER')) AS eligible,
  (SELECT COUNT(*) FROM r_pan_workspace_submission s
   WHERE s.collection_id=c.collection_id AND s.actor_id=i.user_id) AS submitted_count
FROM r_pan_workspace_collection_invite i
JOIN r_pan_workspace_collection c ON c.collection_id=i.collection_id
JOIN r_pan_workspace w ON w.workspace_id=c.workspace_id
LEFT JOIN r_pan_workspace_member m ON m.workspace_id=c.workspace_id AND m.user_id=i.user_id
WHERE i.user_id=? AND (i.active=TRUE OR EXISTS
  (SELECT 1 FROM r_pan_workspace_submission s WHERE s.collection_id=c.collection_id AND s.actor_id=i.user_id))
ORDER BY c.deadline DESC,c.collection_id
""",
        (rs, row) -> {
          Instant deadline = rs.getTimestamp("deadline").toInstant();
          boolean closed = rs.getBoolean("closed");
          boolean invited = rs.getBoolean("active");
          return new Received(
              rs.getString("collection_id"),
              rs.getString("title"),
              rs.getString("name"),
              deadline,
              closed,
              invited,
              invited && rs.getBoolean("eligible") && !closed && deadline.isAfter(Instant.now()),
              rs.getInt("submitted_count"));
        },
        actor);
  }

  public String create(
      long workspace,
      long actor,
      long parent,
      String title,
      Instant deadline,
      long maxBytes,
      String suffix,
      List<Long> recipients) {
    String id = create(workspace, actor, parent, title, deadline, maxBytes, suffix);
    inviteMany(workspace, actor, id, recipients);
    return id;
  }

  public void inviteMany(long workspace, long actor, String collection, List<Long> recipients) {
    manage(workspace, actor);
    requireCollection(workspace, collection);
    if (recipients == null
        || recipients.isEmpty()
        || recipients.size() > 100
        || recipients.stream().anyMatch(java.util.Objects::isNull))
      throw new RPanException("请选择 1 至 100 位提交人");
    var unique = new LinkedHashSet<>(recipients);
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(workspace);
    arguments.addAll(unique);
    String placeholders = String.join(",", java.util.Collections.nCopies(unique.size(), "?"));
    int eligible =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM r_pan_user u
            LEFT JOIN r_pan_workspace_member m ON m.workspace_id=? AND m.user_id=u.user_id
            WHERE (m.user_id IS NULL OR (m.active=TRUE AND m.role<>'VIEWER')) AND u.user_id IN (
            """
                + placeholders
                + ")",
            Integer.class,
            arguments.toArray());
    if (eligible != unique.size()) throw new RPanException("提交人不存在或没有提交资格");
    for (long recipient : unique) setInvitation(collection, recipient, true);
  }

  private static final String COLLECTION_SELECT =
      """
SELECT c.*,
  (SELECT COUNT(*) FROM r_pan_workspace_collection_invite i WHERE i.collection_id=c.collection_id AND i.active=TRUE) AS invited_count,
  (SELECT COUNT(*) FROM r_pan_workspace_collection_invite i WHERE i.collection_id=c.collection_id AND i.active=TRUE
    AND EXISTS (SELECT 1 FROM r_pan_workspace_submission s WHERE s.collection_id=c.collection_id AND s.actor_id=i.user_id)) AS submitted_people,
  (SELECT COUNT(*) FROM r_pan_workspace_submission s WHERE s.collection_id=c.collection_id) AS submitted_files
FROM r_pan_workspace_collection c
""";

  public List<Collection> list(long workspace, long actor) {
    manage(workspace, actor);
    return jdbc.query(
        COLLECTION_SELECT + " WHERE c.workspace_id=? ORDER BY c.deadline DESC",
        (rs, row) ->
            new Collection(
                rs.getString("collection_id"),
                rs.getLong("workspace_id"),
                rs.getLong("parent_id"),
                rs.getString("title"),
                rs.getTimestamp("deadline").toInstant(),
                rs.getLong("max_file_bytes"),
                rs.getString("allowed_suffix"),
                rs.getBoolean("closed"),
                true,
                false,
                rs.getInt("invited_count"),
                rs.getInt("submitted_people"),
                rs.getInt("submitted_files")),
        workspace);
  }

  public Collection detail(String collection, long actor) {
    var spaces =
        jdbc.queryForList(
            "SELECT workspace_id FROM r_pan_workspace_collection WHERE collection_id=?",
            Long.class,
            collection);
    if (spaces.isEmpty()) throw new RPanException("收集任务不存在");
    long workspace = spaces.getFirst();
    uploads.lock(workspace);
    var members =
        jdbc.queryForList(
            "SELECT role,active FROM r_pan_workspace_member WHERE workspace_id=? AND user_id=?",
            workspace,
            actor);
    boolean activeMember =
        !members.isEmpty() && Boolean.TRUE.equals(members.getFirst().get("active"));
    WorkspaceRole role =
        members.isEmpty() ? null : WorkspaceRole.valueOf((String) members.getFirst().get("role"));
    boolean manager = activeMember && role.managesMembers();
    boolean eligible = members.isEmpty() || (activeMember && role != WorkspaceRole.VIEWER);
    boolean invited =
        !jdbc.queryForList(
                """
                SELECT user_id FROM r_pan_workspace_collection_invite
                WHERE collection_id=? AND user_id=? AND active=TRUE
                """,
                Long.class,
                collection,
                actor)
            .isEmpty();
    if (!manager
        && !invited
        && jdbc.queryForObject(
                """
SELECT COUNT(*) FROM r_pan_workspace_submission WHERE collection_id=? AND actor_id=?
""",
                Integer.class,
                collection,
                actor)
            == 0) throw new RPanException("未被邀请参加此收集");
    return jdbc.queryForObject(
        COLLECTION_SELECT + " WHERE c.collection_id=? AND c.workspace_id=?",
        (rs, row) -> {
          Instant deadline = rs.getTimestamp("deadline").toInstant();
          boolean closed = rs.getBoolean("closed");
          boolean canSubmit = invited && eligible && !closed && deadline.isAfter(Instant.now());
          return new Collection(
              collection,
              workspace,
              rs.getLong("parent_id"),
              rs.getString("title"),
              deadline,
              rs.getLong("max_file_bytes"),
              rs.getString("allowed_suffix"),
              closed,
              manager,
              canSubmit,
              manager ? rs.getInt("invited_count") : 0,
              manager ? rs.getInt("submitted_people") : 0,
              manager ? rs.getInt("submitted_files") : 0);
        },
        collection,
        workspace);
  }

  public String create(
      long workspace,
      long actor,
      long parent,
      String title,
      Instant deadline,
      long maxBytes,
      String suffix) {
    manage(workspace, actor);
    uploads.validateParent(workspace, parent);
    if (title == null
        || title.isBlank()
        || title.length() > 100
        || deadline == null
        || !deadline.isAfter(Instant.now())
        || maxBytes <= 0
        || maxBytes > 3221225472L
        || suffix == null
        || !suffix.matches("(?:\\.[a-zA-Z0-9]{1,16})?")) throw new RPanException("收集名称、期限或文件限制不合法");
    String id = UUID.randomUUID().toString();
    jdbc.update(
        """
INSERT INTO r_pan_workspace_collection(collection_id,workspace_id,parent_id,creator_id,title,
deadline,max_file_bytes,allowed_suffix) VALUES(?,?,?,?,?,?,?,?)
""",
        id,
        workspace,
        parent,
        actor,
        title,
        Timestamp.from(deadline),
        maxBytes,
        suffix.toLowerCase(java.util.Locale.ROOT));
    return id;
  }

  public void invite(
      long workspace, long actor, String collection, long recipient, boolean active) {
    manage(workspace, actor);
    requireCollection(workspace, collection);
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM r_pan_user WHERE user_id=?", Integer.class, recipient)
        != 1) throw new RPanException("受邀用户不存在");
    setInvitation(collection, recipient, active);
  }

  private void setInvitation(String collection, long recipient, boolean active) {
    var rows =
        jdbc.queryForList(
            """
SELECT active FROM r_pan_workspace_collection_invite WHERE collection_id=? AND user_id=? FOR UPDATE
""",
            Boolean.class,
            collection,
            recipient);
    if (rows.isEmpty()) {
      jdbc.update(
          "INSERT INTO r_pan_workspace_collection_invite(collection_id,user_id,active)"
              + " VALUES(?,?,?)",
          collection,
          recipient,
          active);
    } else if (rows.getFirst() != active) {
      jdbc.update(
          """
          UPDATE r_pan_workspace_collection_invite SET active=?,generation=generation+1
          WHERE collection_id=? AND user_id=?
          """,
          active,
          collection,
          recipient);
    }
  }

  public void close(long workspace, long actor, String collection) {
    manage(workspace, actor);
    requireCollection(workspace, collection);
    jdbc.update(
        "UPDATE r_pan_workspace_collection SET closed=TRUE WHERE collection_id=?", collection);
  }

  public Destination destination(String collection, long actor) {
    var rows =
        jdbc.query(
            "SELECT workspace_id,parent_id FROM r_pan_workspace_collection WHERE collection_id=?",
            (rs, row) -> new Destination(rs.getLong(1), rs.getLong(2)),
            collection);
    if (rows.isEmpty()) throw new RPanException("收集任务不存在");
    var destination = rows.getFirst();
    uploads.lock(destination.workspace());
    var invited =
        jdbc.queryForList(
            """
SELECT user_id FROM r_pan_workspace_collection_invite WHERE collection_id=? AND user_id=? AND active=TRUE
""",
            Long.class,
            collection,
            actor);
    if (invited.isEmpty()) throw new RPanException("未被邀请参加此收集");
    requireCollection(destination.workspace(), collection);
    return destination;
  }

  public List<Submission> submissions(String collection, long actor, boolean admin) {
    var rows =
        jdbc.queryForList(
            "SELECT workspace_id FROM r_pan_workspace_collection WHERE collection_id=?",
            Long.class,
            collection);
    if (rows.isEmpty()) throw new RPanException("收集任务不存在");
    if (admin) manage(rows.getFirst(), actor);
    // An invited user can still inspect their own submitted results after closing or invitation
    // revocation.
    return jdbc.query(
        """
        SELECT s.*,u.username,(f.file_id IS NOT NULL AND f.del_flag=0) AS available
        FROM r_pan_workspace_submission s LEFT JOIN r_pan_user u ON u.user_id=s.actor_id
        LEFT JOIN r_pan_user_file f ON f.file_id=s.file_id
        WHERE s.collection_id=? AND (?=TRUE OR s.actor_id=?) ORDER BY s.submitted_at,s.upload_id
        """,
        (rs, row) ->
            new Submission(
                rs.getString("upload_id"),
                rs.getLong("actor_id"),
                rs.getLong("file_id"),
                rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("username"),
                rs.getString("filename"),
                rs.getObject("file_bytes", Long.class),
                rs.getBoolean("available")),
        collection,
        admin,
        actor);
  }

  private void manage(long workspace, long actor) {
    if (!files.access(workspace, actor).managesMembers()) throw new RPanException("需要团队管理权限");
  }

  private void requireCollection(long workspace, String collection) {
    if (jdbc.queryForList(
            "SELECT collection_id FROM r_pan_workspace_collection WHERE collection_id=? AND"
                + " workspace_id=? FOR UPDATE",
            String.class,
            collection,
            workspace)
        .isEmpty()) throw new RPanException("收集任务不属于此空间");
  }
}
