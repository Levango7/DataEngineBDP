package com.levango7.dataenginebdp.sqlgateway.calcite.adapter;

import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;

/**
 * Doris 数据源适配器接口——对接 Apache Doris MPP OLAP 引擎。
 *
 * <p>Doris 擅长 OLAP 聚合查询，本适配器在 {@link BaseAdapter} 之上扩展
 * Doris 特有的下推与 Cost 估算能力：</p>
 * <ul>
 *   <li>聚合下推（Aggregate Pushdown）：将 GROUP BY + 聚合函数下推到 Doris 物化视图</li>
 *   <li>Join 下推（Join Pushdown）：将同源 Join 下推到 Doris 分布式执行</li>
 *   <li>分区裁剪（Partition Pruning）：基于 Doris 动态分区裁剪 Tablet 扫描范围</li>
 *   <li>物化视图路由（Materialized View Routing）：自动匹配最优物化视图</li>
 * </ul>
 *
 * <p>对应 Calcite 中通过自定义 {@code DorisRel} 系列节点 + {@code DorisPushDownRule}
 * 实现下推，Cost 模型基于 Doris FE 的统计信息估算。</p>
 *
 * @author shuqing-bigdata
 */
public interface DorisAdapter extends BaseAdapter {

    /**
     * 路由到最优物化视图。
     *
     * <p>Doris 支持基于原表创建物化视图加速聚合查询。本方法根据查询的
     * GROUP BY 列与聚合函数，匹配最优物化视图。</p>
     *
     * @param tableName 原表名
     * @param groupBy   GROUP BY 列列表
     * @param aggFuncs  聚合函数列表（如 ["count(*)", "sum(amount)"]）
     * @return 最优物化视图名，无匹配时返回原 tableName
     */
    String routeMaterializedView(String tableName, java.util.List<String> groupBy,
                                 java.util.List<String> aggFuncs);

    /**
     * 判断指定 Join 是否可下推到 Doris 分布式执行。
     *
     * <p>Doris 支持 Colocate Join / Broadcast Join / Shuffle Join。
     * 本方法检查两表的 Colocate Group 是否一致，决定是否可 Colocate Join。</p>
     *
     * @param leftTable  左表名
     * @param rightTable 右表名
     * @return {@code true} 表示可下推为 Doris Colocate Join
     */
    boolean canColocateJoin(String leftTable, String rightTable);

    /**
     * 获取 Doris 表的 Tablet 数量（用于 Cost 估算）。
     *
     * @param tableName 表名
     * @return Tablet 数量
     */
    int getTabletCount(String tableName);

    /**
     * 获取 Doris 表的估算行数（来自 Doris FE 统计信息）。
     *
     * @param tableName 表名
     * @return 估算行数
     */
    long getEstimatedRowCount(String tableName);

    @Override
    default DataSourceConfig.Type getAdapterType() {
        return DataSourceConfig.Type.DORIS;
    }
}