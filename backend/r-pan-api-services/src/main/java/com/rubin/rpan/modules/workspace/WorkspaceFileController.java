package com.rubin.rpan.modules.workspace;

import com.rubin.rpan.common.annotation.NeedLogin;
import com.rubin.rpan.common.response.R;
import com.rubin.rpan.modules.file.service.IUserFileService;
import com.rubin.rpan.modules.file.upload.model.UploadTaskInitRequest;
import com.rubin.rpan.modules.file.upload.model.UploadTaskView;
import com.rubin.rpan.modules.file.upload.service.UploadTaskService;
import com.rubin.rpan.util.UserIdUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("workspaces/{workspace}/files")
@ConditionalOnProperty(name = "rpan.workspace.enabled", havingValue = "true")
public class WorkspaceFileController {
  private final WorkspaceFiles files;
  private final UploadTaskService uploads;
  private final IUserFileService personalFiles;

  public WorkspaceFileController(
      WorkspaceFiles files, UploadTaskService uploads, IUserFileService personalFiles) {
    this.files = files;
    this.uploads = uploads;
    this.personalFiles = personalFiles;
  }

  public record Folder(long parentId, @NotBlank String name) {}

  public record Update(@NotBlank String name, Long parentId) {}

  @GetMapping
  @NeedLogin
  public R<List<WorkspaceFiles.Node>> list(
      @PathVariable("workspace") long workspace,
      @RequestParam("parentId") long parent,
      @RequestParam(value = "recycle", defaultValue = "false") boolean recycle) {
    return R.data(files.list(workspace, UserIdUtil.get(), parent, recycle));
  }

  @PostMapping("folders")
  @NeedLogin
  public R<String> folder(
      @PathVariable("workspace") long workspace, @Valid @RequestBody Folder input) {
    return R.data(
        String.valueOf(files.folder(workspace, UserIdUtil.get(), input.parentId(), input.name())));
  }

  @PostMapping("uploads")
  @NeedLogin
  public R<UploadTaskView> upload(
      @PathVariable("workspace") long workspace, @Valid @RequestBody UploadTaskInitRequest input) {
    return R.data(uploads.initialize(input, UserIdUtil.get(), workspace));
  }

  @GetMapping("{id}/download")
  @NeedLogin
  public void download(
      @PathVariable("workspace") long workspace,
      @PathVariable("id") long id,
      HttpServletResponse response) {
    long file = files.downloadable(workspace, UserIdUtil.get(), id);
    personalFiles.download(file, response);
  }

  @GetMapping("search")
  @NeedLogin
  public R<List<WorkspaceFiles.Node>> search(
      @PathVariable("workspace") long workspace, @RequestParam("keyword") String keyword) {
    return R.data(files.search(workspace, UserIdUtil.get(), keyword));
  }

  @GetMapping("{id}/preview")
  @NeedLogin
  public void preview(
      @PathVariable("workspace") long workspace,
      @PathVariable("id") long id,
      HttpServletResponse response) {
    personalFiles.previewAuthorized(files.downloadable(workspace, UserIdUtil.get(), id), response);
  }

  @PatchMapping("{id}")
  @NeedLogin
  public R<Void> update(
      @PathVariable("workspace") long workspace,
      @PathVariable("id") long id,
      @Valid @RequestBody Update input) {
    files.update(workspace, UserIdUtil.get(), id, input.name(), input.parentId());
    return R.success();
  }

  @DeleteMapping("{id}")
  @NeedLogin
  public R<Void> delete(
      @PathVariable("workspace") long workspace,
      @PathVariable("id") long id,
      @RequestParam(value = "purge", defaultValue = "false") boolean purge) {
    files.recycle(workspace, UserIdUtil.get(), id, false, purge);
    return R.success();
  }

  @PostMapping("{id}/restore")
  @NeedLogin
  public R<Void> restore(@PathVariable("workspace") long workspace, @PathVariable("id") long id) {
    files.recycle(workspace, UserIdUtil.get(), id, true, false);
    return R.success();
  }
}
