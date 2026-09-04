-- medical 行业模板 DDL - ads.med_report (医疗质控报告)
CREATE TABLE IF NOT EXISTS ads.med_report (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '医疗质控报告'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
