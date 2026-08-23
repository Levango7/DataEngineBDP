package com.levango7.dataenginebdp.sqlgateway.crosssource;

import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import com.levango7.dataenginebdp.sqlgateway.service.BackendProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于表名 Catalog 前缀的源解析器默认实现。
 *
 * <p>解析规则：</p>
 * <ol>
 *   <li>表名含点号（如 {@code hive.users}）→ 取第一段作为 catalog，映射为源标识；</li>
 *   <li>表名不含点号 → 使用默认源（由配置 {@code sql-gateway.default-source} 指定，默认 trino）；</li>
 *   <li>catalog 名 → 源标识映射：{@code hive→trino}（Hive 通过 Trino connector 访问）、
 *       {@code doris→doris}、{@code trino→trino}、{@code mysql→trino}（MySQL 通过 Trino JDBC connector）。</li>
 * </ol>
 *
 * <p>跨源 SQL 拆分策略（简化版）：</p>
 * <ul>
 *   <li>按源分组，每个源构造一个 {@code SELECT * FROM <表>} 查询（仅适用于单表查询场景）；</li>
 *   <li>复杂 SQL（多表 JOIN/UNION）的拆分需调用方提供自定义 {@link CrossSourceExecutor.SourceResolver} 实现。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class CatalogBasedSourceResolver implements CrossSourceExecutor.SourceResolver {

    private static final Logger log = LoggerFactory.getLogger(CatalogBasedSourceResolver.class);

    /** catalog → 源标识映射（小写） */
    private static final Map<String, String> CATALOG_TO_SOURCE = new LinkedHashMap<>();

    static {
        CATALOG_TO_SOURCE.put("hive", "trino");
        CATALOG_TO_SOURCE.put("trino", "trino");
        CATALOG_TO_SOURCE.put("doris", "doris");
        CATALOG_TO_SOURCE.put("mysql", "trino");
        CATALOG_TO_SOURCE.put("postgresql", "trino");
        CATALOG_TO_SOURCE.put("iceberg", "trino");
        CATALOG_TO_SOURCE.put("delta", "trino");
    }

    /** 默认源（表名不含 catalog 前缀时使用） */
    @Value("${sql-gateway.default-source:trino}")
    private String defaultSource;

    /** 默认查询超时（秒） */
    @Value("${sql-gateway.cross-source.timeout-seconds:30}")
    private long timeoutSeconds;

    /** 单源查询最大重试次数 */
    @Value("${sql-gateway.cross-source.max-retry:0}")
    private int maxRetry;

    private final BackendProxyService backendProxyService;

    /**
     * 构造注入。
     *
     * @param backendProxyService 后端代理服务（可选，未注入时单源查询会失败）
     */
    @Autowired
    public CatalogBasedSourceResolver(BackendProxyService backendProxyService) {
        this.backendProxyService = backendProxyService;
    }

    @Override
    public String resolveSource(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return defaultSource;
        }
        String trimmed = tableName.trim();
        int dotIdx = trimmed.indexOf('.');
        if (dotIdx <= 0) {
            // 不含 catalog 前缀
            return defaultSource;
        }
        String catalog = trimmed.substring(0, dotIdx).toLowerCase(Locale.ROOT);
        String source = CATALOG_TO_SOURCE.get(catalog);
        if (source == null) {
            log.warn("未知 catalog: {}，回退到默认源: {}", catalog, defaultSource);
            return defaultSource;
        }
        return source;
    }

    @Override
    public List<SourceQueryTask> splitQuery(String sql, SqlDialect dialect,
                                            Map<String, String> tableToSource,
                                            String tenantId) {
        if (tableToSource == null || tableToSource.isEmpty()) {
            log.warn("splitQuery: tableToSource 为空，返回空任务列表");
            return new ArrayList<>();
        }

        // 按源分组表名
        Map<String, List<String>> sourceToTables = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : tableToSource.entrySet()) {
            sourceToTables.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                    .add(entry.getKey());
        }

        // 为每个源构造单源查询任务
        // 简化策略：每个源执行 SELECT * FROM <table1>, <table2>, ...
        // 真实场景应由调用方提供更智能的拆分器（基于 AST 拆分 JOIN/UNION）
        List<SourceQueryTask> tasks = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : sourceToTables.entrySet()) {
            String source = entry.getKey();
            List<String> tables = entry.getValue();
            String singleSourceSql = buildSingleSourceQuery(tables);
            log.info("splitQuery: source={} tables={} sql={}", source, tables, singleSourceSql);

            SourceQueryTask task = SourceQueryTask.builder()
                    .source(source)
                    .sql(singleSourceSql)
                    .tenantId(tenantId)
                    .timeoutSeconds(timeoutSeconds)
                    .maxRetry(maxRetry)
                    .backendProxyService(backendProxyService)
                    .build();
            tasks.add(task);
        }
        return tasks;
    }

    /**
     * 为一组表构造单源查询 SQL。
     *
     * <p>简化策略：{@code SELECT * FROM <t1>, <t2>, ... LIMIT 10000}。
     * 真实场景应保留原始 SQL 的 SELECT 列、WHERE 条件等。</p>
     *
     * <p>安全：表名通过反引号转义，禁止包含非标识符字符，防止 SQL 注入。</p>
     *
     * @param tables 表名列表
     * @return 单源查询 SQL
     */
    private String buildSingleSourceQuery(List<String> tables) {
        StringBuilder sb = new StringBuilder("SELECT * FROM ");
        boolean first = true;
        for (String table : tables) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("`").append(escapeBacktick(table)).append("`");
            first = false;
        }
        sb.append(" LIMIT ").append(MergeResult.DEFAULT_MAX_ROWS);
        return sb.toString();
    }

    /**
     * 转义表名中的反引号（`` → `` ``），并校验只包含合法标识符字符。
     *
     * @param identifier 原始标识符
     * @return 转义后的安全标识符
     * @throws IllegalArgumentException 包含非法字符时抛出
     */
    private static String escapeBacktick(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        // 允许：字母、数字、下划线、点号（用于 catalog.schema.table）、反引号
        if (!identifier.matches("[a-zA-Z0-9_\\.\\`]+")) {
            throw new IllegalArgumentException("表名包含非法字符: " + identifier);
        }
        return identifier.replace("`", "``");
    }

    /**
     * 获取默认源。
     *
     * @return 默认源
     */
    public String getDefaultSource() {
        return defaultSource;
    }

    /**
     * 列出所有支持的 catalog。
     *
     * @return catalog 列表
     */
    public static List<String> supportedCatalogs() {
        return new ArrayList<>(CATALOG_TO_SOURCE.keySet());
    }

    /**
     * 列出所有支持的源标识。
     *
     * @return 源标识列表
     */
    public static List<String> supportedSources() {
        return Arrays.asList("trino", "doris");
    }
}