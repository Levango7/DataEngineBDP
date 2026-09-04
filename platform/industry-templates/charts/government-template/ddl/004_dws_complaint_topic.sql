-- government 行业模板 DDL - dws.complaint_topic (诉求主题)
CREATE TABLE IF NOT EXISTS dws.complaint_topic (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '诉求主题'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
