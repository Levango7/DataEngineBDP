package com.levango7.dataenginebdp.federated.transaction;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 跨集群事务 REST API 控制器。
 *
 * <p>端点：
 * <ul>
 *   <li>POST   /api/v1/federated/transactions                       - 开启事务</li>
 *   <li>POST   /api/v1/federated/transactions/{txId}/prepare        - 准备阶段</li>
 *   <li>POST   /api/v1/federated/transactions/{txId}/commit         - 提交事务</li>
 *   <li>POST   /api/v1/federated/transactions/{txId}/rollback       - 回滚事务</li>
 *   <li>GET    /api/v1/federated/transactions/{txId}                - 查询事务状态</li>
 *   <li>GET    /api/v1/federated/transactions                       - 列出事务</li>
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "多集群联邦-跨集群事务", description = "分布式事务协调(2PC)")
@RequestMapping("/api/v1/federated/transactions")
public class TransactionController {

    private final TransactionCoordinator coordinator;

    public TransactionController(TransactionCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * 开启跨集群事务。
     *
     * <p>POST /api/v1/federated/transactions
     */
    @Operation(summary = "开启跨集群事务")
    @PostMapping
    public ResponseEntity<TransactionResponse> begin(@Valid @RequestBody BeginTransactionRequest request) {
        log.info("Begin transaction: participants={} tables={}",
                request.getParticipants().keySet(), request.getTableIds());
        String txId = coordinator.begin(request.getParticipants(), request.getTableIds());
        TransactionLog logEntry = coordinator.getTransactionStatus(txId);
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(logEntry));
    }

    /**
     * 准备阶段（2PC 阶段 1）。
     *
     * <p>POST /api/v1/federated/transactions/{txId}/prepare
     */
    @Operation(summary = "准备阶段（2PC 阶段 1）")
    @PostMapping("/{txId}/prepare")
    public ResponseEntity<TransactionResponse> prepare(@PathVariable String txId) {
        log.info("Prepare transaction: txId={}", txId);
        boolean ok = coordinator.prepare(txId);
        TransactionLog logEntry = coordinator.getTransactionStatus(txId);
        if (logEntry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(TransactionResponse.from(logEntry));
    }

    /**
     * 提交事务（2PC 阶段 2）。
     *
     * <p>POST /api/v1/federated/transactions/{txId}/commit
     */
    @Operation(summary = "提交事务（2PC 阶段 2）")
    @PostMapping("/{txId}/commit")
    public ResponseEntity<TransactionResponse> commit(@PathVariable String txId) {
        log.info("Commit transaction: txId={}", txId);
        boolean ok = coordinator.commit(txId);
        TransactionLog logEntry = coordinator.getTransactionStatus(txId);
        if (logEntry == null) {
            return ResponseEntity.notFound().build();
        }
        HttpStatus status = ok ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(TransactionResponse.from(logEntry));
    }

    /**
     * 回滚事务。
     *
     * <p>POST /api/v1/federated/transactions/{txId}/rollback
     */
    @Operation(summary = "回滚联邦")
    @PostMapping("/{txId}/rollback")
    public ResponseEntity<TransactionResponse> rollback(@PathVariable String txId) {
        log.info("Rollback transaction: txId={}", txId);
        boolean ok = coordinator.rollback(txId);
        TransactionLog logEntry = coordinator.getTransactionStatus(txId);
        if (logEntry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(TransactionResponse.from(logEntry));
    }

    /**
     * 查询事务状态。
     *
     * <p>GET /api/v1/federated/transactions/{txId}
     */
    @Operation(summary = "查询事务状态")
    @GetMapping("/{txId}")
    public ResponseEntity<TransactionResponse> getStatus(@PathVariable String txId) {
        TransactionLog logEntry = coordinator.getTransactionStatus(txId);
        if (logEntry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(TransactionResponse.from(logEntry));
    }

    /**
     * 列出所有事务。
     *
     * <p>GET /api/v1/federated/transactions
     */
    @Operation(summary = "列出所有事务")
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<TransactionResponse> txs = coordinator.listTransactions().stream()
                .map(TransactionResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of(
                "data", txs,
                "total", txs.size(),
                "timestamp", Instant.now().toString()));
    }

    /**
     * 异常处理：返回 500。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleError(Exception ex) {
        log.error("Transaction API error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", ex.getClass().getSimpleName(),
                        "message", ex.getMessage() != null ? ex.getMessage() : "unknown",
                        "timestamp", Instant.now().toString()));
    }

    /**
     * 验证异常处理：返回 400。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationError(MethodArgumentNotValidException ex) {
        log.warn("Transaction API validation error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "ValidationFailed",
                        "message", ex.getMessage(),
                        "timestamp", Instant.now().toString()));
    }
}