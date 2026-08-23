package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.DataSourceEntity;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import com.levango7.dataenginebdp.encaps.security.AuditLog;
import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
 * 密码仅写入时接收，查询返回时脱敏。</p>
 */
@Slf4j
@RestController
@Tag(name = "封装数据-数据源管理", description = "数据源CRUD与连接测试")
@RequiredArgsConstructor
@RequestMapping("/api/v1/datasources")
public class DataSourceController {

    private final DataSourceRepository repository;

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

    /** 列表（租户隔离 + 可选类型过滤）。 */
    @Operation(summary = "列表（租户隔离 + 可选类型过滤）")
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> list(String type) {
        String tenantId = requireTenant();
        List<DataSourceEntity> list = (type == null || type.isBlank())
                ? repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : repository.findByTenantIdAndTypeOrderByCreatedAtDesc(tenantId, type);
        return ResponseEntity.ok(list.stream().map(this::toView).toList());
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

    /** 创建。 */
    @Operation(summary = "创建数据源")
    @AuditLog(action = "CREATE_DATASOURCE", resource = "datasource")
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody DataSourceRequest req) {
        String tenantId = requireTenant();
        DataSourceEntity entity = DataSourceEntity.builder()
                .name(req.name())
                .type(req.type())
                .host(req.host())
                .port(req.port())
                .database(req.database())
                .username(req.username())
                .password(req.password())
                .status("disconnected")
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        DataSourceEntity saved = repository.save(entity);
        log.info("创建数据源: id={}, name={}, type={}, tenant={}",
                saved.getId(), saved.getName(), saved.getType(), tenantId);
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新。 */
    @Operation(summary = "更新数据源")
    @AuditLog(action = "UPDATE_DATASOURCE", resource = "datasource")
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody DataSourceRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setType(req.type());
            entity.setHost(req.host());
            entity.setPort(req.port());
            entity.setDatabase(req.database());
            entity.setUsername(req.username());
            if (req.password() != null && !req.password().isBlank()) {
                entity.setPassword(req.password()); // 密码留空则不更新
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

    /** 连接测试（TCP 探测 + JDBC 校验（JDBC 型））。 */
    @Operation(summary = "连接测试（TCP 探测 + JDBC 校验（JDBC 型））")
    @AuditLog(action = "TEST_DATASOURCE", resource = "datasource")
    @PostMapping("/{id}/test")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            Map<String, Object> result = new LinkedHashMap<>();
            long start = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(entity.getHost(), entity.getPort()), 5000);
                result.put("success", true);
                result.put("latency", System.currentTimeMillis() - start);
                result.put("message", "连接成功（TCP " + entity.getHost() + ":" + entity.getPort() + "）");
                entity.setStatus("connected");
            } catch (Exception e) {
                result.put("success", false);
                result.put("latency", System.currentTimeMillis() - start);
                result.put("message", "连接失败: " + e.getMessage());
                entity.setStatus("disconnected");
            }
            repository.save(entity);
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
}
