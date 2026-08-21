package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;

/**
 * Trino 数据源适配器接口——对接 Trino（原 PrestoSQL）联邦查询引擎。
 *
 * <p>Trino 本身即联邦查询引擎，可接入 Hive/Iceberg/MySQL/Kafka 等多类 Connector。
 * 本适配器在 {@link BaseAdapter} 之上扩展 Trino 特有能力：</p>
 * <ul>
 *   <li>Connector 路由（Connector Routing）：将表路由到对应 Trino Connector</li>
 *   <li>动态过滤（Dynamic Filtering）：Trino 运行时生成的动态过滤谓词下推</li>
 *   <li>Exchange 下推（Exchange Pushdown）：将 Shuffle/Exchange 下推到 Trino Worker</li>
 *   <li>CTE 内联（CTE Inlining）：将 WITH 子句内联以启用更多优化</li>
 * </ul>
 *
 * <p>对应 Calcite 中将 Trino 视为一个"超级数据源"，其下推规则对应 Trino SPI
 * 的 {@code org.apache.calcite.rel.rules} 系列规则在 Trino SPI 中的等价实现。</p>
 *
 * @author shuqing-bigdata
 */
public interface TrinoAdapter extends BaseAdapter {

    /**
     * 获取表对应的 Trino Connector 名称。
     *
     * <p>Trino 通过 {@code catalog.schema.table} 三段式命名定位表，
     * 每个 catalog 绑定一个 Connector（如 hive、iceberg、mysql）。</p>
     *
     * @param tableName 表名（含 catalog 前缀，如 "hive.db.table"）
     * @return Connector 名称（如 "hive"、"iceberg"）
     */
    String getConnectorName(String tableName);

    /**
     * 判断是否支持动态过滤。
     *
     * <p>Trino 在 Join 运行时基于 build 侧数据生成动态过滤谓词下推到 probe 侧。
     * 部分 Connector（如 Hive、Iceberg）支持动态过滤，其他不支持。</p>
     *
     * @param connectorName Connector 名称
     * @return {@code true} 表示该 Connector 支持动态过滤
     */
    boolean supportsDynamicFiltering(String connectorName);

    /**
     * 将 Trino CTE（WITH 子句）内联。
     *
     * @param sql 含 WITH 子句的 SQL
     * @return 内联后的 SQL
     */
    String inlineCte(String sql);

    /**
     * 获取 Trino 集群 Worker 数量（用于 Cost 估算的并行度）。
     *
     * @return Worker 数量
     */
    int getWorkerCount();

    @Override
    default DataSourceConfig.Type getAdapterType() {
        return DataSourceConfig.Type.TRINO;
    }
}