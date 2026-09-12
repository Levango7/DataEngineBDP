package com.levango7.dataenginebdp.datastandard.controller;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 全局异常处理（分层收窄，避免 catch (Exception e) 掩盖具体异常）。
 *
 * <p>处理顺序由具体到宽泛：
 * <ul>
 *   <li>{@link IllegalStateException} — 缺少租户上下文等业务前置条件失败 → 400</li>
 *   <li>{@link MethodArgumentNotValidException} — @Valid 请求体校验失败 → 400</li>
 *   <li>{@link ConstraintViolationException} — 路径/查询参数校验失败 → 400</li>
 *   <li>{@link MethodArgumentTypeMismatchException} — 路径变量类型不匹配 → 400</li>
 *   <li>{@link HttpMessageNotReadableException} — 请求体 JSON 不可解析 → 400</li>
 *   <li>{@link NoSuchElementException} — 资源不存在 → 404</li>
 *   <li>{@link OptimisticLockingFailureException} — 乐观锁冲突 → 409</li>
 *   <li>{@link DataAccessException} — 数据访问层异常 → 503</li>
 *   <li>{@link Exception} — 兜底未预期异常 → 500（仅在此处使用宽泛捕获）</li>
 * </ul>
 *
 * <p>设计原则：具体异常返回精确 HTTP 状态码与错误码，
 * 仅最末的兜底 handler 使用 {@code Exception}，避免掩盖可识别的故障。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务前置条件失败（如缺少租户上下文） */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        log.warn("业务前置条件失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "precondition_failed", "message", e.getMessage()));
    }

    /** @Valid 请求体校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("validation failed");
        log.warn("请求体校验失败: {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "validation_failed", "message", detail));
    }

    /** 路径/查询参数校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolation(
            ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .findFirst()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .orElse("constraint violation");
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "constraint_violation", "message", detail));
    }

    /** 路径变量类型不匹配 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        log.warn("路径变量类型不匹配: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "type_mismatch", "message",
                        "参数 " + e.getName() + " 类型不匹配"));
    }

    /** 请求体 JSON 不可解析 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleNotReadable(
            HttpMessageNotReadableException e) {
        log.warn("请求体不可解析: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "malformed_json", "message", "请求体 JSON 格式错误"));
    }

    /** 资源不存在 */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found", "message",
                        e.getMessage() == null ? "resource not found" : e.getMessage()));
    }

    /** 乐观锁冲突 */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(
            OptimisticLockingFailureException e) {
        log.warn("乐观锁冲突: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "conflict", "message", "资源已被并发修改"));
    }

    /** 数据访问层异常（连接、约束、锁等） */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException e) {
        log.error("数据访问异常", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "data_access_error", "message", "数据访问失败"));
    }

    /**
     * 兜底未预期异常。
     *
     * <p>仅在此处使用宽泛的 {@code Exception} 捕获，作为最后一道防线，
     * 避免异常泄漏到容器默认错误页。具体异常已被上方 handler 收窄处理。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal_error", "message", "服务内部错误"));
    }
}