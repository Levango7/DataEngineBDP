package com.shuqing.bigdata.federated.catalog;

import com.shuqing.bigdata.federated.model.TableLocation;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParser.Config;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.springframework.stereotype.Component;

import static org.apache.calcite.sql.SqlKind.AS;
import static org.apache.calcite.sql.SqlKind.EXCEPT;
import static org.apache.calcite.sql.SqlKind.IDENTIFIER;
import static org.apache.calcite.sql.SqlKind.INTERSECT;
import static org.apache.calcite.sql.SqlKind.JOIN;
import static org.apache.calcite.sql.SqlKind.SELECT;
import static org.apache.calcite.sql.SqlKind.UNION;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表元数据定位服务。
 *
 * <p>职责：从 SQL 中解析出涉及的表名（database.table），调用
 * {@link GlobalCatalogClient} 定位每张表所在集群，返回
 * {@code 表全名 → TableLocation} 映射。
 *
 * <p>SQL 解析基于 Apache Calcite（Phase 1 T012 联邦优化器同栈），
 * 支持 SELECT/JOIN/UNION 等常见查询语法。
 */
@Slf4j
@Component
public class TableLocationService {

    private final GlobalCatalogClient catalogClient;
    private final SqlParser.Config parserConfig;

    public TableLocationService(GlobalCatalogClient catalogClient) {
        this.catalogClient = catalogClient;
        this.parserConfig = Config.DEFAULT.withConformance(SqlConformanceEnum.DEFAULT);
    }

    /**
     * 从 SQL 中提取涉及的表全名集合。
     *
     * <p>使用 Calcite SqlParser 解析 AST，遍历找到所有表引用
     * （FROM 子句、JOIN 子句中的表名）。
     *
     * @param sql SQL 语句
     * @return 表全名集合（database.table 或仅 table）
     */
    public Set<String> extractTableNames(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        try {
            SqlParser parser = SqlParser.create(sql, parserConfig);
            SqlNode node = parser.parseQuery();
            collectTables(node, tables);
        } catch (Exception e) {
            log.warn("Failed to parse SQL for table extraction, fallback to regex: {}", e.getMessage());
            tables.addAll(extractTablesByRegex(sql));
        }
        return tables;
    }

    /**
     * 定位 SQL 中所有表所在集群。
     *
     * @param sql SQL 语句
     * @return 表全名 → 表定位信息
     */
    public Map<String, TableLocation> locateTables(String sql) {
        return locateTables(sql, "default");
    }

    /**
     * 定位 SQL 中所有表所在集群。
     *
     * @param sql      SQL 语句
     * @param defaultDatabase 默认数据库（当表名无前缀时使用）
     * @return 表全名 → 表定位信息
     */
    public Map<String, TableLocation> locateTables(String sql, String defaultDatabase) {
        Set<String> fullNames = extractTableNames(sql);
        // 对没有数据库前缀的表名，补上默认数据库
        List<String> normalized = new java.util.ArrayList<>();
        String db = (defaultDatabase != null && !defaultDatabase.isBlank()) ? defaultDatabase : "default";
        for (String fn : fullNames) {
            if (fn.contains(".")) {
                normalized.add(fn);
            } else {
                normalized.add(db + "." + fn);
            }
        }
        log.debug("Tables extracted from SQL: {} (normalized: {})", fullNames, normalized);
        return catalogClient.locateTables(normalized).block();
    }

    /**
     * 定位 SQL 中所有表所在集群（异步）。
     */
    public reactor.core.publisher.Mono<Map<String, TableLocation>> locateTablesReactive(String sql) {
        return locateTablesReactive(sql, "default");
    }

    /**
     * 定位 SQL 中所有表所在集群（异步）。
     */
    public reactor.core.publisher.Mono<Map<String, TableLocation>> locateTablesReactive(String sql, String defaultDatabase) {
        Set<String> fullNames = extractTableNames(sql);
        List<String> normalized = new java.util.ArrayList<>();
        String db = (defaultDatabase != null && !defaultDatabase.isBlank()) ? defaultDatabase : "default";
        for (String fn : fullNames) {
            if (fn.contains(".")) {
                normalized.add(fn);
            } else {
                normalized.add(db + "." + fn);
            }
        }
        return catalogClient.locateTables(List.copyOf(fullNames));
    }

    /**
     * 判断 SQL 是否跨集群（涉及 ≥2 个不同集群）。
     */
    public boolean isCrossCluster(String sql) {
        Map<String, TableLocation> locs = locateTables(sql);
        if (locs == null || locs.isEmpty()) {
            return false;
        }
        long distinctClusters = locs.values().stream()
                .map(TableLocation::getCluster)
                .distinct()
                .count();
        return distinctClusters >= 2;
    }

    /**
     * 返回 SQL 涉及的集群列表（去重、保序）。
     */
    public List<String> involvedClusters(String sql) {
        Map<String, TableLocation> locs = locateTables(sql);
        if (locs == null) {
            return List.of();
        }
        return locs.values().stream()
                .map(TableLocation::getCluster)
                .distinct()
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // 内部：AST 遍历
    // ------------------------------------------------------------------

    private void collectTables(SqlNode node, Set<String> tables) {
        if (node == null) {
            return;
        }
        switch (node.getKind()) {
            case SELECT:
                SqlSelect select = (SqlSelect) node;
                collectTables(select.getFrom(), tables);
                if (select.getWhere() != null) {
                    collectTables(select.getWhere(), tables);
                }
                if (select.getGroup() != null) {
                    collectTables(select.getGroup(), tables);
                }
                if (select.getHaving() != null) {
                    collectTables(select.getHaving(), tables);
                }
                if (select.getOrderList() != null) {
                    collectTables(select.getOrderList(), tables);
                }
                break;
            case JOIN:
                SqlBasicCall join = (SqlBasicCall) node;
                for (SqlNode operand : join.getOperandList()) {
                    collectTables(operand, tables);
                }
                break;
            case UNION:
            case INTERSECT:
            case EXCEPT:
                SqlBasicCall setOp = (SqlBasicCall) node;
                for (SqlNode operand : setOp.getOperandList()) {
                    collectTables(operand, tables);
                }
                break;
            case IDENTIFIER:
                SqlIdentifier id = (SqlIdentifier) node;
                // 表引用：database.table 或 table
                String fullName = id.toString();
                tables.add(fullName);
                break;
            case AS:
                // 表别名：AS 的第一个操作数是表引用
                SqlBasicCall as = (SqlBasicCall) node;
                collectTables(as.operand(0), tables);
                break;
            default:
                if (node instanceof SqlBasicCall) {
                    SqlBasicCall call = (SqlBasicCall) node;
                    for (SqlNode operand : call.getOperandList()) {
                        collectTables(operand, tables);
                    }
                }
        }
    }

    /**
     * 正则兜底：当 Calcite 解析失败时（如方言不兼容），用简单正则提取表名。
     */
    private Set<String> extractTablesByRegex(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?i)\\bfrom\\s+(\\w+(?:\\.\\w+)?)|\\bjoin\\s+(\\w+(?:\\.\\w+)?)");
        java.util.regex.Matcher m = p.matcher(sql);
        while (m.find()) {
            String t = m.group(1) != null ? m.group(1) : m.group(2);
            if (t != null) {
                tables.add(t);
            }
        }
        return tables;
    }
}