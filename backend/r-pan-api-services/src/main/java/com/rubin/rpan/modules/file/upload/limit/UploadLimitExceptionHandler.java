package com.rubin.rpan.modules.file.upload.limit;

import com.rubin.rpan.common.response.R;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@Order(-10)
public class UploadLimitExceptionHandler {
  @ExceptionHandler(UploadLimitException.class)
  public ResponseEntity<R<Void>> handle(UploadLimitException failure) {
    HttpStatus status =
        failure.isUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS;
    return ResponseEntity.status(status)
        .header("Retry-After", String.valueOf(failure.getRetryAfterSeconds()))
        .body(R.fail(failure.isUnavailable() ? 4503 : 4429, failure.getMessage()));
  }
}
