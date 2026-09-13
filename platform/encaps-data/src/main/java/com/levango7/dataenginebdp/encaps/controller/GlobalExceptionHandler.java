package com.levango7.dataenginebdp.encaps.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器（P3-8）。
 *
 * <p>统一处理各 Controller 抛出的异常，返回标准化的错误响应，
 * 避免异常细节泄露内部实现，并提供一致的错误码体系。</p>
 *
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} — 400 参数校验失败</li>
 *   <li>{@link ConstraintViolationException} — 400 约束校验失败</li>
 *   <li>{@link MethodArgumentTypeMismatchException} — 400 参数类型不匹配</li>
 *   <li>{@link IllegalArgumentException} — 400 非法参数</li>
 *   <li>{@link IllegalStateException} — 403 状态不允许（如缺少租户上下文）</li>
 *   <li>通用 {@link Exception} — 500 内部错误（消息泛化）</li>
 * </ul>
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /** 参数校验失败（@Valid 触发）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation_failed");
        body.put("message", e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst().orElse("参数校验失败"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 约束校验失败（@RequestParam @NotBlank 等触发）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("约束校验失败: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "constraint_violation");
        body.put("message", e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .findFirst().orElse("约束校验失败"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 参数类型不匹配。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "type_mismatch");
        body.put("message", "参数 " + e.getName() + " 类型不匹配");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 非法参数。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_argument");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 状态不允许（如缺少租户上下文）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("状态不允许: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "forbidden");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /** 通用异常兜底（消息泛化，不暴露内部细节）。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("未处理的异常", e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "internal_error");
        body.put("message", "内部错误，请联系管理员");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}