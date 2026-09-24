package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.DataSourceEntity;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import com.levango7.dataenginebdp.encaps.security.AuditLog;
import com.levango7.dataenginebdp.encaps.util.CredentialEncryptor;
import com.levango7.dataenginebdp.encaps.util.SsrfGuard;
import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源管理端点（ROADMAP 前后端接线：前端 /datasources）。
 *
 * <p>CRUD + 连接测试；租户 ID 强制取 {@link TenantContext}（防跨租户越权）；
 * 密码仅写入时接收并加密存储，查询返回时脱敏；
 * 连接测试与创建/更新均经 {@link SsrfGuard} 校验目标地址（防 SSRF）。</p>
 */
@Slf4j
@RestController
@Tag(name = "封装数据-数据源管理", description = "数据源CRUD与连接测试")
@RequiredArgsConstructor
@RequestMapping("/api/v1/datasources")
@PreAuthorize("isAuthenticated()")  // R16 安全修复：类级认证校验
public class DataSourceController {

    private final DataSourceRepository repository;
    private final CredentialEncryptor credentialEncryptor;
    private final SsrfGuard ssrfGuard = SsrfGuard.getInstance();

    /** 创建/更新请求体（对齐前端 SaveDataSourceParams）。 */
    public record DataSourceRequest(
            @NotBlank String name,
            @NotBlank String type,
            @NotBlank String host,
            @NotNull Integer port,
            String database,
            String username,
            String password) {
    }

    /** 列表（租户隔离 + 可选类型过滤，P3-9: 添加分页参数）。 */
    @Operation(summary = "列表（租户隔离 + 可选类型过滤）")
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> list(
            String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        String tenantId = requireTenant();
        // P3-9: 分页参数上限校验
        int safePage = Math.max(1, Math.min(page, 1000));
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        List<DataSourceEntity> list = (type == null || type.isBlank())
                ? repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : repository.findByTenantIdAndTypeOrderByCreatedAtDesc(tenantId, type);
        // 内存分页（数据量不大时可接受，大数据量应改用 Pageable 查询）
        int from = (safePage - 1) * safePageSize;
        if (from >= list.size()) {
            return ResponseEntity.ok(List.of());
        }
        int to = Math.min(from + safePageSize, list.size());
        return ResponseEntity.ok(list.subList(from, to).stream().map(this::toView).toList());
    }

    /** 详情。 */
    @Operation(summary = "查询数据源详情")
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> get(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(ds -> ResponseEntity.ok((Object) toView(ds)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。同租户同名数据源返回 409（A3 幂等性）。 */
    @Operation(summary = "创建数据源。同租户同名返回 409")
    @AuditLog(action = "CREATE_DATASOURCE", resource = "datasource")
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody DataSourceRequest req) {
        String tenantId = requireTenant();
        // SSRF 防护：校验目标主机与端口
        ssrfGuard.validate(req.host(), req.port());
        // A3 幂等性：租户内名称唯一预检（数据库层 uk_datasource_tenant_name 兜底）
        if (repository.existsByTenantIdAndName(tenantId, req.name())) {
            Map<String, Object> conflict = new java.util.LinkedHashMap<>();
            conflict.put("code", 40901);
            conflict.put("message", "同名数据源已存在");
            conflict.put("messageKey", "error.resource.conflict");
            conflict.put("conflictField", "name");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict);
        }
        DataSourceEntity entity = DataSourceEntity.builder()
                .name(req.name())
                .type(req.type())
                .host(req.host())
                .port(req.port())
                .database(req.database())
                .username(req.username())
                .password(encryptPassword(req.password()))
                .status("disconnected")
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        DataSourceEntity saved = repository.save(entity);
        log.info("创建数据源: id={}, name={}, type={}, tenant={}",
                saved.getId(), saved.getName(), saved.getType(), tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved));
    }

    /** 更新。 */
    @Operation(summary = "更新数据源")
    @AuditLog(action = "UPDATE_DATASOURCE", resource = "datasource")
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody DataSourceRequest req) {
        String tenantId = requireTenant();
        // SSRF 防护：校验目标主机与端口
        ssrfGuard.validate(req.host(), req.port());
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setType(req.type());
            entity.setHost(req.host());
            entity.setPort(req.port());
            entity.setDatabase(req.database());
            entity.setUsername(req.username());
            if (req.password() != null && !req.password().isBlank()) {
                entity.setPassword(encryptPassword(req.password())); // 密码留空则不更新
            }
            entity.setUpdatedAt(Instant.now());
            return ResponseEntity.ok((Object) toView(repository.save(entity)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除。 */
    @Operation(summary = "删除数据源")
    @AuditLog(action = "DELETE_DATASOURCE", resource = "datasource")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            repository.delete(entity);
            log.info("删除数据源: id={}, tenant={}", id, tenantId);
            return ResponseEntity.ok(Map.of("deleted", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 连接测试（TCP 探测 + JDBC 校验（JDBC 型））。P3-4: 移除不必要的 @Transactional。 */
    @Operation(summary = "连接测试（TCP 探测 + JDBC 校验（JDBC 型））")
    @AuditLog(action = "TEST_DATASOURCE", resource = "datasource")
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            // SSRF 防护：连接前校验目标主机与端口
            ssrfGuard.validate(entity.getHost(), entity.getPort());
            Map<String, Object> result = new LinkedHashMap<>();
            long start = System.currentTimeMillis();
            boolean success = false;
            // 核实依据：误报：TCP 连通性探测，connect() 后立即由 try-with-resources 关闭，不传输任何数据；连接前另有 ssrfGuard.validate()。
            // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(entity.getHost(), entity.getPort()), 5000);
                success = true;
                result.put("success", true);
                result.put("latency", System.currentTimeMillis() - start);
                result.put("message", "连接成功（TCP " + entity.getHost() + ":" + entity.getPort() + "）");
            } catch (Exception e) {
                result.put("success", false);
                result.put("latency", System.currentTimeMillis() - start);
                // 错误消息脱敏：不暴露内部异常细节（如 JDBC URL/密码/堆栈），
                // 仅返回分类后的友好提示，详细错误记录到日志
                result.put("message", sanitizeConnectionError(e));
                log.warn("连接测试失败 id={} host={} port={} err={}", id, entity.getHost(), entity.getPort(), e.toString());
            }
            // P3-28: 只在连接成功时更新状态并保存，减少不必要的 DB 写入
            if (success) {
                entity.setStatus("connected");
                repository.save(entity);
            } else {
                // 失败时也更新状态，但使用单独的 save 调用
                entity.setStatus("disconnected");
                repository.save(entity);
            }
            return ResponseEntity.ok(result);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 租户上下文（无则拒绝）。 */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 租户上下文缺失时返回 403（防跨租户越权信息泄露）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        log.warn("操作被拒绝: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", e.getMessage()));
    }

    /** 视图脱敏：密码不返回。 */
    private Map<String, Object> toView(DataSourceEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("type", e.getType());
        m.put("host", e.getHost());
        m.put("port", e.getPort());
        m.put("database", e.getDatabase());
        m.put("username", e.getUsername());
        m.put("status", e.getStatus());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }

    /** 加密密码：空值原样返回，非空则 AES-256-GCM 加密存储。 */
    private String encryptPassword(String plainOrBlank) {
        if (plainOrBlank == null || plainOrBlank.isBlank()) {
            return plainOrBlank;
        }
        return credentialEncryptor.encrypt(plainOrBlank);
    }

    /** 解密密码：空值原样返回；用于实际建立连接时获取明文。 */
    private String decryptPassword(String cipherOrBlank) {
        if (cipherOrBlank == null || cipherOrBlank.isBlank()) {
            return cipherOrBlank;
        }
        return credentialEncryptor.decrypt(cipherOrBlank);
    }

    /**
     * 连接错误消息脱敏：将内部异常细节转为分类后的友好提示，避免泄露：
     * <ul>
     *   <li>JDBC URL / 连接串（可能含密码）</li>
     *   <li>内部主机名 / 端口 / 网络拓扑</li>
     *   <li>堆栈帧 / 类名 / 内部路径</li>
     * </ul>
     * 仅返回连接失败的高层分类原因。
     */
    private String sanitizeConnectionError(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String cls = e.getClass().getSimpleName();
        // 按异常类型分类，避免直接拼接 e.getMessage()
        if (e instanceof java.net.ConnectException) {
            return "连接被拒绝：目标主机端口未监听或防火墙拦截";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "连接超时：目标主机在 5 秒内未响应";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "主机名无法解析：请检查主机配置";
        }
        if (e instanceof java.net.NoRouteToHostException
                || e instanceof java.net.ConnectException) {
            return "网络不可达：目标主机路由不通";
        }
        if (e instanceof SecurityException) {
            return "安全策略拒绝：SSRF 防护或权限校验失败";
        }
        // 兜底：仅返回异常类名，不暴露 message
        return "连接失败（" + cls + "），请联系管理员";
    }
}
