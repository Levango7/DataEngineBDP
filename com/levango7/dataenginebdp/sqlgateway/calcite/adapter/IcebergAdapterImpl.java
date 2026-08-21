package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;
import com.shuqing.bigdata.sqlgateway.calcite.rel.CustomRelNode;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iceberg 数据源适配器实现——对接 Apache Iceberg 数据湖表格式。
 *
 * <p>本类实现 {@link IcebergAdapter} 接口，基于 {@link AbstractBaseAdapter} 框架
 * 提供 Iceberg 特有的下推与 Cost 估算能力：</p>
 *
 * <p><b>方言转换（HIVE 方言）：</b>下推 SQL 生成 Iceberg 兼容的 Hive SQL，
 * 包含分区裁剪（{@code WHERE dt IN (...)}）与 time-travel 快照选择
 * （{@code FOR SYSTEM_VERSION AS OF snapshotId}）。</p>
 *
 * <p><b>下推能力：</b></p>
 * <ul>
 *   <li>分区裁剪：识别分区列谓词，下推为分区过滤，减少扫描数据文件数</li>
 *   <li>列裁剪：下推投影到 Iceberg Scan 的 selected-columns</li>
 *   <li>谓词下推：将 Filter 转为 Iceberg 表 Scan 的 filter（基于 Iceberg Expressions）</li>
 *   <li>快照选择：支持 time-travel 查询指定 snapshot-id 或 as-of-timestamp</li>
 * </ul>
 *
 * <p><b>Cost 模型：</b>Iceberg 为数据湖格式，IO Cost 较高（需读取 Parquet/ORC 文件），
 * 但分区裁剪可显著减少 IO。CPU Cost 中等（Parquet 解码），Network Cost 较低
 * （通常与计算同节点）。</p>
 *
 * <pre>
 *   cpuCost   = rows × 0.5（Parquet 解码）
 *   ioCost    = (rows × rowSize / 64KB) × 8.0（数据湖 IO 较高）
 *   networkCost = rows × rowSize × 0.01（本地性较好）
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class IcebergAdapterImpl extends AbstractBaseAdapter implements IcebergAdapter {

    /** 默认分区列名（可通过配置 properties.partition-column 覆盖） */
    private static final String DEFAULT_PARTITION_COLUMN = "dt";

    /** 分区列谓词识别正则：col >= 'date' AND col < 'date' */
    private static final Pattern PARTITION_RANGE_PATTERN =
            Pattern.compile("(\\w+)\\s*(?:>=|>)\\s*['\"]([^'\"]+)['\"]\\s*AND\\s*\\1\\s*(?:<=|<)\\s*['\"]([^'\"]+)['\"]",
                    Pattern.CASE_INSENSITIVE);

    /** 分区列等值谓词：col = 'value' */
    private static final Pattern PARTITION_EQUALITY_PATTERN =
            Pattern.compile("(\\w+)\\s*=\\s*['\"]([^'\"]+)['\"]");

    /** 分区列名 */
    private final String partitionColumn;
    /** 表 schema 版本缓存：表名 → 版本号 */
    private final Map<String, Integer> schemaVersions = new LinkedHashMap<>();
    /** 表分区列缓存：表名 → 是否为分区列 */
    private final Map<String, Set<String>> tablePartitionColumns = new LinkedHashMap<>();

    /**
     * 构造 Iceberg 适配器。
     *
     * @param config 数据源配置（type 必须为 ICEBERG）
     */
    public IcebergAdapterImpl(DataSourceConfig config) {
        super(config);
        if (config.getType() != DataSourceConfig.Type.ICEBERG) {
            throw new IllegalArgumentException("数据源类型必须为 ICEBERG, 实际: " + config.getType());
        }
        this.partitionColumn = config.getProperties()
                .getOrDefault("partition-column", DEFAULT_PARTITION_COLUMN);
    }

    // ===================== 方言与下推 SQL 生成 =====================

    @Override
    public SqlDialect getDialect() {
        SqlDialect dialect = getDataSourceConfig().getDialect();
        return dialect == null ? SqlDialect.HIVE : dialect;
    }

    @Override
    protected String buildPushedSql(CustomRelNode relNode, PushDownContext context) {
        String tableName = extractTableName(relNode);
        if (tableName == null) {
            return null;
        }

        // 1. 列裁剪（投影下推）
        List<String> projects = extractProjects(relNode);
        String selectClause = buildSelectClause(projects);

        // 2. 谓词下推（含分区裁剪）
        String condition = extractCondition(relNode);
        String whereClause = condition != null ? " WHERE " + condition : "";

        // 3. time-travel 快照（通过 context 携带 snapshotId）
        String snapshotSuffix = buildSnapshotSuffix(tableName, context);

        return "SELECT " + selectClause + " FROM " + tableName
                + whereClause + snapshotSuffix;
    }

    /**
     * 构造 Iceberg time-travel 快照后缀（Hive 方言）。
     *
     * @param tableName 表名
     * @param context   下推上下文
     * @return 快照后缀（如 " FOR SYSTEM_VERSION AS OF 123"），无快照时为空
     */
    private String buildSnapshotSuffix(String tableName, PushDownContext context) {
        // 从 context 的已下推操作中查找 snapshot 指令
        if (context.getPushedOperations() != null) {
            for (String op : context.getPushedOperations()) {
                if (op != null && op.startsWith("snapshot:")) {
                    try {
                        long snapshotId = Long.parseLong(op.substring("snapshot:".length()));
                        return " FOR SYSTEM_VERSION AS OF " + snapshotId;
                    } catch (NumberFormatException ignored) {
                        // 忽略格式错误的 snapshot 指令
                    }
                }
            }
        }
        return "";
    }

    // ===================== Iceberg 特有方法 =====================

    @Override
    public List<String> prunePartitions(String tableName, String partitionFilter) {
        if (partitionFilter == null || partitionFilter.isBlank()) {
            return Collections.emptyList();
        }

        List<String> partitions = new ArrayList<>();
        String filter = partitionFilter.trim();

        // 1. 范围谓词：col >= 'start' AND col < 'end' → 展开为日期列表
        Matcher rangeMatcher = PARTITION_RANGE_PATTERN.matcher(filter);
        if (rangeMatcher.find()) {
            String start = rangeMatcher.group(2);
            String end = rangeMatcher.group(3);
            return expandDateRange(start, end);
        }

        // 2. 等值谓词：col = 'value'
        Matcher eqMatcher = PARTITION_EQUALITY_PATTERN.matcher(filter);
        while (eqMatcher.find()) {
            partitions.add(eqMatcher.group(2));
        }
        if (!partitions.isEmpty()) {
            return partitions;
        }

        // 3. 无法解析的分区过滤，返回空列表（保守不下推分区裁剪）
        return Collections.emptyList();
    }

    /**
     * 将日期范围 [start, end) 展开为日期列表（按天）。
     *
     * @param start 起始日期（yyyy-MM-dd）
     * @param end   结束日期（yyyy-MM-dd，不含）
     * @return 日期列表
     */
    private List<String> expandDateRange(String start, String end) {
        List<String> dates = new ArrayList<>();
        try {
            java.time.LocalDate startDate = java.time.LocalDate.parse(start);
            java.time.LocalDate endDate = java.time.LocalDate.parse(end);
            for (java.time.LocalDate d = startDate; d.isBefore(endDate); d = d.plusDays(1)) {
                dates.add(d.toString());
                if (dates.size() >= 366) {
                    break; // 防止范围过大导致 OOM
                }
            }
        } catch (Exception e) {
            // 日期解析失败，返回起止两个值
            dates.add(start);
            dates.add(end);
        }
        return dates;
    }

    @Override
    public long selectSnapshot(String tableName, Long snapshotId, Long asOfTimestamp) {
        // 优先使用显式指定的 snapshotId
        if (snapshotId != null) {
            return snapshotId;
        }
        // 基于 asOfTimestamp 选择快照（简化：返回 timestamp 作为 snapshotId）
        if (asOfTimestamp != null) {
            return asOfTimestamp;
        }
        // 默认返回当前最新快照（简化：返回 0 表示 latest）
        return 0L;
    }

    @Override
    public boolean isPartitionColumn(String tableName, String column) {
        if (column == null) {
            return false;
        }
        // 缓存中查找
        Set<String> partitionCols = tablePartitionColumns.get(tableName);
        if (partitionCols != null) {
            return partitionCols.contains(column);
        }
        // 默认分区列
        return partitionColumn.equals(column);
    }

    /**
     * 声明表的分区列（用于注册元数据）。
     *
     * @param tableName      表名
     * @param partitionCols  分区列集合
     */
    public void declarePartitionColumns(String tableName, Set<String> partitionCols) {
        tablePartitionColumns.put(tableName,
                new LinkedHashSet<>(partitionCols == null ? Collections.emptySet() : partitionCols));
    }

    @Override
    public int getSchemaVersion(String tableName) {
        return schemaVersions.getOrDefault(tableName, 1);
    }

    /**
     * 设置表的 schema 版本（用于 schema 演化场景）。
     *
     * @param tableName 表名
     * @param version   schema 版本号
     */
    public void setSchemaVersion(String tableName, int version) {
        schemaVersions.put(tableName, version);
    }

    // ===================== 统计信息加载 =====================

    @Override
    protected TableStatistics loadStatistics(String tableName) {
        // 从配置 properties 读取统计信息（YAML 声明式）
        Map<String, String> props = getDataSourceConfig().getProperties();
        String rowCountKey = "stats." + tableName + ".rowCount";
        String partitionCountKey = "stats." + tableName + ".partitionCount";

        long rowCount = parseLong(props.get(rowCountKey), TableStatistics.DEFAULT_ROW_COUNT);
        int partitionCount = parseInt(props.get(partitionCountKey), 1);

        // Iceberg 表通常行较大（含多列），默认 200 字节
        int rowSize = parseInt(props.get("stats." + tableName + ".rowSizeBytes"), 200);

        return new TableStatistics(rowCount, null, rowSize, partitionCount);
    }

    private long parseLong(String s, long defaultValue) {
        if (s == null || s.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int parseInt(String s, int defaultValue) {
        if (s == null || s.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ===================== Cost 因子 =====================

    @Override
    protected double cpuCostFactor() {
        // Parquet 解码 CPU 中等
        return 0.5;
    }

    @Override
    protected double ioCostFactor() {
        // 数据湖 IO 较高（需读取远端对象存储）
        return 8.0;
    }

    @Override
    protected double networkCostFactor() {
        // Iceberg 通常与计算同节点或同机房，网络 Cost 较低
        return 0.01;
    }
}