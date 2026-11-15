package com.careerpath.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一异常出口。
 *
 * <p>错误体形状固定为 {@code {"detail": {"code": "...", "message": "..."}}}，
 * 成功响应则直接返回资源对象、不包 code/data 封套——这条从 v1 沿用，前端拦截器只写一份。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApiException(ApiException ex, HttpServletRequest request) {
        log.debug("业务异常 {} {} → {}", request.getMethod(), request.getRequestURI(), ex.code());
        return ResponseEntity.status(ex.code().status())
                .body(ErrorBody.of(ex.code().name(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ErrorBody> handleValidation(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("；"));
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ErrorBody.of(ErrorCode.VALIDATION_ERROR.name(),
                        message.isBlank() ? ErrorCode.VALIDATION_ERROR.defaultMessage() : message));
    }

    private String describe(FieldError error) {
        return error.getField() + "：" + (error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorBody> handleBadRequest(Exception ex) {
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ErrorBody.of(ErrorCode.VALIDATION_ERROR.name(), ErrorCode.VALIDATION_ERROR.defaultMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorBody> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
                .body(ErrorBody.of(ErrorCode.FORBIDDEN.name(), ErrorCode.FORBIDDEN.defaultMessage()));
    }

    /** 未匹配到任何路由：返回 JSON，而不是 Spring 默认的 HTML 错误页。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorBody.of(ErrorCode.NOT_FOUND.name(), "接口不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorBody.of("METHOD_NOT_ALLOWED", "请求方法不被支持"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未捕获异常 {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ErrorBody.of(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }
}
