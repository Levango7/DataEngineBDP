-- 能源行业模板 DDL - 能效 KPI 表（DWS）
CREATE TABLE IF NOT EXISTS dws.energy_kpi (
    unit_id     VARCHAR(64)   COMMENT '机组编号',
    ts          DATETIME      COMMENT '统计时间',
    efficiency  DOUBLE        COMMENT '能效比',
    coal_rate   DOUBLE        COMMENT '煤耗率',
    utilization DOUBLE        COMMENT '利用率',
    PRIMARY KEY (unit_id, ts)
) DUPLICATE KEY(unit_id) COMMENT '机组能效KPI'
DISTRIBUTED BY HASH(unit_id) BUCKETS 8
PROPERTIES ("replication_num" = "1");