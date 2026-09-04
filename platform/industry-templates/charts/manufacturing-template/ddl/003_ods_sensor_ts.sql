-- manufacturing 行业模板 DDL - ods.sensor_ts (传感器时序)
CREATE TABLE IF NOT EXISTS ods.sensor_ts (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '传感器时序'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
