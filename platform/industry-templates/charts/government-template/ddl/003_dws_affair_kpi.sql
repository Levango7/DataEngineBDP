-- government 行业模板 DDL - dws.affair_kpi (办件KPI)
CREATE TABLE IF NOT EXISTS dws.affair_kpi (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '办件KPI'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
