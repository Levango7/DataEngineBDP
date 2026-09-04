-- government 行业模板 DDL - ads.gov_kpi (政务KPI)
CREATE TABLE IF NOT EXISTS ads.gov_kpi (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '政务KPI'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
