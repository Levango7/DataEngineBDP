-- agriculture 行业模板 DDL - ads.farm_advice (农事建议)
CREATE TABLE IF NOT EXISTS ads.farm_advice (
    id VARCHAR(64) COMMENT '主键',
    ts DATETIME COMMENT '时间',
    val DOUBLE COMMENT '指标值',
    PRIMARY KEY (id, ts)
) DUPLICATE KEY(id) COMMENT '农事建议'
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES ("replication_num" = "1");
