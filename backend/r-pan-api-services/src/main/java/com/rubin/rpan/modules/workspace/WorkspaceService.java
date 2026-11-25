package com.rubin.rpan.modules.workspace;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.util.IdGenerator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Service
@ConditionalOnProperty(name = "rpan.workspace.enabled", havingValue = "true")
public class WorkspaceService {
  private final JdbcTemplate jdbc;
  private final long defaultCapacity;

  public WorkspaceService(
      JdbcTemplate jdbc,
      @Value("${rpan.workspace.default-total-bytes:10737418240}") long defaultCapacity) {
    if (defaultCapacity <= 0) throw new IllegalArgumentException("团队默认容量必须为正数");
    this.jdbc = jdbc;
    this.defaultCapacity = defaultCapacity;
  }

  public record Workspace(
      @JsonSerialize(using = ToStringSerializer.class) long id,
      String name,
      WorkspaceRole role,
      long totalBytes,
      long usedBytes,
      long reservedBytes) {}

  public record Member(
      @JsonSerialize(using = ToStringSerializer.class) long userId,
      WorkspaceRole role,
      boolean active,
      long generation) {}

  @Transactional
  public Workspace create(long actor, String name) {
    requireUser(actor);
    if (name == null || name.isBlank() || name.strip().length() > 100) {
      throw new RPanException("空间名称须为1至100个字符");
    }
    long id = IdGenerator.nextId();
    jdbc.update(
        "INSERT INTO r_pan_workspace(workspace_id,name,owner_id,total_bytes) VALUES(?,?,?,?)",
        id,
        name.strip(),
        actor,
        defaultCapacity);
    jdbc.update(
        "INSERT INTO r_pan_workspace_member(workspace_id,user_id,role) VALUES(?,?,'OWNER')",
        id,
        actor);
    return new Workspace(id, name.strip(), WorkspaceRole.OWNER, defaultCapacity, 0, 0);
  }

  @Transactional(readOnly = true)
  public List<Workspace> list(long actor) {
    requireUser(actor);
    return jdbc.query(
        """
        SELECT w.*,m.role FROM r_pan_workspace w JOIN r_pan_workspace_member m
          ON m.workspace_id=w.workspace_id
        WHERE m.user_id=? AND m.active=TRUE ORDER BY w.workspace_id
        """,
        (rs, row) ->
            new Workspace(
                rs.getLong("workspace_id"),
                rs.getString("name"),
                WorkspaceRole.valueOf(rs.getString("role")),
                rs.getLong("total_bytes"),
                rs.getLong("used_bytes"),
                rs.getLong("reserved_bytes")),
        actor);
  }

  @Transactional
  public List<Member> members(long id, long actor) {
    lockWorkspace(id);
    if (!requireMember(id, actor).role().managesMembers()) throw denied();
    return jdbc.query(
        "SELECT * FROM r_pan_workspace_member WHERE workspace_id=? ORDER BY user_id",
        (rs, row) -> member(rs),
        id);
  }

  @Transactional
  public Member assign(long id, long actor, long target, WorkspaceRole requested) {
    if (requested == null) throw new RPanException("请选择成员角色");
    lockWorkspace(id);
    var operator = requireMember(id, actor);
    var previous = findMember(id, target);
    if (!operator.role().canAssign(previous == null ? null : previous.role(), requested))
      throw denied();
    requireUser(target);
    if (previous == null) {
      jdbc.update(
          "INSERT INTO r_pan_workspace_member(workspace_id,user_id,role) VALUES(?,?,?)",
          id,
          target,
          requested.name());
    } else if (!previous.active() || previous.role() != requested) {
      jdbc.update(
          """
          UPDATE r_pan_workspace_member SET role=?,active=TRUE,generation=generation+1
          WHERE workspace_id=? AND user_id=?
          """,
          requested.name(),
          id,
          target);
    }
    return findMember(id, target);
  }

  @Transactional
  public void remove(long id, long actor, long target) {
    lockWorkspace(id);
    var operator = requireMember(id, actor);
    var previous = findMember(id, target);
    if (!operator.role().managesMembers()) throw denied();
    if (previous == null) return;
    if (!operator.role().canAssign(previous.role(), WorkspaceRole.VIEWER)) throw denied();
    if (previous.active()) {
      jdbc.update(
          """
          UPDATE r_pan_workspace_member SET active=FALSE,generation=generation+1
          WHERE workspace_id=? AND user_id=?
          """,
          id,
          target);
    }
  }

  private void lockWorkspace(long id) {
    var rows =
        jdbc.queryForList(
            "SELECT workspace_id FROM r_pan_workspace WHERE workspace_id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw denied();
  }

  private Member requireMember(long id, long user) {
    var result = findMember(id, user);
    if (result == null || !result.active()) throw denied();
    return result;
  }

  private Member findMember(long id, long user) {
    var rows =
        jdbc.query(
            "SELECT * FROM r_pan_workspace_member WHERE workspace_id=? AND user_id=? FOR UPDATE",
            (rs, row) -> member(rs),
            id,
            user);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static Member member(ResultSet rs) throws SQLException {
    return new Member(
        rs.getLong("user_id"),
        WorkspaceRole.valueOf(rs.getString("role")),
        rs.getBoolean("active"),
        rs.getLong("generation"));
  }

  private void requireUser(long user) {
    if (user <= 0
        || jdbc.queryForObject("SELECT COUNT(*) FROM r_pan_user WHERE user_id=?", Long.class, user)
            != 1) {
      throw new RPanException("用户不存在");
    }
  }

  private static RPanException denied() {
    return new RPanException("工作空间不存在或无操作权限");
  }
}
