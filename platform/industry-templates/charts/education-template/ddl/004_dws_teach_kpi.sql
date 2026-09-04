-- education 行业模板 DDL - dws.teach_kpi (教学KPI)
CREATE TABLE IF NOT EXISTS dws.teach_kpi (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '教学KPI'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
