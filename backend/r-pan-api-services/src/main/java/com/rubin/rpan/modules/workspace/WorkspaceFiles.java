package com.rubin.rpan.modules.workspace;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.service.impl.FileLifecycleService;
import com.rubin.rpan.util.IdGenerator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Service
@Transactional
@ConditionalOnProperty(name = "rpan.workspace.enabled", havingValue = "true")
public class WorkspaceFiles {
  private final JdbcTemplate jdbc;
  private final WorkspaceUploads uploads;
  private final FileLifecycleService lifecycle;

  public WorkspaceFiles(
      JdbcTemplate jdbc, WorkspaceUploads uploads, FileLifecycleService lifecycle) {
    this.jdbc = jdbc;
    this.uploads = uploads;
    this.lifecycle = lifecycle;
  }

  public record Node(
      @JsonSerialize(using = ToStringSerializer.class) long id,
      @JsonSerialize(using = ToStringSerializer.class) long parentId,
      String name,
      boolean folder,
      boolean deleted,
      long bytes,
      @JsonSerialize(using = ToStringSerializer.class) long creator,
      String creatorName,
      String collectionId,
      String collectionTitle,
      Instant createdAt) {
    public Node(
        long id,
        long parentId,
        String name,
        boolean folder,
        boolean deleted,
        long bytes,
        long creator) {
      this(id, parentId, name, folder, deleted, bytes, creator, null, null, null, null);
    }
  }

  private static final String FILE_SELECT =
      """
      SELECT f.*,u.username AS creator_name,s.collection_id,c.title AS collection_title
      FROM r_pan_user_file f LEFT JOIN r_pan_user u ON u.user_id=f.create_user
      LEFT JOIN r_pan_workspace_submission s ON s.file_id=f.file_id
      LEFT JOIN r_pan_workspace_collection c ON c.collection_id=s.collection_id
      """;

  private static Node node(ResultSet rs) throws SQLException {
    return new Node(
        rs.getLong("file_id"),
        rs.getLong("parent_id"),
        rs.getString("filename"),
        rs.getInt("folder_flag") == 1,
        rs.getInt("del_flag") == 1,
        rs.getLong("space_bytes"),
        rs.getLong("create_user"),
        rs.getString("creator_name"),
        rs.getString("collection_id"),
        rs.getString("collection_title"),
        rs.getTimestamp("create_time").toInstant());
  }

  public WorkspaceRole access(long workspace, long actor) {
    uploads.lock(workspace);
    return readRole(workspace, actor);
  }

  private WorkspaceRole readRole(long workspace, long actor) {
    var rows =
        jdbc.queryForList(
            """
SELECT m.role FROM r_pan_workspace_member m JOIN r_pan_workspace w ON w.workspace_id=m.workspace_id
WHERE m.workspace_id=? AND m.user_id=? AND m.active=TRUE
""",
            String.class,
            workspace,
            actor);
    if (rows.isEmpty()) throw new RPanException("工作空间不存在或无访问权限");
    return WorkspaceRole.valueOf(rows.getFirst());
  }

  @Transactional(readOnly = true)
  public List<Node> list(long workspace, long actor, long parent, boolean recycle) {
    readRole(workspace, actor);
    if (!recycle) uploads.validateReadableParent(workspace, parent);
    return jdbc.query(
        FILE_SELECT
            + """
               WHERE f.workspace_id=? AND f.parent_id=? AND f.del_flag=?
               ORDER BY f.folder_flag DESC,f.file_id
              """,
        (rs, row) -> node(rs),
        workspace,
        parent,
        recycle ? 1 : 0);
  }

  @Transactional(readOnly = true)
  public List<Node> search(long workspace, long actor, String keyword) {
    readRole(workspace, actor);
    if (keyword == null || keyword.isBlank() || keyword.length() > 255)
      throw new RPanException("请输入有效搜索内容");
    var results =
        jdbc.query(
            FILE_SELECT
                + """
                   WHERE f.workspace_id=? AND f.del_flag=0 AND LOCATE(?,f.filename)>0
                   ORDER BY f.file_id LIMIT 100
                  """,
            (rs, row) -> node(rs),
            workspace,
            keyword);
    for (var result : results) uploads.validateReadableParent(workspace, result.parentId());
    return results;
  }

  public long folder(long workspace, long actor, long parent, String name) {
    if (access(workspace, actor) == WorkspaceRole.VIEWER) throw denied();
    uploads.validateParent(workspace, parent);
    validName(name);
    long id = IdGenerator.nextId();
    jdbc.update(
        """
INSERT INTO r_pan_user_file(file_id,user_id,workspace_id,parent_id,real_file_id,filename,
folder_flag,del_flag,file_type,file_size_desc,space_bytes,create_user,update_user,create_time,update_time)
VALUES(?,0,?,?,0,?,1,0,0,'',0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
""",
        id,
        workspace,
        parent,
        name,
        actor,
        actor);
    return id;
  }

  @Transactional(readOnly = true)
  public long downloadable(long workspace, long actor, long id) {
    readRole(workspace, actor);
    var node = require(workspace, id, false);
    if (node.deleted() || node.folder()) throw denied();
    uploads.validateReadableParent(workspace, node.parentId());
    return node.id();
  }

  public void update(long workspace, long actor, long id, String name, Long parent) {
    var node = writable(workspace, actor, id);
    if (node.deleted()) throw denied();
    uploads.validateParent(workspace, node.parentId());
    validName(name);
    long destination = parent == null ? node.parentId() : parent;
    uploads.validateParent(workspace, destination);
    // Every ancestor belongs to this space. Reject moving a directory under itself.
    long cursor = destination;
    while (cursor != workspace) {
      if (cursor == id) throw new RPanException("目录不能移动到自身子目录");
      cursor = require(workspace, cursor).parentId();
    }
    jdbc.update(
        "UPDATE r_pan_user_file SET filename=?,parent_id=?,update_user=? WHERE workspace_id=? AND"
            + " file_id=?",
        name,
        destination,
        actor,
        workspace,
        id);
  }

  public void recycle(long workspace, long actor, long id, boolean restore, boolean purge) {
    var node = writable(workspace, actor, id);
    if (purge && !node.deleted()) throw new RPanException("请先将文件移入回收站");
    if (restore && node.deleted()) {
      uploads.validateParent(workspace, node.parentId());
      jdbc.update(
          "UPDATE r_pan_user_file SET del_flag=0 WHERE workspace_id=? AND file_id=?",
          workspace,
          id);
    }
    if (node.folder()) {
      var children =
          jdbc.queryForList(
              "SELECT file_id FROM r_pan_user_file WHERE workspace_id=? AND parent_id=? ORDER BY"
                  + " file_id",
              Long.class,
              workspace,
              id);
      for (long child : children) {
        var descendant = require(workspace, child);
        Long group =
            jdbc.queryForObject(
                "SELECT recycle_root_id FROM r_pan_user_file WHERE workspace_id=? AND file_id=?",
                Long.class,
                workspace,
                child);
        if (purge
            || (!restore && !descendant.deleted())
            || (restore && group != null && group == id)) {
          recycle(workspace, actor, child, restore, purge);
          if (!purge && !restore)
            jdbc.update(
                "UPDATE r_pan_user_file SET recycle_root_id=? WHERE workspace_id=? AND file_id=?",
                id,
                workspace,
                child);
        }
      }
    }
    if (purge) {
      if (!node.deleted()) throw new RPanException("请先将文件移入回收站");
      var physical =
          jdbc.queryForObject(
              "SELECT real_file_id FROM r_pan_user_file WHERE workspace_id=? AND file_id=?",
              Long.class,
              workspace,
              id);
      if (!node.folder()) lifecycle.lockActiveFiles(List.of(physical));
      jdbc.update("DELETE FROM r_pan_user_file WHERE workspace_id=? AND file_id=?", workspace, id);
      if (jdbc.update(
              "UPDATE r_pan_workspace SET used_bytes=used_bytes-? WHERE workspace_id=? AND"
                  + " used_bytes>=?",
              node.bytes(),
              workspace,
              node.bytes())
          != 1) throw new IllegalStateException("团队容量账目不一致");
      if (!node.folder()) lifecycle.scheduleIfUnreferenced(List.of(physical));
    } else {
      if (restore || !node.deleted()) uploads.validateParent(workspace, node.parentId());
      jdbc.update(
          "UPDATE r_pan_user_file SET del_flag=?,update_user=?,recycle_root_id=NULL WHERE"
              + " workspace_id=? AND file_id=?",
          restore ? 0 : 1,
          actor,
          workspace,
          id);
    }
  }

  private Node writable(long workspace, long actor, long id) {
    var role = access(workspace, actor);
    var node = require(workspace, id);
    if (role == WorkspaceRole.VIEWER
        || (role == WorkspaceRole.MEMBER
            && (node.creator() != actor || node.folder() && childCount(workspace, id) > 0)))
      throw denied();
    return node;
  }

  private Node require(long workspace, long id) {
    return require(workspace, id, true);
  }

  private Node require(long workspace, long id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT * FROM r_pan_user_file WHERE workspace_id=? AND file_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, row) ->
                new Node(
                    rs.getLong("file_id"),
                    rs.getLong("parent_id"),
                    rs.getString("filename"),
                    rs.getInt("folder_flag") == 1,
                    rs.getInt("del_flag") == 1,
                    rs.getLong("space_bytes"),
                    rs.getLong("create_user")),
            workspace,
            id);
    if (rows.isEmpty()) throw denied();
    return rows.getFirst();
  }

  private int childCount(long workspace, long id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM r_pan_user_file WHERE workspace_id=? AND parent_id=?",
        Integer.class,
        workspace,
        id);
  }

  private static void validName(String name) {
    if (name == null
        || name.isBlank()
        || name.length() > 255
        || name.equals(".")
        || name.equals("..")
        || name.contains("/")
        || name.contains("\\")
        || name.chars().anyMatch(Character::isISOControl)) throw new RPanException("文件名称不合法");
  }

  private static RPanException denied() {
    return new RPanException("文件不存在或无操作权限");
  }
}
