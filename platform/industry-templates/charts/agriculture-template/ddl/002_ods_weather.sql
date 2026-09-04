-- agriculture 行业模板 DDL - ods.weather (气象数据)
CREATE TABLE IF NOT EXISTS ods.weather (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '气象数据'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
