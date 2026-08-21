package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;

/**
 * Iceberg 数据源适配器接口——对接 Apache Iceberg 数据湖表格式。
 *
 * <p>Iceberg 提供 ACID 事务、Schema 演化、分区演化与时间旅行能力。本适配器
 * 在 {@link BaseAdapter} 之上扩展 Iceberg 特有的下推能力：</p>
 * <ul>
 *   <li>分区裁剪（Partition Pruning）：将分区列谓词下推为 Iceberg Scan 的分区过滤</li>
 *   <li>快照选择（Snapshot Selection）：支持 time-travel 查询指定 snapshot-id 或 as-of-timestamp</li>
 *   <li>列裁剪（Column Pruning）：下推投影到 Iceberg Scan 的 selected-columns</li>
 *   <li>谓词下推（Predicate Pushdown）：将 Filter 转为 Iceberg 表 Scan 的 filter</li>
 * </ul>
 *
 * <p>对应 Calcite 中通过自定义 {@code IcebergTableScan}（继承
 * {@code org.apache.calcite.rel.AbstractRelNode}）实现下推，配合
 * {@code IcebergPushDownRule} 在 HepPlanner 中匹配并改写。</p>
 *
 * @author shuqing-bigdata
 */
public interface IcebergAdapter extends BaseAdapter {

    /**
     * 执行分区裁剪：根据分区列谓词返回需扫描的分区列表。
     *
     * @param tableName        表名
     * @param partitionFilter  分区列谓词（如 "dt >= '2024-01-01' AND dt < '2024-02-01'"）
     * @return 需扫描的分区标识列表（如 ["2024-01-01", "2024-01-02", ...]）
     */
    java.util.List<String> prunePartitions(String tableName, String partitionFilter);

    /**
     * 选择查询快照——支持 Iceberg time-travel。
     *
     * @param tableName    表名
     * @param snapshotId   快照 ID（null 表示使用当前最新快照）
     * @param asOfTimestamp 时间戳（毫秒，null 表示忽略），选择该时刻最新的快照
     * @return 选中的快照 ID
     */
    long selectSnapshot(String tableName, Long snapshotId, Long asOfTimestamp);

    /**
     * 判断某列是否为 Iceberg 分区列。
     *
     * @param tableName 表名
     * @param column    列名
     * @return {@code true} 表示该列是分区列
     */
    boolean isPartitionColumn(String tableName, String column);

    /**
     * 获取 Iceberg 表的当前 schema 版本号（Iceberg 支持 schema 演化）。
     *
     * @param tableName 表名
     * @return schema 版本号
     */
    int getSchemaVersion(String tableName);

    @Override
    default DataSourceConfig.Type getAdapterType() {
        return DataSourceConfig.Type.ICEBERG;
    }

}