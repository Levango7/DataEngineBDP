-- 能源行业模板 DDL - 能耗聚合明细表（DWD）
CREATE TABLE IF NOT EXISTS dwd.energy_usage (
    unit_id     VARCHAR(64)   COMMENT '机组编号',
    ts          DATETIME      COMMENT '时段',
    slot        BIGINT        COMMENT '5分钟槽位',
    kwh         DOUBLE        COMMENT '电耗(kWh)',
    mj          DOUBLE        COMMENT '热耗(MJ)',
    PRIMARY KEY (unit_id, ts)
) DUPLICATE KEY(unit_id) COMMENT '能耗聚合明细'
DISTRIBUTED BY HASH(unit_id) BUCKETS 8
PROPERTIES ("replication_num" = "1");