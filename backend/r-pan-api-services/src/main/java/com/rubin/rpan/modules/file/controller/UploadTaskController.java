package com.rubin.rpan.modules.file.controller;

import com.rubin.rpan.common.annotation.NeedLogin;
import com.rubin.rpan.common.response.R;
import com.rubin.rpan.modules.file.upload.model.ChunkReceipt;
import com.rubin.rpan.modules.file.upload.model.UploadTaskInitRequest;
import com.rubin.rpan.modules.file.upload.model.UploadTaskView;
import com.rubin.rpan.modules.file.upload.service.OssDirectUploads.SignedPart;
import com.rubin.rpan.modules.file.upload.service.UploadCompletionService;
import com.rubin.rpan.modules.file.upload.service.UploadTaskService;
import com.rubin.rpan.util.UserIdUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequestMapping("file/upload/tasks")
public class UploadTaskController {
  private final UploadTaskService service;
  private final UploadCompletionService completion;

  public UploadTaskController(UploadTaskService service, UploadCompletionService completion) {
    this.service = service;
    this.completion = completion;
  }

  @PostMapping("{uploadId}/complete")
  @NeedLogin
  public R<UploadTaskView> complete(@PathVariable("uploadId") String uploadId) {
    completion.complete(uploadId, UserIdUtil.get());
    return R.data(service.status(uploadId, UserIdUtil.get()));
  }

  @PostMapping
  @NeedLogin
  public R<UploadTaskView> initialize(@Valid @RequestBody UploadTaskInitRequest input) {
    return R.data(service.initialize(input, UserIdUtil.get()));
  }

  @PostMapping("{uploadId}/cancel")
  @NeedLogin
  public R<UploadTaskView> cancel(@PathVariable("uploadId") String id) {
    return R.data(service.cancel(id, UserIdUtil.get()));
  }

  @PostMapping("{uploadId}/chunks/{index}")
  @NeedLogin
  public R<ChunkReceipt> receive(
      @PathVariable("uploadId") String uploadId,
      @PathVariable("index") int index,
      @RequestParam("file") MultipartFile file) {
    return R.data(service.receive(uploadId, index, file, UserIdUtil.get()));
  }

  public record PartSignatureRequest(@NotNull @Pattern(regexp = "[0-9a-fA-F]{32}") String md5) {}

  @PostMapping("{uploadId}/chunks/{index}/signature")
  @NeedLogin
  public R<SignedPart> signPart(
      @PathVariable("uploadId") String id,
      @PathVariable("index") int index,
      @Valid @RequestBody PartSignatureRequest input,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return R.data(service.signPart(id, index, input.md5(), UserIdUtil.get()));
  }

  @GetMapping("{uploadId}")
  @NeedLogin
  public R<UploadTaskView> status(@PathVariable("uploadId") String uploadId) {
    return R.data(service.status(uploadId, UserIdUtil.get()));
  }
}
