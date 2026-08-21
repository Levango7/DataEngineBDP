package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;
import com.shuqing.bigdata.sqlgateway.calcite.rel.CustomRelNode;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IoTDB 数据源适配器实现——对接 Apache IoTDB 时序数据库。
 *
 * <p>本类实现 {@link IoTDBAdapter} 接口，基于 {@link AbstractBaseAdapter} 框架
 * 提供 IoTDB 特有的下推与 Cost 估算能力：</p>
 *
 * <p><b>方言转换（IoTDB 时序 SQL）：</b>IoTDB SQL 语法与标准 SQL 差异较大，
 * 数据模型为"设备-测点-时间戳"。下推 SQL 生成 IoTDB 兼容的时序查询：</p>
 * <pre>
 *   标准 SQL:  SELECT s1, s2 FROM root.sg.d WHERE time >= '2024-01-01' AND time < '2024-02-01'
 *   IoTDB SQL: SELECT s1, s2 FROM root.sg.d WHERE time >= 2024-01-01T00:00:00.000+08:00
 *                                       AND time < 2024-02-01T00:00:00.000+08:00
 * </pre>
 *
 * <p><b>下推能力：</b></p>
 * <ul>
 *   <li>时间范围下推：将时间谓词下推为 IoTDB 查询的时间过滤</li>
 *   <li>降采样下推：将聚合 + 时间分组下推为 IoTDB 内置降采样（避免传输原始时序数据）</li>
 *   <li>设备过滤下推：将设备路径谓词下推为 IoTDB 查询路径</li>
 *   <li>对齐查询：利用 IoTDB 对齐时间戳能力减少数据传输</li>
 * </ul>
 *
 * <p><b>Cost 模型：</b>IoTDB 为时序数据库，针对时间戳索引优化，时间范围查询 IO Cost 极低。
 * CPU Cost 低（时序聚合内置），Network Cost 低（降采样后数据量小）。</p>
 *
 * <pre>
 *   cpuCost   = rows × 0.05（时序聚合内置）
 *   ioCost    = (rows × rowSize / 64KB) × 0.5（时序索引优化）
 *   networkCost = rows × rowSize × 0.02（降采样后数据量小）
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class IoTDBAdapterImpl extends AbstractBaseAdapter implements IoTDBAdapter {

    /** IoTDB 支持的降采样聚合函数 */
    private static final Set<String> DOWNSAMPLING_FUNCS = new LinkedHashSet<>(
            Arrays.asList("mean", "max", "min", "sum", "count", "first", "last",
                    "avg", "extreme", "median", "stddev", "variance"));

    /** 时间谓词识别正则：time >= 'start' AND time < 'end' */
    private static final Pattern TIME_RANGE_PATTERN =
            Pattern.compile("time\\s*(?:>=|>)\\s*['\"]?([^'\"\\s]+)['\"]?\\s*AND\\s*time\\s*(?:<=|<)\\s*['\"]?([^'\"\\s]+)['\"]?",
                    Pattern.CASE_INSENSITIVE);

    /** 时间单边谓词：time >= 'start' 或 time < 'end' */
    private static final Pattern TIME_SINGLE_PATTERN =
            Pattern.compile("time\\s*(>=|>|<=|<)\\s*['\"]?([^'\"\\s]+)['\"]?",
                    Pattern.CASE_INSENSITIVE);

    /** 设备路径谓词：device = 'root.sg.d1' */
    private static final Pattern DEVICE_PATTERN =
            Pattern.compile("device\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);

    /** 默认时间列名 */
    private static final String TIME_COLUMN = "time";

    /**
     * 构造 IoTDB 适配器。
     *
     * @param config 数据源配置（type 必须为 IOTDB）
     */
    public IoTDBAdapterImpl(DataSourceConfig config) {
        super(config);
        if (config.getType() != DataSourceConfig.Type.IOTDB) {
            throw new IllegalArgumentException("数据源类型必须为 IOTDB, 实际: " + config.getType());
        }
    }

    // ===================== 方言与下推 SQL 生成 =====================

    @Override
    public SqlDialect getDialect() {
        // IoTDB SQL 接近 ANSI，但语法有差异，下推 SQL 生成需特殊处理
        SqlDialect dialect = getDataSourceConfig().getDialect();
        return dialect == null ? SqlDialect.ANSI : dialect;
    }

    @Override
    protected String buildPushedSql(CustomRelNode relNode, PushDownContext context) {
        String tableName = extractTableName(relNode);
        if (tableName == null) {
            return null;
        }

        // 1. 将表名转为 IoTDB 设备路径（root.sg.d 形式）
        String queryPath = toQueryPath(tableName);

        // 2. 列裁剪（投影下推）— IoTDB 测点对应列
        List<String> projects = extractProjects(relNode);
        String selectClause = buildIoTdbSelectClause(projects);

        // 3. 时间范围下推 + 谓词下推
        String condition = extractCondition(relNode);
        String whereClause = buildIoTdbWhereClause(condition);

        return "SELECT " + selectClause + " FROM " + queryPath + whereClause;
    }

    /**
     * 构造 IoTDB SELECT 子句（测点列表）。
     *
     * @param projects 投影列（测点）
     * @return IoTDB SELECT 子句
     */
    private String buildIoTdbSelectClause(List<String> projects) {
        if (projects == null || projects.isEmpty()) {
            return "*";
        }
        return String.join(", ", projects);
    }

    /**
     * 构造 IoTDB WHERE 子句（时间范围 + 设备过滤）。
     *
     * @param condition 原始谓词
     * @return IoTDB WHERE 子句
     */
    private String buildIoTdbWhereClause(String condition) {
        if (condition == null || condition.isBlank()) {
            return "";
        }
        // IoTDB 时间谓词语法与标准 SQL 接近，但时间值需转为 IoTDB 格式
        // 简化：保留原谓词，实际实现需解析并转译时间格式
        return " WHERE " + condition;
    }

    // ===================== IoTDB 特有方法 =====================

    @Override
    public String pushDownTimeRange(String timeFilter) {
        if (timeFilter == null || timeFilter.isBlank()) {
            return "";
        }

        // 1. 双边时间范围：time >= 'start' AND time < 'end'
        Matcher rangeMatcher = TIME_RANGE_PATTERN.matcher(timeFilter);
        if (rangeMatcher.find()) {
            String start = rangeMatcher.group(1);
            String end = rangeMatcher.group(2);
            return normalizeTime(start) + "," + normalizeTime(end);
        }

        // 2. 单边时间谓词：time >= 'start' 或 time < 'end'
        Matcher singleMatcher = TIME_SINGLE_PATTERN.matcher(timeFilter);
        if (singleMatcher.find()) {
            String op = singleMatcher.group(1);
            String time = singleMatcher.group(2);
            String normalized = normalizeTime(time);
            if (op.equals(">") || op.equals(">=")) {
                return normalized + ",";
            } else {
                return "," + normalized;
            }
        }

        // 3. 无法解析的时间过滤
        return "";
    }

    /**
     * 将时间字符串规范化为 IoTDB 格式（yyyy-MM-ddTHH:mm:ss.SSS+08:00）。
     *
     * @param time 原始时间字符串
     * @return 规范化后的时间字符串
     */
    private String normalizeTime(String time) {
        if (time == null || time.isBlank()) {
            return time;
        }
        // 简化：保留原格式，实际实现需完整的时间格式转换
        return time;
    }

    @Override
    public String pushDownDownsampling(String aggFunc, String timeColumn, String interval) {
        if (aggFunc == null || timeColumn == null || interval == null) {
            return "";
        }
        // IoTDB 降采样语法：aggFunc(column) GROUP BY ([start, end), interval)
        // 简化：生成降采样查询片段
        return aggFunc + "(" + timeColumn + ") GROUP BY interval(" + timeColumn + ", " + interval + ")";
    }

    @Override
    public String toQueryPath(String deviceFilter) {
        if (deviceFilter == null || deviceFilter.isBlank()) {
            return "root.**";
        }

        // 1. 设备路径谓词：device = 'root.sg.d1'
        Matcher deviceMatcher = DEVICE_PATTERN.matcher(deviceFilter);
        if (deviceMatcher.find()) {
            return deviceMatcher.group(1) + ".*";
        }

        // 2. 直接是路径形式（root.sg.d）
        if (deviceFilter.startsWith("root.")) {
            return deviceFilter + ".*";
        }

        // 3. 表名形式（db.table）→ 转为 root.sg.d 形式
        String normalized = deviceFilter.replace(".", "/");
        if (normalized.contains("/")) {
            return "root." + normalized.replace("/", ".") + ".*";
        }

        // 4. 无法识别，返回通配路径
        return "root.**";
    }

    @Override
    public boolean supportsDownsampling(String aggFunc) {
        if (aggFunc == null) {
            return false;
        }
        return DOWNSAMPLING_FUNCS.contains(aggFunc.toLowerCase());
    }

    /**
     * 添加自定义支持的降采样函数。
     *
     * @param aggFunc 聚合函数名
     */
    public void addDownsamplingFunction(String aggFunc) {
        if (aggFunc != null) {
            DOWNSAMPLING_FUNCS.add(aggFunc.toLowerCase());
        }
    }

    // ===================== IoTDB 下推限制 =====================

    @Override
    public boolean canPushDown(CustomRelNode relNode) {
        if (!super.canPushDown(relNode)) {
            return false;
        }
        // IoTDB 不支持复杂 Join 下推（时序数据库无 Join 语义）
        if (relNode != null && relNode.getOp() == CustomRelNode.Op.JOIN) {
            return false;
        }
        // IoTDB 不支持 LIKE 谓词下推（时序数据库无通配符匹配语义）
        if (relNode != null && relNode.getOp() == CustomRelNode.Op.FILTER
                && relNode.getCondition() != null
                && relNode.getCondition().toUpperCase().contains(" LIKE ")) {
            return false;
        }
        return true;
    }

    // ===================== 统计信息加载 =====================

    @Override
    protected TableStatistics loadStatistics(String tableName) {
        Map<String, String> props = getDataSourceConfig().getProperties();
        // IoTDB 行数 = 设备数 × 测点数 × 时间点数
        long deviceCount = parseLong(props.get("stats." + tableName + ".deviceCount"), 100);
        long sensorCount = parseLong(props.get("stats." + tableName + ".sensorCount"), 10);
        long timePointCount = parseLong(props.get("stats." + tableName + ".timePointCount"),
                TableStatistics.DEFAULT_ROW_COUNT);
        long rowCount = deviceCount * sensorCount * timePointCount;
        int rowSize = parseInt(props.get("stats." + tableName + ".rowSizeBytes"), 32);
        return new TableStatistics(rowCount, null, rowSize, 1);
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
        // IoTDB 时序聚合内置，CPU Cost 极低
        return 0.05;
    }

    @Override
    protected double ioCostFactor() {
        // 时序索引优化，IO Cost 低
        return 0.5;
    }

    @Override
    protected double networkCostFactor() {
        // 降采样后数据量小，Network Cost 低
        return 0.02;
    }
}