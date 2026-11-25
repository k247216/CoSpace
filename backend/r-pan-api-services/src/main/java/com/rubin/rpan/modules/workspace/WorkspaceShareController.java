package com.rubin.rpan.modules.workspace;

import com.rubin.rpan.common.annotation.NeedLogin;
import com.rubin.rpan.common.response.R;
import com.rubin.rpan.modules.file.service.IUserFileService;
import com.rubin.rpan.util.UserIdUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "rpan.workspace.enabled", havingValue = "true")
public class WorkspaceShareController {
  private final WorkspaceSharing sharing;
  private final IUserFileService files;

  public WorkspaceShareController(WorkspaceSharing sharing, IUserFileService files) {
    this.sharing = sharing;
    this.files = files;
  }

  public record Share(long fileId, long recipientId, @NotNull Instant expiresAt) {}

  @GetMapping("workspaces/{workspace}/shares")
  @NeedLogin
  public R<java.util.List<WorkspaceSharing.Share>> list(@PathVariable("workspace") long workspace) {
    return R.data(sharing.list(workspace, UserIdUtil.get()));
  }

  @PostMapping("workspaces/{workspace}/shares")
  @NeedLogin
  public R<String> create(
      @PathVariable("workspace") long workspace, @Valid @RequestBody Share input) {
    return R.data(
        sharing.create(
            workspace, UserIdUtil.get(), input.fileId(), input.recipientId(), input.expiresAt()));
  }

  @DeleteMapping("workspaces/{workspace}/shares/{share}")
  @NeedLogin
  public R<Void> revoke(
      @PathVariable("workspace") long workspace, @PathVariable("share") String share) {
    sharing.revoke(workspace, UserIdUtil.get(), share);
    return R.success();
  }

  @GetMapping("workspace-shares/{share}/download")
  @NeedLogin
  public void download(@PathVariable("share") String share, HttpServletResponse response) {
    files.download(sharing.downloadable(share, UserIdUtil.get()), response);
  }
}
