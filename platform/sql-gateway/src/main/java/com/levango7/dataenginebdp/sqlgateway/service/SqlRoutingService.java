package com.levango7.dataenginebdp.sqlgateway.service;

import com.levango7.dataenginebdp.sqlgateway.model.RouteRule;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteRequest;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import com.levango7.dataenginebdp.sqlgateway.repository.RouteRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * SQL 路由服务。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>根据请求的 {@code engine} 字段或路由规则决定目标后端；</li>
 *   <li>调用 {@link BackendProxyService} 将 SQL 真实代理到 Trino/Doris 后端；</li>
 *   <li>维护路由规则的持久化存储（{@link RouteRuleRepository}，基于 Spring Data JPA）。</li>
 * </ul>
 *
 * <p>执行入口 {@link #execute(SqlExecuteRequest)} 保持同步签名以兼容现有 Controller，
 * 内部通过 {@code Mono.block()} 将异步响应转为同步返回，并兜底处理超时与异常，
 * 后端不可用时返回降级错误响应而非抛出异常。</p>
 *
 * <p>路由规则持久化到关系型数据库（开发环境 H2，生产环境 PostgreSQL），重启不丢失。</p>
 *
 * @author shuqing-bigdata
 */
@Service
public class SqlRoutingService {

    private static final Logger log = LoggerFactory.getLogger(SqlRoutingService.class);

    /**
     * 同步阻塞等待超时（秒），与后端响应超时对齐。
     */
    private static final long BLOCK_TIMEOUT_SECONDS = 35L;

    /**
     * 路由规则持久化仓储。
     */
    private final RouteRuleRepository routeRuleRepository;

    /**
     * 默认引擎，从配置 {@code sql-gateway.default-engine} 读取。
     */
    @Value("${sql-gateway.default-engine:trino}")
    private String defaultEngine;

    /**
     * 后端代理服务（真实 HTTP 调用）。
     */
    private final BackendProxyService backendProxyService;

    /**
     * 查询计量收集器（可选；未注入时计量静默跳过，不影响查询主链路）。
     */
    private final com.levango7.dataenginebdp.sqlgateway.metering.MeteringCollector meteringCollector;

    public SqlRoutingService(BackendProxyService backendProxyService,
                             RouteRuleRepository routeRuleRepository,
                             com.levango7.dataenginebdp.sqlgateway.metering.MeteringCollector meteringCollector) {
        this.backendProxyService = backendProxyService;
        this.routeRuleRepository = routeRuleRepository;
        this.meteringCollector = meteringCollector;
    }

    /**
     * 执行 SQL：解析目标引擎后调用真实后端。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>解析目标引擎（请求显式指定 > 路由规则匹配 > 默认引擎）；</li>
     *   <li>根据引擎调用 {@link BackendProxyService#proxyToTrino} 或
     *       {@link BackendProxyService#proxyToDoris}；</li>
     *   <li>通过 {@code block} 转同步，超时或异常时返回降级错误响应。</li>
     * </ol>
     *
     * @param request SQL 执行请求
     * @return SQL 执行响应
     */
    public SqlExecuteResponse execute(SqlExecuteRequest request) {
        long start = System.currentTimeMillis();
        String targetEngine = resolveEngine(request);
        String queryId = UUID.randomUUID().toString();
        String tenantId = request.getTenantId();
        String sql = request.getSql();

        log.info("queryId={} engine={} tenant={} sql={}{}",
                queryId, targetEngine, tenantId,
                abbreviate(sql, 80),
                request.getLimit() == null ? "" : " limit=" + request.getLimit());

        try {
            SqlExecuteResponse response;
            if ("doris".equalsIgnoreCase(targetEngine)) {
                response = backendProxyService.proxyToDoris(sql, tenantId)
                        .block(java.time.Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));
            } else {
                // 默认走 Trino（未知引擎也走 Trino 兜底）
                response = backendProxyService.proxyToTrino(sql, tenantId)
                        .block(java.time.Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));
            }

            // 后端返回 null 时降级
            if (response == null) {
                log.warn("queryId={} 后端返回空响应，降级处理", queryId);
                return fallbackResponse(queryId, targetEngine, start,
                        "后端返回空响应");
            }

            // 用网关生成的 queryId 覆盖后端响应中的 queryId，保证全局唯一可追踪
            response.setQueryId(queryId);
            recordMetering(tenantId, targetEngine, sql, start,
                    System.currentTimeMillis() - start, queryId);
            return response;
        } catch (IllegalStateException e) {
            // Mono.block 超时会抛 IllegalStateException("Timeout on blocking read...")
            log.error("queryId={} 执行超时 engine={} err={}", queryId, targetEngine, e.getMessage());
            return fallbackResponse(queryId, targetEngine, start, "执行超时: " + e.getMessage());
        } catch (Exception e) {
            log.error("queryId={} 执行异常 engine={} err={}", queryId, targetEngine, e.toString());
            return fallbackResponse(queryId, targetEngine, start,
                    "执行异常: " + e.getMessage());
        }
    }

    /**
     * 列出所有路由规则（按优先级升序）。
     *
     * @return 路由规则列表
     */
    public List<RouteRule> listRoutes() {
        List<RouteRule> all = routeRuleRepository.findAll();
        all.sort(Comparator.comparingInt(r ->
                r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority()));
        return all;
    }

    /**
     * 添加一条路由规则。
     *
     * @param rule 路由规则（若 id 为空则由数据库自增分配）
     * @return 已保存的路由规则
     */
    public RouteRule addRoute(RouteRule rule) {
        // 新增规则 id 置空，由 JPA IDENTITY 策略自动生成
        if (rule.getId() != null && !routeRuleRepository.existsById(rule.getId())) {
            rule.setId(null);
        }
        if (rule.getEnabled() == null) {
            rule.setEnabled(Boolean.TRUE);
        }
        if (rule.getPriority() == null) {
            rule.setPriority(100);
        }
        RouteRule saved = routeRuleRepository.save(rule);
        log.info("路由规则已添加: id={} pattern={} engine={} priority={} enabled={}",
                saved.getId(), saved.getPattern(), saved.getEngine(),
                saved.getPriority(), saved.getEnabled());
        return saved;
    }

    /**
     * 解析目标引擎：请求显式指定 > 路由规则匹配 > 默认引擎。
     *
     * @param request SQL 执行请求
     * @return 目标引擎名称
     */
    private String resolveEngine(SqlExecuteRequest request) {
        // 1. 请求显式指定引擎
        if (request.getEngine() != null && !request.getEngine().isBlank()) {
            return request.getEngine();
        }
        // 2. 路由规则匹配（按优先级升序遍历启用的规则）
        String sql = request.getSql();
        if (sql != null) {
            for (RouteRule rule : listRoutes()) {
                if (Boolean.FALSE.equals(rule.getEnabled())) {
                    continue;
                }
                if (rule.getPattern() != null
                        && sql.toLowerCase().contains(rule.getPattern().toLowerCase())) {
                    log.debug("命中路由规则 id={} engine={}", rule.getId(), rule.getEngine());
                    return rule.getEngine();
                }
            }
        }
        // 3. 默认引擎
        return defaultEngine;
    }

    /**
     * 构造降级错误响应（后端不可用/超时/异常时使用）。
     *
     * @param queryId    查询 ID
     * @param engine     目标引擎
     * @param start      开始时间戳
     * @param message    错误信息
     * @return 降级响应
     */
    private SqlExecuteResponse fallbackResponse(String queryId, String engine,
                                                long start, String message) {
        long duration = System.currentTimeMillis() - start;
        log.warn("降级响应 queryId={} engine={} duration={}ms msg={}",
                queryId, engine, duration, message);
        return SqlExecuteResponse.builder()
                .queryId(queryId)
                .status("DEGRADED")
                .columns(List.of())
                .rows(List.of())
                .durationMs(duration)
                .engine(engine)
                .build();
    }

    /**
     * 截断 SQL 用于日志输出，避免过长。
     *
     * @param s      原始字符串
     * @param maxLen 最大长度
     * @return 截断后的字符串
     */
    private String abbreviate(String s, int maxLen) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 记录查询计量（异步、不阻塞主链路）。
     *
     * <p>字节为估算值（耗时 × 引擎系数），标记 estimated=true；
     * 后续接入 Trino QueryStats / Doris 审计后替换为真实值。
     */
    private void recordMetering(String tenantId, String engine, String sql,
                                long startMs, long durationMs, String queryId) {
        if (meteringCollector == null || tenantId == null || tenantId.isBlank()) {
            return;
        }
        try {
            // 估算扫描字节：约 10 MB/秒 引擎吞吐下限，避免低估
            long estimatedBytes = Math.max(1L, durationMs * 10_000L);
            String sqlHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((sql == null ? "" : sql).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString();
            meteringCollector.submit(new com.levango7.dataenginebdp.sqlgateway.metering.QueryMeter(
                    tenantId, null, engine, sqlHash,
                    estimatedBytes, true, durationMs, queryId));
            log.debug("查询计量已记录: tenant={}, engine={}, estBytes={}, queryId={}",
                    tenantId, engine, estimatedBytes, queryId);
        } catch (Exception e) {
            // 计量失败绝不影响主查询：仅记录日志
            log.warn("查询计量记录失败(忽略): tenant={}, queryId={}, err={}",
                    tenantId, queryId, e.getMessage());
        }
    }
}
