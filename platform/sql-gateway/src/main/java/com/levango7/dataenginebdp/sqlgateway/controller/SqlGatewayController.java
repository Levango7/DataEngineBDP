package com.levango7.dataenginebdp.sqlgateway.controller;

import com.levango7.dataenginebdp.sqlgateway.crosssource.CrossSourceException;
import com.levango7.dataenginebdp.sqlgateway.crosssource.CrossSourceExecutor;
import com.levango7.dataenginebdp.sqlgateway.crosssource.MergeResult;
import com.levango7.dataenginebdp.sqlgateway.crosssource.TenantMismatchException;
import com.levango7.dataenginebdp.sqlgateway.model.CrossSourceExplainResponse;
import com.levango7.dataenginebdp.sqlgateway.model.CrossSourceRequest;
import com.levango7.dataenginebdp.sqlgateway.model.CrossSourceResponse;
import com.levango7.dataenginebdp.sqlgateway.model.RouteRule;
import com.levango7.dataenginebdp.sqlgateway.model.SqlConvertRequest;
import com.levango7.dataenginebdp.sqlgateway.model.SqlConvertResponse;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteRequest;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import com.levango7.dataenginebdp.sqlgateway.model.SqlOptimizeRequest;
import com.levango7.dataenginebdp.sqlgateway.model.SqlOptimizeResponse;
import com.levango7.dataenginebdp.sqlgateway.model.SqlParseRequest;
import com.levango7.dataenginebdp.sqlgateway.model.SqlParseResponse;
import com.levango7.dataenginebdp.sqlgateway.model.SqlValidateResponse;
import com.levango7.dataenginebdp.sqlgateway.optimizer.OptimizationResult;
import com.levango7.dataenginebdp.sqlgateway.optimizer.OptimizationRuleConfig;
import com.levango7.dataenginebdp.sqlgateway.optimizer.SqlOptimizerService;
import com.levango7.dataenginebdp.sqlgateway.parser.ASTNode;
import com.levango7.dataenginebdp.sqlgateway.parser.DialectConverter;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParseException;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParserService;
import com.levango7.dataenginebdp.sqlgateway.service.SqlRoutingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SQL 网关 REST 控制器。
 *
 * <p>暴露统一 SQL 执行、路由管理、引擎查询、SQL 解析/校验/方言转换端点。</p>
 *
 * @author shuqing-bigdata
 */
@RestController
@Tag(name = "SQL网关-统一SQL", description = "SQL执行/解析/优化/跨源")
@RequestMapping("/api/v1/sql")
public class SqlGatewayController {

    private static final Logger log = LoggerFactory.getLogger(SqlGatewayController.class);

    private final SqlRoutingService routingService;
    private final SqlParserService parserService = new SqlParserService();
    private final DialectConverter dialectConverter = new DialectConverter();
    private final SqlOptimizerService optimizerService = new SqlOptimizerService();
    private final CrossSourceExecutor crossSourceExecutor;

    public SqlGatewayController(SqlRoutingService routingService,
                                CrossSourceExecutor crossSourceExecutor) {
        this.routingService = routingService;
        this.crossSourceExecutor = crossSourceExecutor;
    }

    /**
     * 执行 SQL。
     *
     * @param request SQL 执行请求
     * @return SQL 执行响应
     */
    @Operation(summary = "执行 SQL")
    @PostMapping("/execute")
    public ResponseEntity<SqlExecuteResponse> execute(@Valid @RequestBody SqlExecuteRequest request) {
        SqlExecuteResponse response = routingService.execute(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 列出当前所有路由规则。
     *
     * @return 路由规则列表
     */
    @Operation(summary = "列出当前所有路由规则")
    @GetMapping("/routes")
    public ResponseEntity<List<RouteRule>> listRoutes() {
        return ResponseEntity.ok(routingService.listRoutes());
    }

    /**
     * 添加一条路由规则。
     *
     * @param rule 路由规则
     * @return 已保存的路由规则
     */
    @Operation(summary = "添加一条路由规则")
    @PostMapping("/routes")
    public ResponseEntity<RouteRule> addRoute(@RequestBody RouteRule rule) {
        return ResponseEntity.ok(routingService.addRoute(rule));
    }

    /**
     * 列出可用引擎。
     *
     * @return 引擎名称列表
     */
    @Operation(summary = "列出可用引擎")
    @GetMapping("/engines")
    public ResponseEntity<List<String>> listEngines() {
        return ResponseEntity.ok(Arrays.asList("trino", "doris"));
    }

    // ===================== SQL 解析/校验/转换 =====================

    /**
     * 解析 SQL 并返回 AST。
     *
     * @param request SQL 解析请求
     * @return SQL 解析响应
     */
    @Operation(summary = "解析 SQL 并返回 AST")
    @PostMapping("/parse")
    public ResponseEntity<SqlParseResponse> parse(@Valid @RequestBody SqlParseRequest request) {
        SqlDialect dialect = SqlDialect.fromString(request.getDialect());
        ASTNode ast = parserService.parse(request.getSql(), dialect);
        SqlParseResponse response = SqlParseResponse.builder()
                .dialect(dialect.name())
                .statementType(ast.getType().name())
                .properties(ast.getProperties())
                .children(ast.getChildren().stream()
                        .map(SqlGatewayController::toNodeMap)
                        .collect(Collectors.toList()))
                .tables(ast.extractTables())
                .columns(ast.extractColumns())
                .build();
        return ResponseEntity.ok(response);
    }

    /**
     * 校验 SQL 语法。
     *
     * @param request SQL 解析请求
     * @return 校验响应
     */
    @Operation(summary = "校验 SQL 语法")
    @PostMapping("/validate")
    public ResponseEntity<SqlValidateResponse> validate(@Valid @RequestBody SqlParseRequest request) {
        SqlDialect dialect = SqlDialect.fromString(request.getDialect());
        try {
            parserService.parse(request.getSql(), dialect);
            return ResponseEntity.ok(SqlValidateResponse.builder()
                    .valid(true)
                    .dialect(dialect.name())
                    .build());
        } catch (SqlParseException e) {
            return ResponseEntity.ok(SqlValidateResponse.builder()
                    .valid(false)
                    .dialect(dialect.name())
                    .error(e.getMessage())
                    .build());
        }
    }

    /**
     * 方言转换。
     *
     * @param request 转换请求
     * @return 转换响应
     */
    @Operation(summary = "转换SQL")
    @PostMapping("/convert")
    public ResponseEntity<SqlConvertResponse> convert(@Valid @RequestBody SqlConvertRequest request) {
        SqlDialect from = SqlDialect.fromString(request.getFromDialect());
        SqlDialect to = SqlDialect.fromString(request.getToDialect());
        // 若未指定源方言，自动检测
        if (request.getFromDialect() == null || request.getFromDialect().isBlank()) {
            from = parserService.detectDialect(request.getSql());
        }
        String converted = dialectConverter.convert(request.getSql(), from, to);
        return ResponseEntity.ok(SqlConvertResponse.builder()
                .fromDialect(from.name())
                .toDialect(to.name())
                .convertedSql(converted)
                .build());
    }

    /** 递归将 ASTNode 转为 JSON 友好的 Map */
    private static Map<String, Object> toNodeMap(ASTNode node) {
        return Map.of(
                "type", node.getType().name(),
                "properties", node.getProperties(),
                "children", node.getChildren().stream()
                        .map(SqlGatewayController::toNodeMap)
                        .collect(Collectors.toList())
        );
    }

    // ===================== SQL 优化 / 执行计划 =====================

    /**
     * 优化 SQL 并返回执行计划。
     *
     * <p>流程：SQL → AST → RelNode → 启发式优化 → 执行计划。
     * 应用谓词下推、列裁剪、Join 重排等规则。</p>
     *
     * @param request SQL 优化请求
     * @return SQL 优化响应
     */
    @Operation(summary = "优化 SQL 并返回执行计划")
    @PostMapping("/optimize")
    public ResponseEntity<SqlOptimizeResponse> optimize(@Valid @RequestBody SqlOptimizeRequest request) {
        SqlDialect dialect = SqlDialect.fromString(request.getDialect());
        if (request.isEnableAllRules()) {
            optimizerService.setRuleConfig(new OptimizationRuleConfig().enableAll());
        }
        OptimizationResult result = optimizerService.optimize(request.getSql(), dialect);
        SqlOptimizeResponse response = SqlOptimizeResponse.builder()
                .originalSql(result.getOriginalSql())
                .optimizedSql(result.getOptimizedSql())
                .executionPlan(result.getExecutionPlan())
                .rulesApplied(result.getRulesApplied())
                .estimatedCost(result.getEstimatedCost())
                .estimatedRows(result.getEstimatedRows())
                .tableAccesses(result.getTableAccesses())
                .suggestions(result.getSuggestions())
                .success(result.isSuccess())
                .error(result.getError())
                .dialect(result.getDialect())
                .build();
        return ResponseEntity.ok(response);
    }

    /**
     * 生成 SQL 执行计划（EXPLAIN 等价）。
     *
     * @param request SQL 解析请求（复用 SqlParseRequest）
     * @return SQL 优化响应（仅含执行计划）
     */
    @Operation(summary = "生成 SQL 执行计划（EXPLAIN 等价）")
    @PostMapping("/explain")
    public ResponseEntity<SqlOptimizeResponse> explain(@Valid @RequestBody SqlParseRequest request) {
        SqlDialect dialect = SqlDialect.fromString(request.getDialect());
        String plan = optimizerService.getExecutionPlan(request.getSql(), dialect);
        OptimizationResult result = optimizerService.optimize(request.getSql(), dialect);
        SqlOptimizeResponse response = SqlOptimizeResponse.builder()
                .originalSql(request.getSql())
                .executionPlan(plan)
                .rulesApplied(result.getRulesApplied())
                .estimatedCost(result.getEstimatedCost())
                .estimatedRows(result.getEstimatedRows())
                .tableAccesses(result.getTableAccesses())
                .success(result.isSuccess())
                .error(result.getError())
                .dialect(dialect.name())
                .build();
        return ResponseEntity.ok(response);
    }

    /**
     * 列出所有可用的优化规则。
     *
     * @return 规则描述列表
     */
    @Operation(summary = "列出所有可用的优化规则")
    @GetMapping("/optimize/rules")
    public ResponseEntity<List<String>> listOptimizeRules() {
        return ResponseEntity.ok(optimizerService.listAvailableRules());
    }

    // ===================== 跨源查询 =====================

    /**
     * 执行跨源 SQL 查询。
     *
     * <p>流程：解析 SQL 提取表 → 查询每个表的源 → 单源直接代理 / 多源并行查询 + 内存归并。
     * 返回结果包含表→源映射，便于前端可视化展示。</p>
     *
     * @param request 跨源查询请求
     * @return 跨源查询响应
     */
    @Operation(summary = "执行跨源 SQL 查询")
    @PostMapping("/cross-source")
    public ResponseEntity<CrossSourceResponse> crossSourceExecute(
            @Valid @RequestBody CrossSourceRequest request) {
        String queryId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        SqlDialect dialect = SqlDialect.fromString(request.getDialect());

        try {
            // 租户隔离（CONVENTIONS §9.5）：以 JWT claim 为准，body 携带值仅作一致性校验
            String tenantId = resolveCrossSourceTenant(request);

            MergeResult result = crossSourceExecutor.execute(
                    request.getSql(), dialect, tenantId);

            CrossSourceExecutor.ExecutionPlan plan = crossSourceExecutor.explain(
                    request.getSql(), dialect);

            CrossSourceResponse response = CrossSourceResponse.builder()
                    .queryId(queryId)
                    .status("SUCCESS")
                    .columns(result.getColumns())
                    .rows(result.getRows())
                    .rowCount(result.getRowCount())
                    .source(result.getSource())
                    .crossSource(plan.isCrossSource())
                    .sources(plan.getSources())
                    .tableToSource(plan.getTableToSource())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
            return ResponseEntity.ok(response);
        } catch (TenantMismatchException e) {
            // 显式越权（§9.5 不一致必须 403），不暴露请求方目标租户的更多信息
            log.warn("跨源查询租户不一致拒绝 queryId={} jwtTenant={} requestedTenant={}",
                    queryId, e.getJwtTenantId(), e.getRequestedTenantId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(buildFailedResponse(queryId, start, "tenant_mismatch",
                            "请求携带租户与登录身份不一致，已拒绝"));
        } catch (CrossSourceException e) {
            log.error("跨源查询失败 queryId={} code={} msg={}", queryId, e.getErrorCode(), e.getMessage());
            return ResponseEntity.status(crossSourceHttpStatus(e.getErrorCode()))
                    .body(buildFailedResponse(queryId, start, e.getErrorCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("跨源查询异常 queryId={} err={}", queryId, e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildFailedResponse(queryId, start, "INTERNAL_ERROR", e.getMessage()));
        }
    }

    /**
     * 生成跨源 SQL 执行计划（不实际执行）。
     *
     * <p>返回 SQL 涉及的表、表→源映射、是否跨源、执行策略等信息，
     * 供前端展示执行计划与跨源 JOIN 可视化。</p>
     *
     * @param request 跨源查询请求
     * @return 执行计划响应
     */
    @Operation(summary = "生成跨源 SQL 执行计划（不实际执行）")
    @PostMapping("/cross-source/explain")
    public ResponseEntity<CrossSourceExplainResponse> crossSourceExplain(
            @Valid @RequestBody CrossSourceRequest request) {
        long start = System.currentTimeMillis();
        SqlDialect dialect = SqlDialect.fromString(request.getDialect());

        try {
            // 租户隔离（CONVENTIONS §9.5）：以 JWT claim 为准，不一致抛 403
            resolveCrossSourceTenant(request);

            CrossSourceExecutor.ExecutionPlan plan = crossSourceExecutor.explain(
                    request.getSql(), dialect);

            CrossSourceExplainResponse response = CrossSourceExplainResponse.builder()
                    .sql(plan.getSql())
                    .statementType(plan.getStatementType())
                    .tables(plan.getTables())
                    .tableToSource(plan.getTableToSource())
                    .sources(plan.getSources())
                    .crossSource(plan.isCrossSource())
                    .strategy(plan.getStrategy())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
            return ResponseEntity.ok(response);
        } catch (TenantMismatchException e) {
            log.warn("跨源计划租户不一致拒绝 jwtTenant={} requestedTenant={}",
                    e.getJwtTenantId(), e.getRequestedTenantId());
            CrossSourceExplainResponse response = CrossSourceExplainResponse.builder()
                    .sql(request.getSql())
                    .durationMs(System.currentTimeMillis() - start)
                    .error("tenant_mismatch: 请求携带租户与登录身份不一致，已拒绝")
                    .build();
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (CrossSourceException e) {
            log.error("跨源执行计划生成失败 code={} msg={}", e.getErrorCode(), e.getMessage());
            CrossSourceExplainResponse response = CrossSourceExplainResponse.builder()
                    .sql(request.getSql())
                    .durationMs(System.currentTimeMillis() - start)
                    .error(e.getErrorCode() + ": " + e.getMessage())
                    .build();
            return ResponseEntity.status(crossSourceHttpStatus(e.getErrorCode())).body(response);
        } catch (Exception e) {
            log.error("跨源执行计划生成异常 err={}", e.toString());
            CrossSourceExplainResponse response = CrossSourceExplainResponse.builder()
                    .sql(request.getSql())
                    .durationMs(System.currentTimeMillis() - start)
                    .error("INTERNAL_ERROR: " + e.getMessage())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ===================== 私有工具方法 =====================

    /**
     * 解析生效租户 ID：JWT claim 优先，body 携带值仅作一致性校验。
     *
     * <p>规则（CONVENTIONS §9.5）：</p>
     * <ul>
     *   <li>TenantContext 存在（经 JwtAuthFilter 认证）→ 一律采用 JWT 值；</li>
     *   <li>与 body 值不一致 → 抛出 {@link TenantMismatchException}（Controller 转为 403）；</li>
     *   <li>无认证上下文（内部调用/单测/直连未挂过滤器）→ 回退 body 值（兼容历史行为）。</li>
     * </ul>
     */
    private String resolveCrossSourceTenant(CrossSourceRequest request) {
        String jwtTenant = com.levango7.dataenginebdp.common.security.TenantContext.getTenantId();
        String bodyTenant = request.getTenantId();
        if (jwtTenant != null && !jwtTenant.isBlank()) {
            if (bodyTenant != null && !bodyTenant.isBlank() && !bodyTenant.equals(jwtTenant)) {
                throw new TenantMismatchException(jwtTenant, bodyTenant);
            }
            return jwtTenant;
        }
        return bodyTenant;
    }

    /**
     * 跨源错误码 → HTTP 状态码映射（CONVENTIONS §9.3 语义）。
     */
    private HttpStatus crossSourceHttpStatus(String errorCode) {
        if (errorCode == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        switch (errorCode) {
            case CrossSourceException.PARSE_ERROR:
            case CrossSourceException.UNSUPPORTED:
                return HttpStatus.BAD_REQUEST;          // 客户端请求问题
            case CrossSourceException.SOURCE_NOT_FOUND:
                return HttpStatus.NOT_FOUND;            // 源不存在
            case CrossSourceException.RESULT_TOO_LARGE:
                return HttpStatus.PAYLOAD_TOO_LARGE;    // 结果超限
            case CrossSourceException.QUERY_TIMEOUT:
                return HttpStatus.GATEWAY_TIMEOUT;      // 上游超时
            case CrossSourceException.QUERY_FAILED:
            case CrossSourceException.MERGE_ERROR:
            default:
                return HttpStatus.BAD_GATEWAY;          // 上游/归并失败
        }
    }

    /** 构造跨源失败响应（body 结构保持兼容，仅 HTTP 状态码变化）。 */
    private CrossSourceResponse buildFailedResponse(String queryId, long start,
                                                    String errorCode, String message) {
        return CrossSourceResponse.builder()
                .queryId(queryId)
                .status("FAILED")
                .columns(List.of())
                .rows(List.of())
                .rowCount(0)
                .durationMs(System.currentTimeMillis() - start)
                .error(errorCode + ": " + message)
                .build();
    }
}
