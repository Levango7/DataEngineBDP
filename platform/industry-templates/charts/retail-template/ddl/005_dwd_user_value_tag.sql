-- retail 行业模板 DDL - dwd.user_value_tag (价值标签)
CREATE TABLE IF NOT EXISTS dwd.user_value_tag (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '价值标签'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
