package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Doris 引擎端点（ROADMAP 前后端接线：前端 /doris）。
 *
 * <p>提供 Doris 节点、数据库、表、查询记录查询。
 * 统一前缀：{@code /api/v1/doris}</p>
 *
 * <ul>
 *   <li>GET /nodes                              — Doris 节点列表</li>
 *   <li>GET /databases                          — 数据库列表</li>
 *   <li>GET /databases/{db}/tables              — 数据库下表列表</li>
 *   <li>GET /queries                            — 查询记录</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/doris")
public class DorisController {

    /** Doris 节点列表。 */
    @GetMapping("/nodes")
    public ResponseEntity<List<Map<String, Object>>> listNodes() {
        // TODO: 接入 Doris FE REST API /api/show_proc?path=/backends
        log.info("列出 Doris 节点: tenant={}", TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }

    /** 数据库列表。 */
    @GetMapping("/databases")
    public ResponseEntity<List<String>> listDatabases() {
        // TODO: 接入 Doris FE 查询 information_schema
        log.info("列出 Doris 数据库: tenant={}", TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }

    /** 数据库下表列表。 */
    @GetMapping("/databases/{db}/tables")
    public ResponseEntity<List<String>> listTables(@PathVariable String db) {
        // TODO: 接入 Doris FE 查询指定库的表
        log.info("列出 Doris 表: db={}, tenant={}", db, TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }

    /** 查询记录。 */
    @GetMapping("/queries")
    public ResponseEntity<List<Map<String, Object>>> listQueries() {
        // TODO: 接入 Doris FE 查询记录
        log.info("列出 Doris 查询: tenant={}", TenantContext.getTenantId());
        return ResponseEntity.ok(List.of());
    }
}