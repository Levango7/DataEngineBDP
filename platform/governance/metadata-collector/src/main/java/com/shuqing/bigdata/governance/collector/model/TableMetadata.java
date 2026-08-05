package com.shuqing.bigdata.governance.collector.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表元数据。
 *
 * <p>描述一张表的完整元数据：所属库、表名、列、分区、表属性、统计信息等。
 * 由各 Collector 采集得到，最终通过 {@code MetadataWriterService#writeTableMetadata}
 * 写入 Catalog 服务的 {@code POST /api/v1/catalog/tables} 端点。</p>
 *
 * <p>字段命名与 Catalog 服务 {@code Table} 模型对齐，便于直接 JSON 序列化。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableMetadata {

    /** Catalog 主键（UUID），由 Catalog 服务生成；采集阶段可空 */
    private String id;

    /** 所属数据库名 */
    private String databaseName;

    /** 表名 */
    private String tableName;

    /** 表类型：MANAGED_TABLE/EXTERNAL_TABLE/VIEW/OLAP/MySQL 等 */
    private String tableType;

    /** 表注释/描述 */
    private String description;

    /** 数据源类型：HIVE/DORIS/KAFKA/FILESYSTEM */
    private String sourceType;

    /** 列元数据列表 */
    private List<ColumnMetadata> columns = new ArrayList<>();

    /** 分区键列表（Hive 分区字段名） */
    private List<String> partitionKeys = new ArrayList<>();

    /** 表属性映射，例如 {"owner":"alice","transient_lastDdlTime":"...","numRows":"1000"} */
    private Map<String, String> properties = new HashMap<>();

    /** 表统计信息：行数 */
    private Long rowCount;

    /** 表统计信息：总字节数 */
    private Long totalSize;

    /** 表统计信息：文件数 */
    private Integer fileCount;

    /** Doris 表模型：OLAP/MySQL/NATIVE（仅 Doris 源有意义） */
    private String dorisTableModel;

    /** Doris 分桶信息：分桶数（仅 Doris 源有意义） */
    private Integer bucketCount;

    /** Doris 分桶信息：分桶列（仅 Doris 源有意义） */
    private List<String> bucketColumns = new ArrayList<>();

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}