package com.rubin.rpan.modules.workspace;

import com.rubin.rpan.common.annotation.NeedLogin;
import com.rubin.rpan.common.response.R;
import com.rubin.rpan.util.UserIdUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("workspaces")
@ConditionalOnProperty(name = "rpan.workspace.enabled", havingValue = "true")
public class WorkspaceController {
  private final WorkspaceService service;

  public WorkspaceController(WorkspaceService service) {
    this.service = service;
  }

  public record CreateWorkspace(@NotBlank @Size(max = 100) String name) {}

  public record AssignRole(@NotNull WorkspaceRole role) {}

  @GetMapping("me")
  @NeedLogin
  public R<String> identity() {
    return R.data(String.valueOf(UserIdUtil.get()));
  }

  @PostMapping
  @NeedLogin
  public R<WorkspaceService.Workspace> create(@Valid @RequestBody CreateWorkspace input) {
    return R.data(service.create(UserIdUtil.get(), input.name()));
  }

  @GetMapping
  @NeedLogin
  public R<List<WorkspaceService.Workspace>> list() {
    return R.data(service.list(UserIdUtil.get()));
  }

  @GetMapping("{id}/members")
  @NeedLogin
  public R<List<WorkspaceService.Member>> members(@PathVariable("id") long id) {
    return R.data(service.members(id, UserIdUtil.get()));
  }

  @PutMapping("{id}/members/{userId}")
  @NeedLogin
  public R<WorkspaceService.Member> assign(
      @PathVariable("id") long id,
      @PathVariable("userId") long target,
      @Valid @RequestBody AssignRole input) {
    return R.data(service.assign(id, UserIdUtil.get(), target, input.role()));
  }

  @DeleteMapping("{id}/members/{userId}")
  @NeedLogin
  public R<Void> remove(@PathVariable("id") long id, @PathVariable("userId") long target) {
    service.remove(id, UserIdUtil.get(), target);
    return R.success();
  }
}
