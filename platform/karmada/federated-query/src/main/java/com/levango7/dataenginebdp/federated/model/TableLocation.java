package com.levango7.dataenginebdp.federated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 表定位信息：表在哪个集群。
 *
 * <p>由全局 Catalog 表元数据定位服务（{@code TableLocationService}）解析得到。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableLocation {

    /** 完整表名（database.table）。 */
    private String fullName;

    /** 数据库名。 */
    private String database;

    /** 表名。 */
    private String table;

    /** 表所在集群名。 */
    private String cluster;

    /** 集群端点 URL。 */
    private String clusterUrl;

    /** 集群类型。 */
    private String clusterType;

    /** 是否本地集群。 */
    private boolean local;

    /** 表 ID（Catalog 中的 ID）。 */
    private String tableId;

    /** 表的列模式：列名 → 类型。 */
    private java.util.Map<String, String> schema;

    /** 是否分片表（同一逻辑表分布在多集群）。 */
    private boolean sharded;

    /** 分片所在集群列表（sharded=true 时非空）。 */
    private java.util.List<String> shardClusters;
}