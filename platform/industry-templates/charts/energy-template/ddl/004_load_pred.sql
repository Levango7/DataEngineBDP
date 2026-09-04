-- 能源行业模板 DDL - 负荷预测结果表（DWS）
CREATE TABLE IF NOT EXISTS dws.load_pred (
    ts          DATETIME      COMMENT '预测时点',
    pred        DOUBLE        COMMENT '预测负荷',
    actual      DOUBLE        COMMENT '实际负荷',
    PRIMARY KEY (ts)
) DUPLICATE KEY(ts) COMMENT '负荷预测结果'
DISTRIBUTED BY HASH(ts) BUCKETS 8
PROPERTIES ("replication_num" = "1");