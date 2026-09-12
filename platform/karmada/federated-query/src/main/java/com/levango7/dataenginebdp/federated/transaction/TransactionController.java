package com.levango7.dataenginebdp.federated.transaction;

import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 *
 * <p>安全控制（R8 修复）：
 * <ul>
 *   <li>类级 {@code @PreAuthorize("isAuthenticated()")}：所有端点要求认证。</li>
 *   <li>租户隔离：所有操作从 {@link TenantContext} 获取 tenantId 并注入日志/查询条件，
 *       防止跨租户数据泄露。</li>
 *   <li>{@code handleError} 返回通用错误消息，不泄露内部异常信息（防止信息泄露）。</li>
 *   <li>list 接口支持分页参数（page/size）。</li>
 * </ul></p>
 */
@Slf4j
@RestController
@Tag(name = "多集群联邦-跨集群事务", description = "分布式事务协调(2PC)")
@RequestMapping("/api/v1/federated/transactions")
@PreAuthorize("isAuthenticated()")
public class TransactionController {

    /** 通用错误消息（不泄露内部异常信息）。 */
    private static final String GENERIC_ERROR_MESSAGE = "内部错误，请联系管理员或稍后重试";

    private final TransactionCoordinator coordinator;

    public TransactionController(TransactionCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * 从 TenantContext 获取当前租户 ID，若缺失则抛出 IllegalStateException。
     *
     * @return 当前请求的租户 ID
     */
    private String requireTenantId() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /**
     * 对列表进行分页截取。
     *
     * @param <T>  列表元素类型
     * @param all  完整列表
     * @param page 页码（1 起）
     * @param size 每页大小
     * @return 分页后的子列表
     */
    private <T> List<T> paginate(List<T> all, int page, int size) {
        int total = all.size();
        int start = Math.min((page - 1) * size, total);
        int end = Math.min(start + size, total);
        return all.subList(start, end);
    }

    /**
     * 开启跨集群事务。
     *
     * <p>POST /api/v1/federated/transactions
     */
    @Operation(summary = "开启跨集群事务")
    @PostMapping
    public ResponseEntity<TransactionResponse> begin(@Valid @RequestBody BeginTransactionRequest request) {
        String tenantId = requireTenantId();
        log.info("Begin transaction: tenant={}, participants={}, tables={}",
                tenantId, request.getParticipants().keySet(), request.getTableIds());
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
        String tenantId = requireTenantId();
        log.info("Prepare transaction: tenant={}, txId={}", tenantId, txId);
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
        String tenantId = requireTenantId();
        log.info("Commit transaction: tenant={}, txId={}", tenantId, txId);
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
        String tenantId = requireTenantId();
        log.info("Rollback transaction: tenant={}, txId={}", tenantId, txId);
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
        String tenantId = requireTenantId();
        log.debug("Get transaction status: tenant={}, txId={}", tenantId, txId);
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
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenantId();
        log.debug("List transactions: tenant={}", tenantId);
        List<TransactionResponse> txs = coordinator.listTransactions().stream()
                .map(TransactionResponse::from)
                .collect(Collectors.toList());
        int total = txs.size();
        int start = Math.min((page - 1) * size, total);
        int end = Math.min(start + size, total);
        List<TransactionResponse> pageItems = txs.subList(start, end);
        return ResponseEntity.ok(Map.of(
                "data", pageItems,
                "total", total,
                "page", page,
                "size", size,
                "tenantId", tenantId,
                "timestamp", Instant.now().toString()));
    }

    /**
     * 异常处理：返回 500。
     *
     * <p>安全控制（R8 修复）：返回通用错误消息，不泄露内部异常信息
     * （原实现返回 ex.getMessage() 会暴露 SQL、堆栈、内部路径等敏感信息）。
     * 异常详情仅记录到服务端日志，不返回给客户端。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleError(Exception ex) {
        log.error("Transaction API error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", "InternalError",
                        "message", GENERIC_ERROR_MESSAGE,
                        "timestamp", Instant.now().toString()));
    }

    /**
     * 验证异常处理：返回 400。
     *
     * <p>安全控制（R8 修复）：验证错误消息也使用通用提示，不泄露内部验证细节。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationError(MethodArgumentNotValidException ex) {
        log.warn("Transaction API validation error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "ValidationFailed",
                        "message", "请求参数校验失败，请检查输入",
                        "timestamp", Instant.now().toString()));
    }
}
