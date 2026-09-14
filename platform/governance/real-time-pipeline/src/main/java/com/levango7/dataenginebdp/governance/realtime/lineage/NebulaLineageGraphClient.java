package com.levango7.dataenginebdp.governance.realtime.lineage;

import com.levango7.dataenginebdp.governance.realtime.model.FieldLineage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NebulaGraph 血缘图客户端。
 *
 * <p>将 {@link FieldLineage} 写入 NebulaGraph 图数据库，构建字段级血缘图。
 * 图 schema：
 * <ul>
 *   <li><b>Tag（节点）</b>：{@code TableField}，属性：tableIdentifier、fieldName、fieldType</li>
 *   <li><b>Edge Type（边）</b>：{@code FieldLineage}，属性：lineageId、jobId、transformType、expression、extractedAt</li>
 *   <li><b>Space</b>：{@code lineage}（可通过 {@code governance.nebula.space} 配置）</li>
 * </ul>
 *
 * <p>nGQL 示例：
 * <pre>
 * -- 创建节点
 * INSERT VERTEX TableField(tableIdentifier, fieldName, fieldType) VALUES
 *   "source_table.field_a":("source_table", "field_a", "string");
 *
 * -- 创建血缘边
 * INSERT EDGE FieldLineage(lineageId, jobId, transformType, expression, extractedAt) VALUES
 *   "source_table.field_a"->"target_table.field1":("lineage-001", "job-001", "DIRECT", "", "2026-08-08T...");
 * </pre>
 *
 * <p>降级策略：NebulaGraph 不可用时，血缘写入内存缓存（{@code lineageCache}），
 * 不影响治理闭环主流程，由后台任务重试。
 */
@Component
public class NebulaLineageGraphClient {

    private static final Logger log = LoggerFactory.getLogger(NebulaLineageGraphClient.class);

    /** nGQL 保留关键字集合（大小写不敏感比较，存储大写形式） */
    private static final Set<String> NGQL_RESERVED_KEYWORDS = Set.of(
            "DROP", "INSERT", "VERTEX", "SPACE", "DELETE", "UPDATE", "SELECT",
            "FROM", "WHERE", "CREATE", "ALTER", "REMOVE", "DESC", "SHOW",
            "GO", "OVER", "YIELD", "REBUILD", "SUBGRAPH", "FIND", "PATH",
            "SHORTEST", "ALL", "ANY", "SOME", "NONE", "NOT", "AND",
            "OR", "XOR", "UNION", "INTERSECT", "MINUS", "LIMIT", "OFFSET",
            "ORDER", "BY", "ASC", "GROUP", "HAVING", "JOIN", "ON",
            "INTO", "VALUES", "SET", "MATCH", "OPTIONAL", "EXPLAIN", "PROFILE",
            "UNWIND", "RETURN", "CALL", "WITH", "DISTINCT", "CASE", "WHEN",
            "THEN", "ELSE", "END", "TRUE", "FALSE", "NULL", "IS", "AS",
            "TAG", "EDGE", "STEP", "UPTO", "REVERSELY", "BIDIRECT"
    );

    /** 标识符最大长度 */
    private static final int MAX_IDENTIFIER_LENGTH = 128;

    private final RestClient restClient;
    private final String graphdHost;
    private final int graphdPort;
    private final String space;
    private final String nodeTag;
    private final String edgeType;

    /** 内存血缘缓存（NebulaGraph 不可用时的降级存储） */
    private final ConcurrentHashMap<String, FieldLineage> lineageCache = new ConcurrentHashMap<>();

    /** 写入统计 */
    private final AtomicLong writeSuccessCount = new AtomicLong(0);
    private final AtomicLong writeFailureCount = new AtomicLong(0);
    private final AtomicLong fallbackCount = new AtomicLong(0);

    public NebulaLineageGraphClient(
            @Value("${governance.nebula.graphd-host:localhost}") String graphdHost,
            @Value("${governance.nebula.graphd-port:9669}") int graphdPort,
            @Value("${governance.nebula.space:lineage}") String space,
            @Value("${governance.nebula.node-tag:TableField}") String nodeTag,
            @Value("${governance.nebula.edge-type:FieldLineage}") String edgeType) {
        // 安全校验：防止 nGQL 注入
        validateNebulaIdentifier(space, "governance.nebula.space");
        validateNebulaIdentifier(nodeTag, "governance.nebula.node-tag");
        validateNebulaIdentifier(edgeType, "governance.nebula.edge-type");

        this.graphdHost = graphdHost;
        this.graphdPort = graphdPort;
        this.space = space;
        this.nodeTag = nodeTag;
        this.edgeType = edgeType;
        // NebulaGraph HTTP 端点（graphd 默认 9669 是 Thrift，HTTP 通常 19669）
        String baseUrl = String.format("http://%s:%d", graphdHost, graphdPort + 10000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("NebulaLineageGraphClient initialized: {}:{}, space={}", graphdHost, graphdPort, space);
    }

    /**
     * 写入字段级血缘到 NebulaGraph。
     *
     * <p>执行步骤：
     * <ol>
     *   <li>为源表字段创建/更新 TableField 节点</li>
     *   <li>为目标表字段创建/更新 TableField 节点</li>
     *   <li>创建 FieldLineage 边（源 → 目标）</li>
     * </ol>
     * NebulaGraph 不可用时降级到内存缓存。
     *
     * @param lineage 字段级血缘
     * @return {@code true} 表示写入成功（含降级成功）
     */
    public boolean writeLineage(FieldLineage lineage) {
        if (lineage == null || lineage.getFieldMappings() == null) {
            log.warn("Cannot write null lineage or lineage with null mappings");
            return false;
        }

        try {
            // 构造 nGQL 批量写入语句
            String ngql = buildNgql(lineage);
            log.debug("Executing nGQL: {}", ngql);

            // 通过 HTTP 执行 nGQL
            Map<String, Object> response = restClient.post()
                    .uri("/api/v1/graph")
                    .body(Map.of("space", space, "stmt", ngql))
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("errors")) {
                log.warn("NebulaGraph write returned errors: {}", response.get("errors"));
                return fallbackToCache(lineage);
            }

            writeSuccessCount.incrementAndGet();
            lineageCache.put(lineage.getTargetTable(), lineage);
            log.info("Lineage written to NebulaGraph: {} → {}, mappings={}",
                    lineage.getSourceTable(), lineage.getTargetTable(),
                    lineage.getFieldMappings().size());
            return true;
        } catch (Exception e) {
            log.warn("NebulaGraph write failed, falling back to cache: {}", e.getMessage());
            writeFailureCount.incrementAndGet();
            return fallbackToCache(lineage);
        }
    }

    /**
     * 查询指定目标表的血缘（从缓存）。
     *
     * @param targetTable 目标表标识符
     * @return 字段级血缘；不存在时返回 null
     */
    public FieldLineage queryLineage(String targetTable) {
        return lineageCache.get(targetTable);
    }

    /**
     * 查询所有缓存的血缘（用于测试断言与一致性校验）。
     *
     * @return 不可变的血缘缓存视图
     */
    public Map<String, FieldLineage> getAllCachedLineage() {
        return Collections.unmodifiableMap(lineageCache);
    }

    /**
     * 查询指定租户的所有缓存血缘（多租户隔离，R10 安全修复）。
     *
     * @param tenantId 租户 ID
     * @return 按 tenantId 过滤后的不可变血缘缓存视图
     */
    public Map<String, FieldLineage> getAllCachedLineage(String tenantId) {
        Map<String, FieldLineage> filtered = new java.util.LinkedHashMap<>();
        for (var entry : lineageCache.entrySet()) {
            FieldLineage lineage = entry.getValue();
            if (lineage != null && tenantId.equals(lineage.getTenantId())) {
                filtered.put(entry.getKey(), lineage);
            }
        }
        return Collections.unmodifiableMap(filtered);
    }

    /**
     * 获取写入统计。
     */
    public Map<String, Long> getWriteStats() {
        Map<String, Long> stats = new java.util.HashMap<>();
        stats.put("successCount", writeSuccessCount.get());
        stats.put("failureCount", writeFailureCount.get());
        stats.put("fallbackCount", fallbackCount.get());
        return stats;
    }

    // -----------------------------------------------------------------------
    // 私有方法
    // -----------------------------------------------------------------------

    private boolean fallbackToCache(FieldLineage lineage) {
        lineageCache.put(lineage.getTargetTable(), lineage);
        fallbackCount.incrementAndGet();
        return true;
    }

    /**
     * 构造 nGQL 写入语句。
     *
     * <p>生成批量 INSERT VERTEX + INSERT EDGE 语句。
     */
    private String buildNgql(FieldLineage lineage) {
        StringBuilder sb = new StringBuilder();

        // Step 1: 创建源表字段节点
        for (FieldLineage.FieldMapping mapping : lineage.getFieldMappings()) {
            if (mapping.getSourceField() != null) {
                String vertexId = escapeVertexId(lineage.getSourceTable(), mapping.getSourceField());
                sb.append(String.format(
                        "INSERT VERTEX %s(tableIdentifier, fieldName) VALUES \"%s\":(\"%s\", \"%s\");",
                        nodeTag, vertexId, lineage.getSourceTable(), mapping.getSourceField()));
            }
        }

        // Step 2: 创建目标表字段节点
        for (FieldLineage.FieldMapping mapping : lineage.getFieldMappings()) {
            if (mapping.getTargetField() != null) {
                String vertexId = escapeVertexId(lineage.getTargetTable(), mapping.getTargetField());
                sb.append(String.format(
                        "INSERT VERTEX %s(tableIdentifier, fieldName) VALUES \"%s\":(\"%s\", \"%s\");",
                        nodeTag, vertexId, lineage.getTargetTable(), mapping.getTargetField()));
            }
        }

        // Step 3: 创建血缘边
        for (FieldLineage.FieldMapping mapping : lineage.getFieldMappings()) {
            if (mapping.getSourceField() != null && mapping.getTargetField() != null) {
                String srcVertex = escapeVertexId(lineage.getSourceTable(), mapping.getSourceField());
                String dstVertex = escapeVertexId(lineage.getTargetTable(), mapping.getTargetField());
                String transformType = mapping.getTransformType() == null ? "DIRECT" : mapping.getTransformType();
                String expression = mapping.getExpression() == null ? "" : mapping.getExpression().replace("\"", "\\\"");
                sb.append(String.format(
                        "INSERT EDGE %s(lineageId, jobId, transformType, expression) VALUES " +
                                "\"%s\"->\"%s\":(\"%s\", \"%s\", \"%s\", \"%s\");",
                        edgeType, srcVertex, dstVertex,
                        lineage.getLineageId(), lineage.getJobId(),
                        transformType, expression));
            }
        }

        return sb.toString();
    }

    private String escapeVertexId(String table, String field) {
        return (table + "." + field).replace("\"", "\\\"");
    }

    // Getter for testing
    /**
     * 校验 Nebula 标识符安全性，防止 nGQL 注入。
     *
     * <p>校验规则：
     * <ul>
     *   <li>非空且长度 ≤ 128</li>
     *   <li>仅允许字母、数字、下划线、连字符</li>
     *   <li>不能以连字符开头（防止 SQL 注释 --）</li>
     *   <li>不能是 nGQL 保留关键字（大小写不敏感）</li>
     * </ul></p>
     *
     * @param identifier 待校验的标识符
     * @param fieldName  字段名（用于错误消息定位）
     * @throws IllegalArgumentException 如果标识符非法
     */
    public void validateNebulaIdentifier(String identifier, String fieldName) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(fieldName + " 长度超过 " + MAX_IDENTIFIER_LENGTH + " 字符");
        }
        if (identifier.startsWith("-")) {
            throw new IllegalArgumentException(fieldName + " 不能以连字符开头");
        }
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                throw new IllegalArgumentException(fieldName + " 含非法字符: '" + c + "'");
            }
        }
        if (NGQL_RESERVED_KEYWORDS.contains(identifier.toUpperCase())) {
            throw new IllegalArgumentException(fieldName + " 是 nGQL 保留关键字: " + identifier);
        }
    }

    public String getSpace() {
        return space;
    }

    public String getNodeTag() {
        return nodeTag;
    }

    public String getEdgeType() {
        return edgeType;
    }
}