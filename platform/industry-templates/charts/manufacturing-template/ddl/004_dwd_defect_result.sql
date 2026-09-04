-- manufacturing 行业模板 DDL - dwd.defect_result (缺陷结果)
CREATE TABLE IF NOT EXISTS dwd.defect_result (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '缺陷结果'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
