-- finance 行业模板 DDL - dwd.risk_feature (风险特征)
CREATE TABLE IF NOT EXISTS dwd.risk_feature (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '风险特征'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
