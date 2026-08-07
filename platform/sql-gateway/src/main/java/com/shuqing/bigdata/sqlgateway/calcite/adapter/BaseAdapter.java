package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;
import com.shuqing.bigdata.sqlgateway.calcite.rel.CustomRelNode;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;

import java.util.List;

/**
 * 数据源适配器抽象接口——统一封装异构数据源与 Calcite 联邦优化器的交互。
 *
 * <p>每种数据源（Iceberg/Doris/Trino/IoTDB/ES）实现本接口，向 Calcite 提供：</p>
 * <ul>
 *   <li>{@link #toRel}：将数据源表转换为 Calcite {@code RelNode}（注册 Schema/Table）</li>
 *   <li>{@link #pushDown}：将可下推的操作（filter/project/limit/agg）下推到数据源执行</li>
 *   <li>{@link #costEstimate}：估算在数据源上执行某 RelNode 的 Cost</li>
 *   <li>{@link #getDialect}：返回数据源 SQL 方言，用于生成下推 SQL</li>
 * </ul>
 *
 * <p>本接口对应 Calcite {@code org.apache.calcite.schema.Schema} +
 * {@code RelOptRule}（下推规则）+ {@code RelMetadataProvider}（Cost 元数据）的整合抽象。</p>
 *
 * <p>下推流程：</p>
 * <pre>
 *   Calcite 优化器遍历 RelNode 树
 *     │
 *     ▼  adapter.pushDown(node, ctx)
 *   PushDownResult
 *     ├─ pushedSql：下推到数据源的 SQL（如 "SELECT * FROM t WHERE age>18"）
 *     ├─ remainingRel：保留在联邦层的残余 RelNode（如跨源 Join）
 *     └─ pushedOperations：已下推操作列表
 * </pre>
 *
 * @author shuqing-bigdata
 */
public interface BaseAdapter {

    /**
     * 获取该适配器对应的数据源类型。
     *
     * <p>由各子接口（Iceberg/Doris/Trino/IoTDB/ES）提供默认实现，
     * 便于 {@code CalciteOptimizer} 在注册适配器时按类型分发。</p>
     *
     * @return 数据源类型
     */
    DataSourceConfig.Type getAdapterType();

    /**
     * 获取该适配器对应的数据源配置。
     *
     * @return 数据源配置
     */
    DataSourceConfig getDataSourceConfig();

    /**
     * 将数据源表转换为 Calcite {@code RelNode}（TableScan）。
     *
     * <p>对应 Calcite {@code ScannableTable.scan()} 或 {@code QueryableTable}。
     * 实现应构造一个 {@link CustomRelNode}（op=TABLE_SCAN），携带表名、数据源名、
     * 列 schema 等信息，供优化器后续改写。</p>
     *
     * @param tableName 表名（含 schema 前缀，如 "db.table"）
     * @param columns   列名列表（可为空，表示全表扫描）
     * @return 构造的 TableScan RelNode
     */
    CustomRelNode toRel(String tableName, List<String> columns);

    /**
     * 将可下推的操作下推到数据源执行。
     *
     * <p>对应 Calcite {@code RelOptRule.onMatch()}：当优化器匹配到可下推的
     * RelNode 模式时，调用本方法生成下推 SQL 与残余 RelNode。</p>
     *
     * @param relNode 待下推的 RelNode 子树
     * @param context 下推上下文（携带已下推操作、方言、Cost 权重等）
     * @return 下推结果（含下推 SQL、残余 RelNode、已下推操作列表）
     */
    PushDownResult pushDown(CustomRelNode relNode, PushDownContext context);

    /**
     * 估算在数据源上执行某 RelNode 的 Cost。
     *
     * <p>对应 Calcite {@code RelMetadataQuery.getCumulativeCost()}。
     * 实现应基于数据源统计信息（行数、列基数、索引）估算 CPU/IO/Network Cost。</p>
     *
     * @param relNode 待估算的 RelNode
     * @return Cost 估算结果
     */
    Cost costEstimate(CustomRelNode relNode);

    /**
     * 获取数据源 SQL 方言，用于生成下推 SQL。
     *
     * <p>对应 Calcite {@code org.apache.calcite.sql.SqlDialect}。</p>
     *
     * @return SQL 方言
     */
    SqlDialect getDialect();

    /**
     * 判断指定 RelNode 是否可下推到该数据源。
     *
     * <p>默认实现：检查 RelNode 的所有数据源名是否与本适配器一致（非跨源）。
     * 子类可覆写以增加数据源特定的下推限制（如 IoTDB 不支持复杂 Join）。</p>
     *
     * @param relNode 待检查的 RelNode
     * @return {@code true} 表示可下推
     */
    default boolean canPushDown(CustomRelNode relNode) {
        if (relNode == null) {
            return false;
        }
        String mySource = getDataSourceConfig() == null ? null : getDataSourceConfig().getName();
        for (String src : relNode.collectSourceNames()) {
            if (mySource == null || !mySource.equals(src)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 下推上下文——携带下推过程中需要的元信息。
     *
     * @author shuqing-bigdata
     */
    class PushDownContext {
        /** 已下推操作列表（累积） */
        private final List<String> pushedOperations;
        /** SQL 方言 */
        private final SqlDialect dialect;
        /** Cost 权重：cpu/io/network */
        private final double cpuWeight;
        private final double ioWeight;
        private final double networkWeight;

        public PushDownContext(List<String> pushedOperations, SqlDialect dialect,
                               double cpuWeight, double ioWeight, double networkWeight) {
            this.pushedOperations = pushedOperations;
            this.dialect = dialect;
            this.cpuWeight = cpuWeight;
            this.ioWeight = ioWeight;
            this.networkWeight = networkWeight;
        }

        public List<String> getPushedOperations() {
            return pushedOperations;
        }

        public SqlDialect getDialect() {
            return dialect;
        }

        public double getCpuWeight() {
            return cpuWeight;
        }

        public double getIoWeight() {
            return ioWeight;
        }

        public double getNetworkWeight() {
            return networkWeight;
        }
    }

    /**
     * 下推结果——封装下推 SQL 与残余 RelNode。
     *
     * @author shuqing-bigdata
     */
    class PushDownResult {
        /** 下推到数据源的 SQL（如 "SELECT * FROM t WHERE age>18"） */
        private final String pushedSql;
        /** 保留在联邦层的残余 RelNode（跨源 Join 等），无可下推内容时为原 relNode */
        private final CustomRelNode remainingRel;
        /** 已下推操作列表 */
        private final List<String> pushedOperations;
        /** 下推是否成功 */
        private final boolean success;
        /** 失败原因（success=false 时） */
        private final String failureReason;

        public PushDownResult(String pushedSql, CustomRelNode remainingRel,
                              List<String> pushedOperations, boolean success, String failureReason) {
            this.pushedSql = pushedSql;
            this.remainingRel = remainingRel;
            this.pushedOperations = pushedOperations;
            this.success = success;
            this.failureReason = failureReason;
        }

        public static PushDownResult failure(String reason) {
            return new PushDownResult(null, null, null, false, reason);
        }

        public String getPushedSql() {
            return pushedSql;
        }

        public CustomRelNode getRemainingRel() {
            return remainingRel;
        }

        public List<String> getPushedOperations() {
            return pushedOperations;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getFailureReason() {
            return failureReason;
        }
    }

    /**
     * Cost 估算结果——含 CPU/IO/Network 三维 Cost 与估算行数。
     *
     * @author shuqing-bigdata
     */
    class Cost {
        /** CPU Cost（无量纲） */
        private final double cpuCost;
        /** IO Cost（无量纲） */
        private final double ioCost;
        /** 网络 Cost（无量纲） */
        private final double networkCost;
        /** 估算结果行数 */
        private final double rows;

        public Cost(double cpuCost, double ioCost, double networkCost, double rows) {
            this.cpuCost = cpuCost;
            this.ioCost = ioCost;
            this.networkCost = networkCost;
            this.rows = rows;
        }

        /** 零 Cost */
        public static Cost zero() {
            return new Cost(0, 0, 0, 0);
        }

        /**
         * 按权重计算加权总 Cost。
         *
         * @param cpuWeight      CPU 权重
         * @param ioWeight       IO 权重
         * @param networkWeight  网络权重
         * @return 加权总 Cost
         */
        public double weightedTotal(double cpuWeight, double ioWeight, double networkWeight) {
            return cpuCost * cpuWeight + ioCost * ioWeight + networkCost * networkWeight;
        }

        /** 简单总 Cost（权重均为 1） */
        public double total() {
            return cpuCost + ioCost + networkCost;
        }

        public double getCpuCost() {
            return cpuCost;
        }

        public double getIoCost() {
            return ioCost;
        }

        public double getNetworkCost() {
            return networkCost;
        }

        public double getRows() {
            return rows;
        }

        @Override
        public String toString() {
            return "Cost{cpu=" + cpuCost + ", io=" + ioCost
                    + ", net=" + networkCost + ", rows=" + rows + '}';
        }
    }
}