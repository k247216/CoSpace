package com.rubin.rpan.modules.workspace;

import com.rubin.rpan.common.annotation.NeedLogin;
import com.rubin.rpan.common.response.R;
import com.rubin.rpan.modules.file.upload.model.UploadTaskInitRequest;
import com.rubin.rpan.modules.file.upload.model.UploadTaskView;
import com.rubin.rpan.modules.file.upload.service.UploadTaskService;
import com.rubin.rpan.util.UserIdUtil;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "rpan.workspace.enabled", havingValue = "true")
public class WorkspaceCollectionController {
  private final WorkspaceCollections collections;
  private final UploadTaskService uploads;

  public WorkspaceCollectionController(
      WorkspaceCollections collections, UploadTaskService uploads) {
    this.collections = collections;
    this.uploads = uploads;
  }

  public record Create(
      long parentId,
      String title,
      Instant deadline,
      long maxFileBytes,
      String allowedSuffix,
      List<Long> recipientIds) {}

  public record Invitation(boolean active) {}

  public record Recipients(List<Long> recipientIds) {}

  @GetMapping("workspaces/{workspace}/collections")
  @NeedLogin
  public R<List<WorkspaceCollections.Collection>> list(@PathVariable("workspace") long workspace) {
    return R.data(collections.list(workspace, UserIdUtil.get()));
  }

  @GetMapping("collections")
  @NeedLogin
  public R<List<WorkspaceCollections.Received>> received() {
    return R.data(collections.received(UserIdUtil.get()));
  }

  @GetMapping("workspaces/{workspace}/collections/recipients")
  @NeedLogin
  public R<List<WorkspaceCollections.Recipient>> recipients(
      @PathVariable("workspace") long workspace,
      @RequestParam(value = "query", defaultValue = "") String query) {
    return R.data(collections.recipients(workspace, UserIdUtil.get(), query));
  }

  @GetMapping("workspaces/{workspace}/collections/{collection}/invitees")
  @NeedLogin
  public R<List<WorkspaceCollections.Participant>> invitees(
      @PathVariable("workspace") long workspace, @PathVariable("collection") String collection) {
    return R.data(collections.invitees(workspace, UserIdUtil.get(), collection));
  }

  @GetMapping("collections/{collection}")
  @NeedLogin
  public R<WorkspaceCollections.Collection> detail(@PathVariable("collection") String collection) {
    return R.data(collections.detail(collection, UserIdUtil.get()));
  }

  @PostMapping("workspaces/{workspace}/collections")
  @NeedLogin
  public R<String> create(@PathVariable("workspace") long workspace, @RequestBody Create input) {
    return R.data(
        collections.create(
            workspace,
            UserIdUtil.get(),
            input.parentId(),
            input.title(),
            input.deadline(),
            input.maxFileBytes(),
            input.allowedSuffix(),
            input.recipientIds()));
  }

  @PostMapping("workspaces/{workspace}/collections/{collection}/invitees")
  @NeedLogin
  public R<Void> inviteMany(
      @PathVariable("workspace") long workspace,
      @PathVariable("collection") String collection,
      @RequestBody Recipients input) {
    collections.inviteMany(workspace, UserIdUtil.get(), collection, input.recipientIds());
    return R.success();
  }

  @PutMapping("workspaces/{workspace}/collections/{collection}/invitees/{user}")
  @NeedLogin
  public R<Void> invite(
      @PathVariable("workspace") long workspace,
      @PathVariable("collection") String collection,
      @PathVariable("user") long user,
      @RequestBody Invitation input) {
    collections.invite(workspace, UserIdUtil.get(), collection, user, input.active());
    return R.success();
  }

  @PostMapping("workspaces/{workspace}/collections/{collection}/close")
  @NeedLogin
  public R<Void> close(
      @PathVariable("workspace") long workspace, @PathVariable("collection") String collection) {
    collections.close(workspace, UserIdUtil.get(), collection);
    return R.success();
  }

  @PostMapping("collections/{collection}/uploads")
  @NeedLogin
  public R<UploadTaskView> upload(
      @PathVariable("collection") String collection,
      @Valid @RequestBody UploadTaskInitRequest input) {
    var destination = collections.destination(collection, UserIdUtil.get());
    input.setParentId(destination.parent());
    return R.data(uploads.initialize(input, UserIdUtil.get(), destination.workspace(), collection));
  }

  @GetMapping("collections/{collection}/submissions")
  @NeedLogin
  public R<List<WorkspaceCollections.Submission>> submissions(
      @PathVariable("collection") String collection,
      @RequestParam(value = "admin", defaultValue = "false") boolean admin) {
    return R.data(collections.submissions(collection, UserIdUtil.get(), admin));
  }
}
