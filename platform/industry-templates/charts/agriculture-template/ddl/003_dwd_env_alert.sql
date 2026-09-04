-- agriculture 行业模板 DDL - dwd.env_alert (环境告警)
CREATE TABLE IF NOT EXISTS dwd.env_alert (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '环境告警'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
