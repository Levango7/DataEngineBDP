-- medical 行业模板 DDL - dwd.emr_struct (结构化病历)
CREATE TABLE IF NOT EXISTS dwd.emr_struct (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '结构化病历'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
