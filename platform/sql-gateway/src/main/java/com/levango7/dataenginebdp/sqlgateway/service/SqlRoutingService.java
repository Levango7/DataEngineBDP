package com.levango7.dataenginebdp.sqlgateway.service;

import com.levango7.dataenginebdp.sqlgateway.config.CacheConfig;
import com.levango7.dataenginebdp.sqlgateway.model.RouteRule;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteRequest;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import com.levango7.dataenginebdp.sqlgateway.repository.RouteRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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

    /**
     * Doris 审计日志扫描字节客户端（可选；audit_log 表查 ScanBytes，未开启时返回 null → 估算）。
     */
    private final DorisScanStatsClient dorisScanStatsClient;

    private final CacheManager cacheManager;

    /**
     * 执行策略配置（只读门禁/行数上限）。
     * <p>可选注入：未注入（单测直连构造器）时使用默认实例，行为与历史版本一致；
     * Spring 运行时由 application.yml 的 {@code sql-gateway.execute} 提供安全默认。</p>
     */
    private com.levango7.dataenginebdp.sqlgateway.config.ExecuteProperties executeProperties =
            new com.levango7.dataenginebdp.sqlgateway.config.ExecuteProperties();

    /**
     * 注入执行策略配置（Spring 自动装配；可选依赖）。
     *
     * @param executeProperties 执行策略配置
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setExecuteProperties(
            com.levango7.dataenginebdp.sqlgateway.config.ExecuteProperties executeProperties) {
        if (executeProperties != null) {
            this.executeProperties = executeProperties;
        }
    }

    public SqlRoutingService(BackendProxyService backendProxyService,
                             RouteRuleRepository routeRuleRepository,
                             com.levango7.dataenginebdp.sqlgateway.metering.MeteringCollector meteringCollector,
                             DorisScanStatsClient dorisScanStatsClient,
                             CacheManager cacheManager) {
        this.backendProxyService = backendProxyService;
        this.routeRuleRepository = routeRuleRepository;
        this.meteringCollector = meteringCollector;
        this.dorisScanStatsClient = dorisScanStatsClient;
        this.cacheManager = cacheManager;
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
        // 租户身份解析：认证上下文（JWT）优先，请求体仅作无鉴权调用回退。
        // 修复越权面：此前 body 中的 tenantId 可被伪造，用于缓存键/计量/X-Trino-User 冒充他租户。
        String tenantId = resolveTenantId(request);
        String sql = request.getSql();

        log.info("queryId={} engine={} tenant={} sql={}{}",
                queryId, targetEngine, tenantId,
                abbreviate(sql, 80),
                request.getLimit() == null ? "" : " limit=" + request.getLimit());

        // 只读门禁：read-only-only 开启时拒绝 DML/DDL，不下发后端（防误删/越权写入）
        if (executeProperties.isReadOnlyOnly() && !CacheConfig.isReadOnly(sql)) {
            log.warn("queryId={} 非只读 SQL 被拒绝(read-only-only=true) tenant={} sql={}",
                    queryId, tenantId, abbreviate(sql, 80));
            return SqlExecuteResponse.builder()
                    .queryId(queryId)
                    .status("FAILED")
                    .columns(List.of())
                    .rows(List.of())
                    .durationMs(System.currentTimeMillis() - start)
                    .engine(targetEngine)
                    .truncated(false)
                    .message("网关已开启只读模式(read-only-only=true)，"
                            + "仅允许 SELECT/SHOW/DESC/WITH/EXPLAIN 语句")
                    .build();
        }

        Integer effectiveLimit = executeProperties.effectiveLimit(request.getLimit());

        // 查询结果缓存（任务 D）：仅只读 SQL + 租户隔离键，DML 永不缓存。
        // 缓存键必须包含生效 limit：同一 SQL 不同 limit 的截断结果不同，
        // 否则大 limit 的缓存结果会错误命中小 limit 请求（或反之）。
        if (CacheConfig.isReadOnly(sql)) {
            String cacheKey = buildCacheKey(targetEngine, sql, tenantId, effectiveLimit);
            Cache cache = cacheManager.getCache(CacheConfig.SQL_QUERY_CACHE);
            SqlExecuteResponse cached = cache == null ? null : cache.get(cacheKey, SqlExecuteResponse.class);
            if (cached != null) {
                log.info("queryId={} 命中查询缓存 key={}", queryId, cacheKey);
                // 返回副本并标记 cached（不污染缓存对象：缓存对象被多请求共享，
                // 直接 setCached(true) 会让并发请求/首次请求也读到 cached=true）
                return copyResponse(cached, true);
            }
            try {
                SqlExecuteResponse response = doExecute(targetEngine, sql, tenantId,
                        effectiveLimit, queryId, start);
                if (response != null && "SUCCESS".equals(response.getStatus()) && cache != null) {
                    cache.put(cacheKey, response);
                }
                return response;
            } finally {
                // doExecute 内部已记录 metering
            }
        }
        return doExecute(targetEngine, sql, tenantId, effectiveLimit, queryId, start);
    }

    /**
     * 解析生效租户 ID：JWT 认证上下文优先，body 回退。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>TenantContext 存在（经 JwtAuthFilter 认证）→ 一律采用 JWT 值；</li>
     *   <li>与 body 值不一致 → 告警（潜在越权尝试），仍以 JWT 值为准；</li>
     *   <li>无认证上下文（内部调用/单测）→ 使用 body 值（兼容历史行为）。</li>
     * </ul>
     */
    private String resolveTenantId(SqlExecuteRequest request) {
        String jwtTenant = com.levango7.dataenginebdp.common.security.TenantContext.getTenantId();
        String bodyTenant = request.getTenantId();
        if (jwtTenant != null && !jwtTenant.isBlank()) {
            if (bodyTenant != null && !bodyTenant.isBlank() && !bodyTenant.equals(jwtTenant)) {
                log.warn("租户身份不一致：以 JWT 为准 jwtTenant={}, bodyTenant={}（疑似越权尝试）",
                        jwtTenant, bodyTenant);
            }
            return jwtTenant;
        }
        return bodyTenant;
    }

    /** 拷贝响应并覆盖 cached 标志（保留 truncated/message 等新字段）。 */
    private static SqlExecuteResponse copyResponse(SqlExecuteResponse src, boolean cachedFlag) {
        return SqlExecuteResponse.builder()
                .queryId(src.getQueryId())
                .status(src.getStatus())
                .columns(src.getColumns())
                .rows(src.getRows())
                .durationMs(src.getDurationMs())
                .engine(src.getEngine())
                .rawInputBytes(src.getRawInputBytes())
                .truncated(src.getTruncated())
                .message(src.getMessage())
                .cached(cachedFlag)
                .build();
    }

    /** 构建缓存键（engine+sql+tenantId+limit 的 SHA-256，含租户与行数隔离；JDK 实现零依赖）。 */
    private String buildCacheKey(String engine, String sql, String tenantId, Integer effectiveLimit) {
        // 归一化：压缩空白、去掉注释、统一大小写，使语义等价的 SQL 产生相同缓存键
        String normalized = normalizeSql(sql);
        String raw = engine + "|" + normalized + "|" + (tenantId == null ? "" : tenantId)
                + "|limit=" + (effectiveLimit == null ? "" : effectiveLimit);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // 不可能发生（JDK 必含 SHA-256）
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * 归一化 SQL：压缩空白、去掉单行/多行注释、去掉尾部分号，使语义等价的 SQL 产生相同键。
     */
    private static String normalizeSql(String sql) {
        if (sql == null) return "";
        // 去掉 SQL 注释（-- 行注释 / /* */ 块注释）
        // (?s) 启用 DOTALL 模式，使 . 能匹配换行符（用于跨行块注释）
        String noComments = sql.replaceAll("--[^\n]*", " ")
                .replaceAll("(?s)/\\*.*?\\*/", " ");
        // 压缩连续空白为单空格
        return noComments.replaceAll("\\s+", " ").trim();
    }

    /** 执行真实查询（缓存未命中路径）。 */
    private SqlExecuteResponse doExecute(String targetEngine, String sql, String tenantId,
                                         Integer effectiveLimit, String queryId, long start) {
        try {
            SqlExecuteResponse response;
            if ("doris".equalsIgnoreCase(targetEngine)) {
                response = backendProxyService.proxyToDoris(sql, tenantId, effectiveLimit)
                        .block(java.time.Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));
            } else {
                // 默认走 Trino（未知引擎也走 Trino 兜底）
                response = backendProxyService.proxyToTrino(sql, tenantId, effectiveLimit)
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
            // Doris：优先按 SQL 指纹查审计日志真实扫描字节；未开启/未命中回退估算（Trino：沿用响应 rawInputBytes）
            Long dorisRealBytes = "doris".equalsIgnoreCase(targetEngine)
                    ? resolveDorisScanBytes(sql)
                    : null;
            recordMetering(tenantId, targetEngine, sql, start,
                    System.currentTimeMillis() - start, queryId,
                    dorisRealBytes != null ? dorisRealBytes : response.getRawInputBytes());
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
                .message(message)
                .truncated(false)
                .build();
    }

    /** 将字节数组转为十六进制字符串。 */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
     * 解析 Doris 真实扫描字节：按 SQL 指纹查审计日志（未开启/未命中返回 null，上层估算兜底）。
     */
    private Long resolveDorisScanBytes(String sql) {
        try {
            if (dorisScanStatsClient == null) {
                return null;
            }
            return dorisScanStatsClient.fetchScanBytes(sql);
        } catch (Exception e) {
            log.warn("Doris 扫描字节解析失败(估算兜底): err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 记录查询计量（异步、不阻塞主链路）。
     *
     * <p>字节来源：真实扫描字节（Trino rawInputBytes / Doris 审计日志）优先，
     * 否则用耗时×系数估算并标记 est=true。</p>
     */
    private void recordMetering(String tenantId, String engine, String sql,
                                long startMs, long durationMs, String queryId,
                                Long realRawInputBytes) {
        if (meteringCollector == null || tenantId == null || tenantId.isBlank()) {
            return;
        }
        try {
            boolean estimated;
            long bytes;
            if (realRawInputBytes != null && realRawInputBytes > 0) {
                bytes = realRawInputBytes;
                estimated = false;
            } else {
                // 估算扫描字节：约 10 MB/秒 引擎吞吐下限，避免低估
                bytes = Math.max(1L, durationMs * 10_000L);
                estimated = true;
            }
            String sqlHash = toHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((sql == null ? "" : sql).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            meteringCollector.submit(new com.levango7.dataenginebdp.sqlgateway.metering.QueryMeter(
                    tenantId, null, engine, sqlHash,
                    bytes, estimated, durationMs, queryId));
            log.debug("查询计量已记录: tenant={}, engine={}, bytes={}, estimated={}, queryId={}",
                    tenantId, engine, bytes, estimated, queryId);
        } catch (Exception e) {
            // 计量失败绝不影响主查询：仅记录日志
            log.warn("查询计量记录失败(忽略): tenant={}, queryId={}, err={}",
                    tenantId, queryId, e.getMessage());
        }
    }
}
