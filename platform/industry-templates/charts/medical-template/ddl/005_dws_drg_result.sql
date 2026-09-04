-- medical 行业模板 DDL - dws.drg_result (DRG分组)
CREATE TABLE IF NOT EXISTS dws.drg_result (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT 'DRG分组'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
