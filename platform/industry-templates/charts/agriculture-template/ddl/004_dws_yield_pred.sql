-- agriculture 行业模板 DDL - dws.yield_pred (产量预测)
CREATE TABLE IF NOT EXISTS dws.yield_pred (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '产量预测'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
