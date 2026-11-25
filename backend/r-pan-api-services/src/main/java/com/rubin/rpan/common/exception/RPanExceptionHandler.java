package com.rubin.rpan.common.exception;

import com.rubin.rpan.common.response.R;
import com.rubin.rpan.common.response.ResponseCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常捕获 */
@RestControllerAdvice
public class RPanExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(RPanExceptionHandler.class);

  @ExceptionHandler(value = RPanException.class)
  public R rPanExceptionHandler(RPanException e) {
    return R.fail(e.getCode(), e.getMsg());
  }

  @ExceptionHandler(value = MethodArgumentNotValidException.class)
  public R methodArgumentNotValidExceptionHandler(MethodArgumentNotValidException e) {
    ObjectError objectError = e.getBindingResult().getAllErrors().stream().findFirst().get();
    return R.fail(ResponseCode.ERROR_PARAM.getCode(), objectError.getDefaultMessage());
  }

  @ExceptionHandler(value = ConstraintViolationException.class)
  public R constraintViolationExceptionHandler(ConstraintViolationException e) {
    ConstraintViolation constraintViolation =
        e.getConstraintViolations().stream().findFirst().get();
    return R.fail(ResponseCode.ERROR_PARAM.getCode(), constraintViolation.getMessage());
  }

  @ExceptionHandler(value = MissingServletRequestParameterException.class)
  public R missingServletRequestParameterExceptionHandler(
      MissingServletRequestParameterException e) {
    return R.fail(ResponseCode.ERROR_PARAM.getCode(), ResponseCode.ERROR_PARAM.getDesc());
  }

  @ExceptionHandler(value = IllegalStateException.class)
  public R illegalStateExceptionHandler(IllegalStateException e) {
    return R.fail(ResponseCode.ERROR_PARAM.getCode(), ResponseCode.ERROR_PARAM.getDesc());
  }

  @ExceptionHandler(value = BindException.class)
  public R bindExceptionHandler(BindException e) {
    FieldError fieldError = e.getBindingResult().getFieldErrors().stream().findFirst().get();
    return R.fail(ResponseCode.ERROR_PARAM.getCode(), fieldError.getDefaultMessage());
  }

  @ExceptionHandler(value = RuntimeException.class)
  public R runtimeExceptionHandler(RuntimeException e) {
    log.error("Unhandled application error", e);
    return R.fail(ResponseCode.ERROR.getCode(), "服务暂时不可用，请重试");
  }
}
