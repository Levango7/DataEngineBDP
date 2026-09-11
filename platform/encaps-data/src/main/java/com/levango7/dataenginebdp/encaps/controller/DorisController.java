package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.engine.DorisClient;
import com.levango7.dataenginebdp.encaps.service.engine.EngineUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Doris 引擎端点（ROADMAP 前后端接线：前端 /doris）。
 *
 * <p>提供 Doris 节点、数据库、表、查询记录与 SQL 执行。
 * 统一前缀：{@code /api/v1/doris}</p>
 *
 * <ul>
 *   <li>GET  /nodes                       — Doris 节点列表（FE + BE）</li>
 *   <li>GET  /databases                   — 数据库列表</li>
 *   <li>GET  /tables?database=xxx         — 表列表（任务要求）</li>
 *   <li>GET  /databases/{db}/tables       — 指定库的表列表（前端用）</li>
 *   <li>POST /query                       — 执行 SQL 查询（任务要求）</li>
 *   <li>GET  /queries                     — 查询记录（前端用）</li>
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "封装数据-Doris引擎", description = "Doris节点/库/表/查询管理")
@RequiredArgsConstructor
@RequestMapping("/api/v1/doris")
public class DorisController {

    private final DorisClient dorisClient;

    /** 危险 SQL 关键词全词匹配正则（大小写不敏感，词边界匹配防绕过）。 */
    private static final Pattern DANGEROUS_SQL_PATTERN = Pattern.compile(
            "\\b(DROP|ALTER|DELETE|INSERT|UPDATE|TRUNCATE|CREATE|GRANT|REVOKE)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 只读查询起始关键词正则（大小写不敏感）。 */
    private static final Pattern READONLY_SQL_PATTERN = Pattern.compile(
            "^\\s*(SELECT|SHOW|DESCRIBE|EXPLAIN)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Doris 节点列表。 */
    @Operation(summary = "Doris 节点列表")
    @GetMapping("/nodes")
    public ResponseEntity<?> listNodes() {
        String tenantId = requireTenant();
        log.info("列出 Doris 节点: tenant={}", tenantId);
        try {
            return ResponseEntity.ok(dorisClient.listNodes());
        } catch (EngineUnavailableException e) {
            log.warn("Doris 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Doris 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 数据库列表。 */
    @Operation(summary = "查询Doris列表")
    @GetMapping("/databases")
    public ResponseEntity<?> listDatabases() {
        String tenantId = requireTenant();
        log.info("列出 Doris 数据库: tenant={}", tenantId);
        try {
            return ResponseEntity.ok(dorisClient.listDatabases());
        } catch (EngineUnavailableException e) {
            log.warn("Doris 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Doris 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 表列表（query 参数指定数据库，任务要求）。 */
    @Operation(summary = "表列表（query 参数指定数据库，任务要求）")
    @GetMapping("/tables")
    public ResponseEntity<?> listTables(@RequestParam String database) {
        String tenantId = requireTenant();
        log.info("列出 Doris 表: db={}, tenant={}", database, tenantId);
        try {
            return ResponseEntity.ok(dorisClient.listTables(database));
        } catch (EngineUnavailableException e) {
            log.warn("Doris 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Doris 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 指定数据库下的表列表（路径参数，前端用）。 */
    @Operation(summary = "指定数据库下的表列表（路径参数，前端用）")
    @GetMapping("/databases/{db}/tables")
    public ResponseEntity<?> listTablesByDb(@PathVariable String db) {
        String tenantId = requireTenant();
        log.info("列出 Doris 表: db={}, tenant={}", db, tenantId);
        try {
            return ResponseEntity.ok(dorisClient.listTables(db));
        } catch (EngineUnavailableException e) {
            log.warn("Doris 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Doris 引擎不可用", "message", e.getMessage()));
        }
    }

    /** SQL 查询请求体。 */
    public record QueryRequest(String sql) {
    }

    /** 执行 SQL 查询（仅允许 SELECT/SHOW/DESCRIBE/EXPLAIN）。 */
    @Operation(summary = "执行Doris")
    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody QueryRequest req) {
        String tenantId = requireTenant();
        log.info("执行 Doris SQL: tenant={}", tenantId);
        // SQL 安全校验：只允许只读查询
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
            return ResponseEntity.ok(dorisClient.executeQuery(req.sql()));
        } catch (EngineUnavailableException e) {
            log.warn("Doris 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Doris 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 查询记录（前端用，Doris 暂未提供查询历史接口，返回空列表）。 */
    @Operation(summary = "查询记录（前端用，Doris 暂未提供查询历史接口，返回空列表）")
    @GetMapping("/queries")
    public ResponseEntity<List<Map<String, Object>>> listQueries() {
        String tenantId = requireTenant();
        log.info("列出 Doris 查询: tenant={}", tenantId);
        return ResponseEntity.ok(List.of());
    }

    /** 租户上下文校验（无则拒绝，防跨租户越权）。 */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }
}
