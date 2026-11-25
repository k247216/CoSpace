package com.rubin.rpan.modules.workspace;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.entity.RPanUploadTask;
import com.rubin.rpan.modules.file.entity.RPanUserFile;
import com.rubin.rpan.modules.file.quota.model.UploadReservation;
import com.rubin.rpan.util.FileUtil;
import java.util.HashSet;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@ConditionalOnProperty(name = "rpan.workspace.enabled", havingValue = "true")
public class WorkspaceUploads {
  private final JdbcTemplate jdbc;

  public WorkspaceUploads(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lock(long workspace) {
    if (jdbc.queryForList(
            "SELECT workspace_id FROM r_pan_workspace WHERE workspace_id=? FOR UPDATE", workspace)
        .isEmpty()) throw new RPanException("工作空间不存在或不可用");
  }

  public long authorize(long workspace, long actor) {
    lock(workspace);
    var members =
        jdbc.queryForList(
            """
            SELECT role,generation FROM r_pan_workspace_member
            WHERE workspace_id=? AND user_id=? AND active=TRUE FOR UPDATE
            """,
            workspace,
            actor);
    if (members.isEmpty() || "VIEWER".equals(members.getFirst().get("role")))
      throw new RPanException("没有团队上传权限");
    return ((Number) members.getFirst().get("generation")).longValue();
  }

  public long initializeEligibility(RPanUploadTask task) {
    long generation = eligibility(task);
    validateParent(task.getWorkspaceId(), task.getParentId());
    return generation;
  }

  private long eligibility(RPanUploadTask task) {
    if (task.getCollectionId() == null) return authorize(task.getWorkspaceId(), task.getUserId());
    lock(task.getWorkspaceId());
    var collections =
        jdbc.queryForList(
            """
            SELECT * FROM r_pan_workspace_collection WHERE collection_id=? AND workspace_id=?
              AND closed=FALSE AND deadline>CURRENT_TIMESTAMP FOR UPDATE
            """,
            task.getCollectionId(),
            task.getWorkspaceId());
    if (collections.isEmpty()) throw new RPanException("收集任务已关闭或到期");
    var collection = collections.getFirst();
    if (((Number) collection.get("parent_id")).longValue() != task.getParentId()
        || task.getFileSize() > ((Number) collection.get("max_file_bytes")).longValue())
      throw new RPanException("文件不符合收集目录或大小限制");
    String suffix = (String) collection.get("allowed_suffix");
    if (!suffix.isEmpty()
        && !task.getFilename().toLowerCase(java.util.Locale.ROOT).endsWith(suffix))
      throw new RPanException("文件类型不符合收集限制");
    var members =
        jdbc.queryForList(
            "SELECT role,generation,active FROM r_pan_workspace_member WHERE workspace_id=? AND"
                + " user_id=?",
            task.getWorkspaceId(),
            task.getUserId());
    long memberGeneration = 0;
    if (!members.isEmpty()) {
      var member = members.getFirst();
      if (!Boolean.TRUE.equals(member.get("active")) || "VIEWER".equals(member.get("role")))
        throw new RPanException("团队成员提交资格不可用");
      memberGeneration = ((Number) member.get("generation")).longValue();
    }
    if (task.getMembershipGeneration() == null)
      task.setCollectionMemberGeneration(memberGeneration);
    else if (!Objects.equals(task.getCollectionMemberGeneration(), memberGeneration))
      throw new RPanException("团队资格已变化，请重新提交");
    var invites =
        jdbc.queryForList(
            """
SELECT generation FROM r_pan_workspace_collection_invite WHERE collection_id=? AND user_id=?
  AND active=TRUE FOR UPDATE
""",
            Long.class,
            task.getCollectionId(),
            task.getUserId());
    if (invites.isEmpty()) throw new RPanException("收集提交资格已撤销");
    return invites.getFirst();
  }

  public void check(RPanUploadTask task) {
    long generation = eligibility(task);
    if (!Objects.equals(task.getMembershipGeneration(), generation))
      throw new RPanException("上传资格已变化，请取消旧任务后重新上传");
    validateParent(task.getWorkspaceId(), task.getParentId());
  }

  public void validateParent(long workspace, long parent) {
    validateParent(workspace, parent, true);
  }

  public void validateReadableParent(long workspace, long parent) {
    validateParent(workspace, parent, false);
  }

  private void validateParent(long workspace, long parent, boolean lock) {
    var visited = new HashSet<Long>();
    while (parent != workspace && visited.add(parent)) {
      var rows =
          jdbc.queryForList(
              """
              SELECT parent_id FROM r_pan_user_file
              WHERE workspace_id=? AND file_id=? AND folder_flag=1 AND del_flag=0
              """
                  + (lock ? " FOR UPDATE" : ""),
              workspace,
              parent);
      if (rows.isEmpty()) throw new RPanException("团队目标目录不可用");
      parent = ((Number) rows.getFirst().get("parent_id")).longValue();
    }
    if (parent != workspace) throw new RPanException("团队目录结构异常");
  }

  public UploadReservation findRequest(long workspace, long actor, String request) {
    var rows =
        jdbc.query(
            """
SELECT * FROM r_pan_workspace_reservation WHERE workspace_id=? AND actor_id=? AND request_id=?
""",
            (rs, row) -> {
              var result = new UploadReservation();
              result.setUploadId(rs.getString("upload_id"));
              return result;
            },
            workspace,
            actor,
            request);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void reserve(RPanUploadTask task, String request) {
    check(task);
    if (jdbc.update(
            """
            UPDATE r_pan_workspace SET reserved_bytes=reserved_bytes+?
            WHERE workspace_id=? AND total_bytes-used_bytes-reserved_bytes>=?
            """,
            task.getFileSize(),
            task.getWorkspaceId(),
            task.getFileSize())
        != 1) throw new RPanException(4101, "团队容量不足");
    jdbc.update(
        """
        INSERT INTO r_pan_workspace_reservation(upload_id,workspace_id,actor_id,request_id,bytes)
        VALUES(?,?,?,?,?)
        """,
        task.getUploadId(),
        task.getWorkspaceId(),
        task.getUserId(),
        request,
        task.getFileSize());
  }

  public void settle(RPanUploadTask task, boolean commit) {
    lock(task.getWorkspaceId());
    var rows =
        jdbc.queryForList(
            "SELECT * FROM r_pan_workspace_reservation WHERE upload_id=? FOR UPDATE",
            task.getUploadId());
    if (rows.isEmpty()) throw new IllegalStateException("团队上传预占不存在");
    var row = rows.getFirst();
    if (((Number) row.get("workspace_id")).longValue() != task.getWorkspaceId()
        || ((Number) row.get("actor_id")).longValue() != task.getUserId()
        || ((Number) row.get("bytes")).longValue() != task.getFileSize())
      throw new IllegalStateException("团队预占归属或字节数不一致");
    String target = commit ? "COMMITTED" : "RELEASED";
    if (target.equals(row.get("state"))) return;
    if (!"RESERVED".equals(row.get("state"))) throw new IllegalStateException("团队上传已结算");
    long charged = commit ? task.getFileSize() : 0;
    if (jdbc.update(
            """
            UPDATE r_pan_workspace SET reserved_bytes=reserved_bytes-?,used_bytes=used_bytes+?
            WHERE workspace_id=? AND reserved_bytes>=?
            """,
            task.getFileSize(),
            charged,
            task.getWorkspaceId(),
            task.getFileSize())
        != 1) throw new IllegalStateException("团队账目不一致");
    jdbc.update(
        "UPDATE r_pan_workspace_reservation SET state=? WHERE upload_id=?",
        target,
        task.getUploadId());
  }

  public String availableName(RPanUploadTask task) {
    var existing =
        new HashSet<>(
            jdbc.queryForList(
                """
SELECT filename FROM r_pan_user_file WHERE workspace_id=? AND parent_id=? AND del_flag=0
""",
                String.class,
                task.getWorkspaceId(),
                task.getParentId()));
    String name = task.getFilename();
    String suffix = FileUtil.getFileSuffix(name);
    String base = name.substring(0, name.length() - suffix.length());
    for (int i = 1; existing.contains(name); i++) name = base + "（" + i + "）" + suffix;
    return name;
  }

  public int insertReference(RPanUploadTask task, RPanUserFile file) {
    int inserted =
        jdbc.update(
            """
INSERT INTO r_pan_user_file(file_id,user_id,workspace_id,parent_id,real_file_id,filename,
folder_flag,del_flag,file_type,file_size_desc,space_bytes,create_user,update_user,create_time,update_time)
VALUES(?,0,?,?,?,?,0,0,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
""",
            file.getFileId(),
            task.getWorkspaceId(),
            file.getParentId(),
            file.getRealFileId(),
            file.getFilename(),
            file.getFileType(),
            file.getFileSizeDesc(),
            file.getSpaceBytes(),
            task.getUserId(),
            task.getUserId());
    if (task.getCollectionId() != null)
      jdbc.update(
          """
INSERT INTO r_pan_workspace_submission(upload_id,collection_id,actor_id,file_id,filename,file_bytes) VALUES(?,?,?,?,?,?)
""",
          task.getUploadId(),
          task.getCollectionId(),
          task.getUserId(),
          file.getFileId(),
          task.getFilename(),
          task.getFileSize());
    return inserted;
  }
}
