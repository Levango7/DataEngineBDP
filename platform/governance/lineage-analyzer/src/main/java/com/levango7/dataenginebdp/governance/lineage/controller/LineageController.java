package com.levango7.dataenginebdp.governance.lineage.controller;

import com.levango7.dataenginebdp.governance.lineage.model.LineageGraph;
import com.levango7.dataenginebdp.governance.lineage.model.LineageQueryResult;
import com.levango7.dataenginebdp.governance.lineage.service.LineageAnalyzerService;
import com.levango7.dataenginebdp.governance.lineage.service.LineageQueryService;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
 * </ul>
 *
 * @author shuqing-bigdata
 */
@RestController
@Tag(name = "数据治理-血缘分析", description = "SQL血缘分析与影响评估")
@RequestMapping("/api/v1/lineage")
public class LineageController {

    private static final Logger log = LoggerFactory.getLogger(LineageController.class);

    private final LineageAnalyzerService analyzerService;
    private final LineageQueryService queryService;

    /**
     * 构造控制器。
     *
     * @param analyzerService 分析服务
     * @param queryService    查询服务
     */
    @Autowired
    public LineageController(LineageAnalyzerService analyzerService,
                             LineageQueryService queryService) {
        this.analyzerService = analyzerService;
        this.queryService = queryService;
    }

    /**
     * 分析 SQL 血缘。
     *
     * @param request 请求体（sql + dialect）
     * @return 血缘图谱（ECharts 友好格式）
     */
    @Operation(summary = "分析 SQL 血缘")
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody AnalyzeRequest request) {
        if (request == null || request.getSql() == null || request.getSql().isBlank()) {
            return ResponseEntity.badRequest().body(errorMap("invalid_request", "sql 不能为空"));
        }
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
     * @param depth 深度（默认 5）
     * @return 上游查询结果
     */
    @Operation(summary = "查询上游依赖表")
    @GetMapping("/upstream/{table}")
    public ResponseEntity<LineageQueryResult> upstream(
            @PathVariable String table,
            @RequestParam(defaultValue = "5") int depth) {
        return ResponseEntity.ok(queryService.getUpstream(table, depth));
    }

    /**
     * 查询下游依赖表。
     *
     * @param table 表全名
     * @param depth 深度（默认 5）
     * @return 下游查询结果
     */
    @Operation(summary = "查询下游依赖表")
    @GetMapping("/downstream/{table}")
    public ResponseEntity<LineageQueryResult> downstream(
            @PathVariable String table,
            @RequestParam(defaultValue = "5") int depth) {
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
        return ResponseEntity.ok(queryService.impactAnalysis(table));
    }

    /**
     * 异常处理。
     *
     * @param e 异常
     * @return 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleError(Exception e) {
        log.error("血缘 API 异常", e);
        return ResponseEntity.badRequest().body(errorMap("lineage_analysis_failed", e.getMessage()));
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

    /** 分析请求体 */
    public static class AnalyzeRequest {
        @NotBlank
        private String sql;
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