package com.levango7.dataenginebdp.federated.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联邦治理视图模型。
 *
 * <p>聚合跨集群的元数据/血缘/质量三类治理信息，统一对外暴露。
 * 包含以下内部视图：
 * <ul>
 *   <li>{@link MetadataView} - 元数据视图（表/列/统计）</li>
 *   <li>{@link LineageView} - 血缘视图（节点/边/集群归属）</li>
 *   <li>{@link QualityView} - 质量视图（规则/评分/告警）</li>
 *   <li>{@link DashboardView} - 治理仪表盘聚合视图</li>
 * </ul>
 *
 * <p>本类仅作为数据载体（DTO），不含业务逻辑。
 */
public final class FederatedGovernanceView {

    private FederatedGovernanceView() {
        // 工具类/常量类，禁止实例化
    }

    // ==================================================================
    // 元数据视图
    // ==================================================================

    /** 表元数据。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableMetadata {
        /** 表 ID（全局唯一，格式：clusterId:database.table）。 */
        private String tableId;
        /** 数据库名。 */
        private String database;
        /** 表名。 */
        private String table;
        /** 完整表名（database.table）。 */
        private String fullName;
        /** 来源集群 ID。 */
        private String clusterId;
        /** 表类型（MANAGED/EXTERNAL/VIEW）。 */
        private String tableType;
        /** 列元数据。 */
        @Builder.Default
        private List<ColumnMetadata> columns = new ArrayList<>();
        /** 表注释/描述。 */
        private String description;
        /** 表属性。 */
        @Builder.Default
        private Map<String, String> properties = new LinkedHashMap<>();
        /** 行数统计（可能为 null，表示未统计）。 */
        private Long rowCount;
        /** 存储大小（字节）。 */
        private Long sizeInBytes;
        /** 最后修改时间。 */
        private Instant lastModified;
        /** 元数据版本号。 */
        private long version;
        /** 同步时间戳。 */
        private Instant syncedAt;
    }

    /** 列元数据。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnMetadata {
        /** 列名。 */
        private String name;
        /** 列数据类型。 */
        private String type;
        /** 是否可空。 */
        private boolean nullable;
        /** 是否主键。 */
        private boolean primaryKey;
        /** 列注释。 */
        private String comment;
        /** 列序号。 */
        private int ordinal;
    }

    /** 元数据冲突（同名表在不同集群 schema 不一致）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetadataConflict {
        /** 冲突表名。 */
        private String fullName;
        /** 涉及的集群及表元数据。 */
        @Builder.Default
        private Map<String, TableMetadata> clusterTables = new LinkedHashMap<>();
        /** 冲突类型（SCHEMA_MISMATCH / TYPE_MISMATCH / COLUMN_MISMATCH）。 */
        private String conflictType;
        /** 冲突描述。 */
        private String description;
        /** 检测时间。 */
        private Instant detectedAt;
    }

    /** 元数据视图聚合。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetadataView {
        /** 跨集群表列表。 */
        @Builder.Default
        private List<TableMetadata> tables = new ArrayList<>();
        /** 涉及的集群列表。 */
        @Builder.Default
        private List<String> clusters = new ArrayList<>();
        /** 表总数。 */
        private int totalTables;
        /** 元数据冲突列表。 */
        @Builder.Default
        private List<MetadataConflict> conflicts = new ArrayList<>();
        /** 视图生成时间。 */
        private Instant generatedAt;
    }

    // ==================================================================
    // 血缘视图
    // ==================================================================

    /** 血缘节点（表或字段）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageNode {
        /** 节点 ID（通常为 tableId）。 */
        private String nodeId;
        /** 节点名称。 */
        private String name;
        /** 节点类型（TABLE/COLUMN/VIEW）。 */
        private String nodeType;
        /** 所属集群 ID。 */
        private String clusterId;
        /** 数据库。 */
        private String database;
        /** 显示标签。 */
        private String label;
    }

    /** 血缘边（数据流向）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageEdge {
        /** 边 ID。 */
        private String edgeId;
        /** 源节点 ID（上游）。 */
        private String sourceNodeId;
        /** 目标节点 ID（下游）。 */
        private String targetNodeId;
        /** 边类型（TRANSFORM/DIRECT/COPY）。 */
        private String edgeType;
        /** 转换表达式/描述。 */
        private String transformation;
        /** 是否跨集群。 */
        private boolean crossCluster;
        /** 源集群。 */
        private String sourceClusterId;
        /** 目标集群。 */
        private String targetClusterId;
    }

    /** 血缘图（节点+边）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageGraph {
        /** 节点列表。 */
        @Builder.Default
        private List<LineageNode> nodes = new ArrayList<>();
        /** 边列表。 */
        @Builder.Default
        private List<LineageEdge> edges = new ArrayList<>();
        /** 是否存在跨集群血缘。 */
        private boolean hasCrossCluster;
        /** 涉及的集群列表。 */
        @Builder.Default
        private List<String> clusters = new ArrayList<>();
        /** 图生成时间。 */
        private Instant generatedAt;
    }

    /** 血缘视图聚合。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageView {
        /** 完整血缘图。 */
        private LineageGraph graph;
        /** 上游血缘图。 */
        private LineageGraph upstream;
        /** 下游血缘图。 */
        private LineageGraph downstream;
        /** 视图生成时间。 */
        private Instant generatedAt;
    }

    // ==================================================================
    // 质量视图
    // ==================================================================

    /** 质量维度。 */
    public static final class QualityDimension {
        /** 完整性。 */
        public static final String COMPLETENESS = "COMPLETENESS";
        /** 一致性。 */
        public static final String CONSISTENCY = "CONSISTENCY";
        /** 准确性。 */
        public static final String ACCURACY = "ACCURACY";
        /** 及时性。 */
        public static final String TIMELINESS = "TIMELINESS";
        /** 唯一性。 */
        public static final String UNIQUENESS = "UNIQUENESS";

        private QualityDimension() {
        }
    }

    /** 质量规则。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityRule {
        /** 规则 ID。 */
        private String ruleId;
        /** 规则名称。 */
        private String name;
        /** 质量维度。 */
        private String dimension;
        /** 规则表达式（SQL/DSL）。 */
        private String expression;
        /** 规则描述。 */
        private String description;
        /** 严重级别（INFO/WARN/ERROR/CRITICAL）。 */
        private String severity;
        /** 是否启用。 */
        private boolean enabled;
        /** 应用的集群列表。 */
        @Builder.Default
        private List<String> appliedClusters = new ArrayList<>();
        /** 应用的表列表。 */
        @Builder.Default
        private List<String> appliedTables = new ArrayList<>();
        /** 创建时间。 */
        private Instant createdAt;
        /** 是否模板规则。 */
        private boolean template;
    }

    /** 质量报告（单表）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityReport {
        /** 报告 ID。 */
        private String reportId;
        /** 表 ID。 */
        private String tableId;
        /** 表名。 */
        private String tableName;
        /** 集群 ID。 */
        private String clusterId;
        /** 规则执行结果：ruleId → 通过与否。 */
        @Builder.Default
        private Map<String, Boolean> ruleResults = new LinkedHashMap<>();
        /** 各维度评分：dimension → score(0-100)。 */
        @Builder.Default
        private Map<String, Double> dimensionScores = new LinkedHashMap<>();
        /** 综合评分（0-100）。 */
        private double overallScore;
        /** 检查行数。 */
        private long checkedRows;
        /** 失败行数。 */
        private long failedRows;
        /** 报告生成时间。 */
        private Instant generatedAt;
    }

    /** 质量告警。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityAlert {
        /** 告警 ID。 */
        private String alertId;
        /** 规则 ID。 */
        private String ruleId;
        /** 表 ID。 */
        private String tableId;
        /** 集群 ID。 */
        private String clusterId;
        /** 严重级别。 */
        private String severity;
        /** 告警消息。 */
        private String message;
        /** 告警时间。 */
        private Instant alertedAt;
        /** 是否已确认。 */
        private boolean acknowledged;
    }

    /** 联邦质量评分。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FederatedQualityScore {
        /** 总体评分（0-100）。 */
        private double overallScore;
        /** 各集群评分：clusterId → score。 */
        @Builder.Default
        private Map<String, Double> clusterScores = new LinkedHashMap<>();
        /** 各维度评分：dimension → score。 */
        @Builder.Default
        private Map<String, Double> dimensionScores = new LinkedHashMap<>();
        /** 评分表数量。 */
        private int tableCount;
        /** 评分集群数量。 */
        private int clusterCount;
        /** 评分时间。 */
        private Instant generatedAt;
    }

    /** 质量视图聚合。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityView {
        /** 质量规则列表。 */
        @Builder.Default
        private List<QualityRule> rules = new ArrayList<>();
        /** 质量报告列表。 */
        @Builder.Default
        private List<QualityReport> reports = new ArrayList<>();
        /** 质量告警列表。 */
        @Builder.Default
        private List<QualityAlert> alerts = new ArrayList<>();
        /** 联邦质量评分。 */
        private FederatedQualityScore federatedScore;
        /** 视图生成时间。 */
        private Instant generatedAt;
    }

    // ==================================================================
    // 治理仪表盘
    // ==================================================================

    /** 治理仪表盘聚合视图。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardView {
        /** 元数据统计。 */
        private MetadataView metadata;
        /** 血缘统计。 */
        private LineageView lineage;
        /** 质量统计。 */
        private QualityView quality;
        /** 集群总数。 */
        private int clusterCount;
        /** 表总数。 */
        private int tableCount;
        /** 质量评分。 */
        private double overallQualityScore;
        /** 冲突数量。 */
        private int conflictCount;
        /** 告警数量。 */
        private int alertCount;
        /** 仪表盘生成时间。 */
        private Instant generatedAt;
    }

    /** 元数据同步请求。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncRequest {
        /** 要同步的集群 ID（为空则同步全部集群）。 */
        private String clusterId;
        /** 是否强制全量同步。 */
        private boolean force;
    }

    /** 元数据同步结果。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncResult {
        /** 集群 ID。 */
        private String clusterId;
        /** 是否成功。 */
        private boolean success;
        /** 同步的表数量。 */
        private int syncedTables;
        /** 错误信息。 */
        private String error;
        /** 耗时（毫秒）。 */
        private long elapsedMs;
        /** 同步时间。 */
        private Instant syncedAt;
    }
}