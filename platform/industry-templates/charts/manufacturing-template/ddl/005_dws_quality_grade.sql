-- manufacturing 行业模板 DDL - dws.quality_grade (质量等级)
CREATE TABLE IF NOT EXISTS dws.quality_grade (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '质量等级'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
