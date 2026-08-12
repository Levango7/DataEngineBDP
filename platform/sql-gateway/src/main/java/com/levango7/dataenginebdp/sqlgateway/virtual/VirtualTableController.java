package com.levango7.dataenginebdp.sqlgateway.virtual;

import com.levango7.dataenginebdp.sqlgateway.security.TenantContext;
import com.levango7.dataenginebdp.sqlgateway.virtual.adapter.VirtualAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 虚拟表管理 REST 控制器。
 *
 * <p>暴露虚拟表 CRUD、查询、schema 获取、连接测试、物化刷新等端点。
 * 全部端点需认证，租户 ID 从 JWT 的 {@code tenantId} claim 提取（由
 * {@code JwtAuthFilter} 写入 {@link TenantContext}），实现租户级隔离。</p>
 *
 * <p>端点清单（前缀 {@code /api/v1/virtual-tables}）：</p>
 *
 * <table>
 *   <caption>表：虚拟表 API 端点说明</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/</td><td>注册虚拟表</td></tr>
 *   <tr><td>GET</td><td>/</td><td>列出当前租户全部虚拟表</td></tr>
 *   <tr><td>GET</td><td>/{tableName}</td><td>获取单个虚拟表定义</td></tr>
 *   <tr><td>PUT</td><td>/{tableName}</td><td>更新虚拟表定义</td></tr>
 *   <tr><td>DELETE</td><td>/{tableName}</td><td>删除虚拟表</td></tr>
 *   <tr><td>POST</td><td>/{tableName}/query</td><td>查询虚拟表数据</td></tr>
 *   <tr><td>GET</td><td>/{tableName}/schema</td><td>获取虚拟表 schema</td></tr>
 *   <tr><td>POST</td><td>/{tableName}/test-connection</td><td>测试连接</td></tr>
 *   <tr><td>POST</td><td>/{tableName}/refresh</td><td>手动刷新物化表</td></tr>
 *   <tr><td>GET</td><td>/cache/stats</td><td>缓存统计</td></tr>
 *   <tr><td>GET</td><td>/types</td><td>支持的数据源类型</td></tr>
 * </table>
 *
 * @author shuqing-bigdata
 */
@RestController
@RequestMapping("/api/v1/virtual-tables")
public class VirtualTableController {

    private static final Logger log = LoggerFactory.getLogger(VirtualTableController.class);

    private final VirtualTableService virtualTableService;

    /**
     * 构造控制器。
     *
     * @param virtualTableService 虚拟表业务服务
     */
    public VirtualTableController(VirtualTableService virtualTableService) {
        this.virtualTableService = virtualTableService;
    }

    /**
     * 注册虚拟表。
     *
     * @param definition 虚拟表定义
     * @return 已注册的虚拟表定义；表名已存在返回 409
     */
    @PostMapping
    public ResponseEntity<?> register(@RequestBody VirtualTableDefinition definition) {
        String tenantId = resolveTenantId(definition.getTenantId());
        definition.setTenantId(tenantId);
        try {
            VirtualTableDefinition saved = virtualTableService.register(definition);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出当前租户的全部虚拟表。
     *
     * @param dataSourceType 可选过滤：数据源类型
     * @return 虚拟表定义列表
     */
    @GetMapping
    public ResponseEntity<List<VirtualTableDefinition>> list(
            @RequestParam(required = false) String dataSourceType) {
        String tenantId = resolveTenantId(null);
        if (dataSourceType != null && !dataSourceType.isBlank()) {
            DataSourceType type = DataSourceType.fromString(dataSourceType);
            return ResponseEntity.ok(virtualTableService.listByType(tenantId, type));
        }
        return ResponseEntity.ok(virtualTableService.list(tenantId));
    }

    /**
     * 获取单个虚拟表定义。
     *
     * @param tableName 虚拟表名
     * @return 虚拟表定义；不存在返回 404
     */
    @GetMapping("/{tableName}")
    public ResponseEntity<VirtualTableDefinition> get(@PathVariable String tableName) {
        String tenantId = resolveTenantId(null);
        Optional<VirtualTableDefinition> def = virtualTableService.get(tenantId, tableName);
        return def.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 更新虚拟表定义。
     *
     * @param tableName     虚拟表名
     * @param definition    新字段值
     * @return 更新后的虚拟表定义；不存在返回 404
     */
    @PutMapping("/{tableName}")
    public ResponseEntity<?> update(@PathVariable String tableName,
                                    @RequestBody VirtualTableDefinition definition) {
        String tenantId = resolveTenantId(null);
        try {
            Optional<VirtualTableDefinition> updated = virtualTableService.update(tenantId, tableName, definition);
            return updated.map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除虚拟表。
     *
     * @param tableName 虚拟表名
     * @return 204 删除成功；不存在返回 404
     */
    @DeleteMapping("/{tableName}")
    public ResponseEntity<Void> delete(@PathVariable String tableName) {
        String tenantId = resolveTenantId(null);
        boolean deleted = virtualTableService.delete(tenantId, tableName);
        return deleted ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * 查询虚拟表数据。
     *
     * @param tableName 虚拟表名
     * @param request   查询请求（predicate、limit）
     * @return 查询结果
     */
    @PostMapping("/{tableName}/query")
    public ResponseEntity<?> query(@PathVariable String tableName,
                                   @RequestBody(required = false) QueryRequest request) {
        String tenantId = resolveTenantId(null);
        String predicate = request != null ? request.getPredicate() : null;
        Integer limit = request != null ? request.getLimit() : null;
        try {
            VirtualAdapter.QueryResult result = virtualTableService.query(tenantId, tableName, predicate, limit);
            return ResponseEntity.ok(Map.of(
                    "columns", result.columns(),
                    "rows", result.rows(),
                    "rowCount", result.rowCount()
            ));
        } catch (com.levango7.dataenginebdp.sqlgateway.virtual.adapter.VirtualAdapterException e) {
            log.error("虚拟表查询失败 table={} code={} msg={}", tableName, e.getErrorCode(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getErrorCode() + ": " + e.getMessage()));
        }
    }

    /**
     * 获取虚拟表 schema。
     *
     * @param tableName 虚拟表名
     * @return 列定义列表
     */
    @GetMapping("/{tableName}/schema")
    public ResponseEntity<List<ColumnDefinition>> getSchema(@PathVariable String tableName) {
        String tenantId = resolveTenantId(null);
        return ResponseEntity.ok(virtualTableService.getSchema(tenantId, tableName));
    }

    /**
     * 测试虚拟表连接。
     *
     * @param tableName 虚拟表名
     * @return {@code {"connected": true/false}}
     */
    @PostMapping("/{tableName}/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable String tableName) {
        String tenantId = resolveTenantId(null);
        Optional<VirtualTableDefinition> def = virtualTableService.get(tenantId, tableName);
        if (def.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean connected = virtualTableService.testConnection(def.get());
        return ResponseEntity.ok(Map.of("connected", connected));
    }

    /**
     * 手动刷新物化表。
     *
     * @param tableName 虚拟表名
     * @return 刷新结果（行数）
     */
    @PostMapping("/{tableName}/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable String tableName) {
        String tenantId = resolveTenantId(null);
        try {
            int rows = virtualTableService.refreshMaterialization(tenantId, tableName);
            return ResponseEntity.ok(Map.of("refreshed", true, "rows", rows));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 获取元数据缓存统计信息。
     *
     * @return 缓存统计
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> cacheStats() {
        return ResponseEntity.ok(virtualTableService.getCacheStats());
    }

    /**
     * 列出支持的数据源类型。
     *
     * @return 数据源类型列表
     */
    @GetMapping("/types")
    public ResponseEntity<List<String>> listTypes() {
        return ResponseEntity.ok(java.util.Arrays.stream(DataSourceType.values())
                .map(Enum::name)
                .toList());
    }

    /**
     * 解析租户 ID：优先使用 TenantContext（来自 JWT），其次使用请求体中的 tenantId。
     *
     * @param fallback 请求体中的租户 ID（可选）
     * @return 租户 ID
     */
    private String resolveTenantId(String fallback) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return "default";
    }

    /**
     * 查询请求体。
     */
    public static class QueryRequest {
        private String predicate;
        private Integer limit;

        public String getPredicate() {
            return predicate;
        }

        public void setPredicate(String predicate) {
            this.predicate = predicate;
        }

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }
    }
}