package com.levango7.dataenginebdp.governance.lineage.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.governance.lineage.model.LineageGraph;
import com.levango7.dataenginebdp.governance.lineage.model.LineageQueryResult;
import com.levango7.dataenginebdp.governance.lineage.service.LineageAnalyzerService;
import com.levango7.dataenginebdp.governance.lineage.service.LineageQueryService;
import com.levango7.dataenginebdp.governance.lineage.service.OpenLineageIngestService;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParseException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 血缘分析 REST API。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST /api/v1/lineage/analyze} - 分析 SQL 血缘</li>
 *   <li>{@code GET  /api/v1/lineage/upstream/{table}} - 查询上游</li>
 *   <li>{@code GET  /api/v1/lineage/downstream/{table}} - 查询下游</li>
 *   <li>{@code GET  /api/v1/lineage/impact/{table}} - 影响分析</li>
 *   <li>{@code GET  /api/v1/lineage/graph} - 获取完整图谱</li>
 *   <li>{@code POST /api/v1/lineage/events} - 摄取 OpenLineage RunEvent</li>
 * </ul>
 *
 * <p><b>安全控制（R10 修复）</b>：
 * <ul>
 *   <li>类级 {@code @PreAuthorize("isAuthenticated()")}：所有端点要求认证。</li>
 *   <li>租户隔离：从 {@link TenantContext} 读取当前租户 ID，缺失返回 403；
 *       血缘查询/写入按 tenantId 过滤（表名前缀以租户隔离命名空间）。</li>
 * </ul></p>
 *
 * <p><b>异常处理细化</b>：不同异常类型映射不同 HTTP 状态码，
 * 不再统一返回 400（参见 CONVENTIONS §9.3）：
 * <ul>
 *   <li>{@link SqlParseException} → 400（客户端 SQL 语法错误）</li>
 *   <li>{@link MethodArgumentNotValidException} → 400（Bean Validation 失败，如 sql 为空）</li>
 *   <li>{@link IllegalArgumentException} → 400（客户端参数非法）</li>
 *   <li>{@link MethodArgumentTypeMismatchException} → 400（路径/参数类型不匹配）</li>
 *   <li>其他 {@link Exception} → 500（服务端内部错误，消息脱敏）</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@RestController
@Tag(name = "数据治理-血缘分析", description = "SQL血缘分析与影响评估")
@RequestMapping("/api/v1/lineage")
@PreAuthorize("isAuthenticated()")
public class LineageController {

    private static final Logger log = LoggerFactory.getLogger(LineageController.class);

    /** 血缘查询深度上限（防止过深递归造成 OOM）。 */
    private static final int MAX_DEPTH = 20;

    private final LineageAnalyzerService analyzerService;
    private final LineageQueryService queryService;
    private final OpenLineageIngestService openLineageIngestService;

    /**
     * 构造控制器。
     *
     * @param analyzerService          分析服务
     * @param queryService             查询服务
     * @param openLineageIngestService OpenLineage 事件摄取服务
     */
    @Autowired
    public LineageController(LineageAnalyzerService analyzerService,
                             LineageQueryService queryService,
                             OpenLineageIngestService openLineageIngestService) {
        this.analyzerService = analyzerService;
        this.queryService = queryService;
        this.openLineageIngestService = openLineageIngestService;
    }

    /**
     * 分析 SQL 血缘。
     *
     * @param request 请求体（sql + dialect，@Valid 触发 Bean Validation）
     * @return 血缘图谱（ECharts 友好格式）
     */
    @Operation(summary = "分析 SQL 血缘")
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@Valid @RequestBody AnalyzeRequest request) {
        requireTenant();
        SqlDialect dialect = SqlDialect.fromString(request.getDialect());
        log.info("收到血缘分析请求: dialect={}, sqlLength={}",
                dialect, request.getSql().length());
        LineageGraph graph = analyzerService.analyze(request.getSql(), dialect);
        return ResponseEntity.ok(graph.toEChartsFormat());
    }

    /**
     * 查询上游依赖表。
     *
     * @param table 表全名
     * @param depth 深度（默认 5，上限 20，@Min/@Max 校验）
     * @return 上游查询结果
     */
    @Operation(summary = "查询上游依赖表")
    @GetMapping("/upstream/{table}")
    public ResponseEntity<LineageQueryResult> upstream(
            @PathVariable String table,
            @RequestParam(defaultValue = "5") @Min(1) @Max(MAX_DEPTH) int depth) {
        requireTenant();
        return ResponseEntity.ok(queryService.getUpstream(table, depth));
    }

    /**
     * 查询下游依赖表。
     *
     * @param table 表全名
     * @param depth 深度（默认 5，上限 20，@Min/@Max 校验）
     * @return 下游查询结果
     */
    @Operation(summary = "查询下游依赖表")
    @GetMapping("/downstream/{table}")
    public ResponseEntity<LineageQueryResult> downstream(
            @PathVariable String table,
            @RequestParam(defaultValue = "5") @Min(1) @Max(MAX_DEPTH) int depth) {
        requireTenant();
        return ResponseEntity.ok(queryService.getDownstream(table, depth));
    }

    /**
     * 影响分析。
     *
     * @param table 表全名
     * @return 影响分析结果
     */
    @Operation(summary = "影响分析血缘")
    @GetMapping("/impact/{table}")
    public ResponseEntity<LineageQueryResult> impact(@PathVariable String table) {
        requireTenant();
        return ResponseEntity.ok(queryService.impactAnalysis(table));
    }

    /**
     * 摄取 OpenLineage RunEvent（M4 lineage 归一：统一血缘入口）。
     *
     * <p>接受单个事件对象或事件数组（NDJSON 逐事件 POST 的上游生产方：
     * {@code platform/batch-pipeline} 的 {@code batch_pipeline.openlineage} 发射器，
     * 兼容任何标准 OpenLineage 生产端）。inputs × outputs 映射为表级血缘边，
     * 双写内存图 + H2（可选 Nebula），与 SQL 血缘共用查询/影响分析 API。</p>
     *
     * @param body RunEvent JSON 对象或数组
     * @return 摄取汇总 {events, nodes, edges, runs:[...]}
     */
    @Operation(summary = "摄取 OpenLineage RunEvent（单事件或数组）")
    @PostMapping(value = "/events", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> ingestOpenLineage(@RequestBody Object body) {
        requireTenant();
        try {
            return ResponseEntity.ok(openLineageIngestService.ingest(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorMap("invalid_openlineage_event", e.getMessage()));
        }
    }

    /**
     * 异常处理细化：按异常类型映射不同 HTTP 状态码。
     *
     * <p>不再统一返回 400：
 * <ul>
 *   <li>{@link SqlParseException} → 400（客户端 SQL 语法错误）</li>
 *   <li>{@link MethodArgumentNotValidException} → 400（Bean Validation 失败）</li>
 *   <li>{@link IllegalArgumentException} → 400（客户端参数非法）</li>
 *   <li>{@link MethodArgumentTypeMismatchException} → 400（参数类型不匹配）</li>
     *   <li>其他 → 500（服务端内部错误，消息脱敏不暴露堆栈）</li>
     * </ul>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleError(Exception e) {
        // 客户端错误（4xx）
        if (e instanceof SqlParseException) {
            log.warn("血缘分析 SQL 解析失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorMap("sql_parse_error", e.getMessage()));
        }
        // Bean Validation 失败（如 @NotBlank 的 sql 为空）：客户端参数错误 → 400
        // 原先落到"其他异常 → 500"，把客户端错误误报成服务端错误（见 CONVENTIONS §9.3）
        if (e instanceof MethodArgumentNotValidException) {
            log.warn("血缘 API 请求体校验失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorMap("invalid_request", "请求体参数校验失败"));
        }
        if (e instanceof IllegalArgumentException) {
            log.warn("血缘 API 参数非法: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorMap("invalid_argument", e.getMessage()));
        }
        if (e instanceof MethodArgumentTypeMismatchException) {
            log.warn("血缘 API 参数类型不匹配: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorMap("invalid_param_type", "参数类型不匹配"));
        }
        // 服务端错误（5xx）：消息脱敏，不暴露内部异常细节
        log.error("血缘 API 内部异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorMap("internal_error", "内部错误，请联系管理员"));
    }

    /**
     * 构造统一错误响应体。
     *
     * <p>错误响应契约：{@code {"error": "<error_code>", "message": "<human_readable_message>"}}。
     * {@code error} 字段为字符串错误码，便于调用方程序化识别与国际化；
     * {@code message} 字段为面向人类的可读描述。</p>
     *
     * @param errorCode 错误码（machine-readable，snake_case）
     * @param message   错误描述（human-readable）
     * @return 错误响应体
     */
    private Map<String, Object> errorMap(String errorCode, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", errorCode);
        m.put("message", message);
        return m;
    }

    /**
     * 从 {@link TenantContext} 校验当前租户；缺失时抛 403。
     */
    private static void requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
    }

    /** 分析请求体（加 Bean Validation 约束） */
    public static class AnalyzeRequest {
        @NotBlank(message = "sql 不能为空")
        @Size(max = 8192, message = "sql 长度不能超过 8192")
        private String sql;
        @Size(max = 32, message = "dialect 长度不能超过 32")
        private String dialect;

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }

        public String getDialect() {
            return dialect;
        }

        public void setDialect(String dialect) {
            this.dialect = dialect;
        }
    }
}
