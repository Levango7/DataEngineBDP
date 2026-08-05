package com.shuqing.bigdata.governance.collector.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 列元数据。
 *
 * <p>描述一张表中单列的名称、数据类型、注释、是否可空等属性。
 * 由各 Collector 通过 JDBC {@code ResultSetMetaData} 或信息_schema查询采集得到，
 * 最终通过 {@code MetadataWriterService} 写入 Catalog 服务。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMetadata {

    /** 列名 */
    private String name;

    /** 列数据类型，如 STRING/INT/BIGINT/ARRAY&lt;STRING&gt; */
    private String type;

    /** 列注释/描述，可空 */
    private String comment;

    /** 是否允许 NULL */
    private boolean nullable;

    /** 是否为分区列（Hive/Doris 表的分区字段） */
    private boolean partitionColumn;

    /** 列序号（从 1 开始） */
    private int ordinalPosition;
}