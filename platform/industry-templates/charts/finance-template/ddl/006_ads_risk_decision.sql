-- finance 行业模板 DDL - ads.risk_decision (风控决策)
CREATE TABLE IF NOT EXISTS ads.risk_decision (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '风控决策'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
