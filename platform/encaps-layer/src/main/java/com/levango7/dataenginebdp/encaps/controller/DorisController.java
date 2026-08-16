package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.security.TenantContext;
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

import java.util.List;
import java.util.Map;

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
@RequiredArgsConstructor
@RequestMapping("/api/v1/doris")
public class DorisController {

    private final DorisClient dorisClient;

    /** Doris 节点列表。 */
    @GetMapping("/nodes")
    public ResponseEntity<?> listNodes() {
        log.info("列出 Doris 节点: tenant={}", TenantContext.getTenantId());
        try {
            return ResponseEntity.ok(dorisClient.listNodes());
        } catch (EngineUnavailableException e) {
            log.warn("Doris 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Doris 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 数据库列表。 */
    @GetMapping("/databases")
    public ResponseEntity<?> listDatabases() {
        log.info("列出 Doris 数据库: tenant={}", TenantContext.getTenantId());
        try {
            return ResponseEntity.ok(dorisClient.listDatabases());
        } catch (EngineUnavailableException e) {
            log.warn("Doris 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Doris 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 表列表（query 参数指定数据库，任务要求）。 */
    @GetMapping("/tables")
    public ResponseEntity<?> listTables(@RequestParam String database) {
        log.info("列出 Doris 表: db={}, tenant={}", database, TenantContext.getTenantId());
        try {
            return ResponseEntity.ok(dorisClient.listTables(database));
        } catch (EngineUnavailableException e) {
            log.warn("Doris 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Doris 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 指定数据库下的表列表（路径参数，前端用）。 */
    @GetMapping("/databases/{db}/tables")
    public ResponseEntity<?> listTablesByDb(@PathVariable String db) {
        log.info("列出 Doris 表: db={}, tenant={}", db, TenantContext.getTenantId());
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

    /** 执行 SQL 查询（任务要求）。 */
    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody QueryRequest req) {
        log.info("执行 Doris SQL: tenant={}", TenantContext.getTenantId());
        try {
            return ResponseEntity.ok(dorisClient.executeQuery(req.sql()));
        } catch (EngineUnavailableException e) {
            log.warn("Doris 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Doris 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 查询记录（前端用，Doris 暂未提供查询历史接口，返回空列表）。 */
    @GetMapping("/queries")
    public ResponseEntity<List<Map<String, Object>>> listQueries() {
        log.info("列出 Doris 查询: tenant={}", TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }
}
