package com.shuqing.bigdata.infra.xinchang.controller;

import com.shuqing.bigdata.infra.xinchang.service.XinchangProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理。
 *
 * <p>将业务异常与校验异常映射为标准 JSON 错误响应。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 集群不存在 → 404。
     */
    @ExceptionHandler(XinchangProviderService.ClusterNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(XinchangProviderService.ClusterNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "cluster_not_found",
                "message", e.getMessage()));
    }

    /**
     * 跨租户访问 → 403。
     */
    @ExceptionHandler(XinchangProviderService.ClusterAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(XinchangProviderService.ClusterAccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "access_denied",
                "message", e.getMessage()));
    }

    /**
     * 集群操作失败 → 500。
     */
    @ExceptionHandler(XinchangProviderService.ClusterOperationException.class)
    public ResponseEntity<Map<String, Object>> handleOperationFailed(XinchangProviderService.ClusterOperationException e) {
        log.error("Cluster operation failed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "operation_failed",
                "message", e.getMessage()));
    }

    /**
     * 参数校验失败 → 400。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "validation_failed",
                "message", message));
    }

    /**
     * 非法参数 → 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "invalid_argument",
                "message", e.getMessage()));
    }
}