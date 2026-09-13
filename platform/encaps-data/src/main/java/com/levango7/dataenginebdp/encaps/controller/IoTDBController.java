package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.DataSourceEntity;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import com.levango7.dataenginebdp.encaps.util.CredentialEncryptor;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.engine.EngineUnavailableException;
import com.levango7.dataenginebdp.encaps.service.engine.IoTDBClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * IoTDB 引擎端点（ROADMAP 前后端接线：前端 /iotdb）。
 *
 * <p>提供 IoTDB 存储组、设备、时序列表与 SQL 查询、写入吞吐查询。
 * 统一前缀：{@code /api/v1/iotdb}</p>
 *
 * <p>id 对应数据源表中 type=iotdb 的记录 ID，从中读取连接信息（host/port/username/password）。</p>
 *
 * <ul>
 *   <li>GET  /{id}/storage-groups    — 存储组列表</li>
 *   <li>GET  /{id}/devices           — 设备列表</li>
 *   <li>GET  /{id}/timeseries        — 时序列表（参数：device）</li>
 *   <li>POST /{id}/query             — 执行 SQL 查询（任务要求）</li>
 *   <li>GET  /{id}/write-throughput  — 写入吞吐</li>
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "封装数据-IoTDB引擎", description = "IoTDB时序数据查询与管理")
@RequiredArgsConstructor
@RequestMapping("/api/v1/iotdb")
@PreAuthorize("isAuthenticated()")  // R16 安全修复：类级认证校验
public class IoTDBController {

    /** 危险 SQL 关键词全词匹配正则（大小写不敏感，词边界匹配防绕过）。 */
    private static final Pattern DANGEROUS_SQL_PATTERN = Pattern.compile(
            "\\b(DROP|ALTER|DELETE|INSERT|UPDATE|TRUNCATE|CREATE|GRANT|REVOKE)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 只读查询起始关键词正则（大小写不敏感）。 */
    private static final Pattern READONLY_SQL_PATTERN = Pattern.compile(
            "^\\s*(SELECT|SHOW|DESCRIBE|EXPLAIN)\\b",
            Pattern.CASE_INSENSITIVE);

    /** IoTDB device/时序名白名单：仅允许字母数字下划线点（防 SQL 注入）。 */
    private static final Pattern DEVICE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");

    private final IoTDBClient ioTdbClient;
    private final DataSourceRepository dataSourceRepository;
    private final CredentialEncryptor credentialEncryptor;

    /** 存储组列表。 */
    @Operation(summary = "查询IoTDB列表")
    @GetMapping("/{id}/storage-groups")
    public ResponseEntity<?> listStorageGroups(@PathVariable String id) {
        log.info("列出 IoTDB 存储组: id={}, tenant={}", id, TenantContext.getTenantId());
        try {
            var conn = resolveConn(id);
            return ResponseEntity.ok(ioTdbClient.listStorageGroups(conn));
        } catch (EngineUnavailableException e) {
            log.warn("IoTDB 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IoTDB 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 设备列表。 */
    @Operation(summary = "查询IoTDB列表")
    @GetMapping("/{id}/devices")
    public ResponseEntity<?> listDevices(@PathVariable String id) {
        log.info("列出 IoTDB 设备: id={}, tenant={}", id, TenantContext.getTenantId());
        try {
            var conn = resolveConn(id);
            return ResponseEntity.ok(ioTdbClient.listDevices(conn));
        } catch (EngineUnavailableException e) {
            log.warn("IoTDB 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IoTDB 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 时序列表。 */
    @Operation(summary = "查询IoTDB列表")
    @GetMapping("/{id}/timeseries")
    public ResponseEntity<?> listTimeseries(
            @PathVariable String id,
            @RequestParam(required = false) String device) {
        log.info("列出 IoTDB 时序: id={}, device={}, tenant={}",
                id, device, TenantContext.getTenantId());
        // R16 安全修复：device 参数白名单校验（防 SQL 注入）
        if (device != null && !device.isBlank() && !DEVICE_NAME_PATTERN.matcher(device).matches()) {
            log.warn("非法 IoTDB device 参数: id={}, device={}", id, device);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "invalid device",
                            "message", "device 参数仅允许字母数字下划线点"));
        }
        try {
            var conn = resolveConn(id);
            return ResponseEntity.ok(ioTdbClient.listTimeseries(conn, device));
        } catch (EngineUnavailableException e) {
            log.warn("IoTDB 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IoTDB 引擎不可用", "message", e.getMessage()));
        }
    }

    /** SQL 查询请求体。 */
    public record QueryRequest(String sql) {
    }

    /** 执行 SQL 查询（任务要求）。 */
    @Operation(summary = "执行 SQL 查询（任务要求）")
    @PostMapping("/{id}/query")
    public ResponseEntity<?> executeQuery(@PathVariable String id,
                                          @RequestBody QueryRequest req) {
        log.info("执行 IoTDB SQL: id={}, tenant={}", id, TenantContext.getTenantId());
        // R16 安全修复：SQL 安全校验（参照 DorisController.executeQuery）
        String sql = req.sql();
        if (sql == null || sql.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL 不能为空"));
        }
        // 禁止分号多语句（防 SQL 注入堆叠）
        if (sql.contains(";")) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "不允许执行多语句查询（含分号）"));
        }
        // 全词匹配危险关键词（防 "SELECT * FROM t; DROP TABLE" 绕过及 "DROP_TABLE" 变体）
        if (DANGEROUS_SQL_PATTERN.matcher(sql).find()) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "不允许执行危险 SQL 操作（DDL/DML 写操作）"));
        }
        // 必须以只读关键词开头
        if (!READONLY_SQL_PATTERN.matcher(sql).find()) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "只允许执行 SELECT/SHOW/DESCRIBE/EXPLAIN 查询"));
        }
        try {
            var conn = resolveConn(id);
            return ResponseEntity.ok(ioTdbClient.executeQuery(conn, req.sql()));
        } catch (EngineUnavailableException e) {
            log.warn("IoTDB 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IoTDB 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 写入吞吐。 */
    @Operation(summary = "查询IoTDB详情")
    @GetMapping("/{id}/write-throughput")
    public ResponseEntity<?> getWriteThroughput(@PathVariable String id) {
        log.info("查询 IoTDB 写入吞吐: id={}, tenant={}", id, TenantContext.getTenantId());
        try {
            var conn = resolveConn(id);
            List<Map<String, Object>> throughput = ioTdbClient.getWriteThroughput(conn);
            return ResponseEntity.ok(throughput);
        } catch (EngineUnavailableException e) {
            log.warn("IoTDB 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "IoTDB 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 根据 id 解析 IoTDB 连接参数 */
    private IoTDBClient.ConnParams resolveConn(String id) {
        String tenantId = requireTenant();
        Long pk;
        try {
            pk = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new EngineUnavailableException("无效的实例 ID: " + id);
        }
        DataSourceEntity ds = dataSourceRepository.findByIdAndTenantId(pk, tenantId)
                .orElseThrow(() -> new EngineUnavailableException("IoTDB 实例不存在: " + id));
        if (!"iotdb".equalsIgnoreCase(ds.getType())) {
            throw new EngineUnavailableException("数据源 " + id + " 不是 IoTDB 类型");
        }
        String jdbcUrl = "jdbc:iotdb://" + ds.getHost() + ":" + ds.getPort() + "/";
        String user = ds.getUsername() != null ? ds.getUsername() : "root";
        // R16 安全修复：密码解密后再传给 IoTDBClient
        String pass = "root";
        if (ds.getPassword() != null && !ds.getPassword().isBlank()) {
            pass = credentialEncryptor.decrypt(ds.getPassword());
        }
        return ioTdbClient.connParams(jdbcUrl, user, pass);
    }

    /**
     * 解析租户 ID：fail-closed 租户校验（R14 安全修复）。
     *
     * <p>仅信任 TenantContext（来自 JWT），若未设置则拒绝请求（返回 403），
     * 不回退到默认值，避免未认证请求绕过租户隔离。</p>
     *
     * @return 租户 ID
     * @throws IllegalStateException 若 TenantContext 未设置租户 ID
     */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /**
     * 异常处理：缺少租户上下文返回 403（R14 安全修复）。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        log.warn("IoTDB 操作被拒绝: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", e.getMessage()));
    }
}
