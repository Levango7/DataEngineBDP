-- 能源行业模板 DDL - 智能能源物联监控与能耗分析
-- 资产打包进 ConfigMap energy-template-assets，由 import Job 导入 Doris
-- 对应 template energy-iot-monitor：物联遥测 -> 能耗聚合 -> 能效分析 -> 负荷预测 -> 告警

-- 1. ODS 层：物联设备遥测（发电/输变电设备温度/振动/功率/电流）
CREATE TABLE IF NOT EXISTS ods.device_telemetry (
    device_id   VARCHAR(64)   COMMENT '设备编号',
    unit_id     VARCHAR(64)   COMMENT '所属机组',
    ts          DATETIME      COMMENT '采集时间',
    temp        DOUBLE        COMMENT '设备温度(℃)',
    vibration   DOUBLE        COMMENT '机组振动(mm/s)',
    power_kw    DOUBLE        COMMENT '实时功率(kW)',
    current_a   DOUBLE        COMMENT '电流(A)',
    voltage_v   DOUBLE        COMMENT '电压(V)',
    PRIMARY KEY (device_id, ts)
) DUPLICATE KEY(device_id) COMMENT '设备遥测明细'
DISTRIBUTED BY HASH(device_id) BUCKETS 16
PROPERTIES ("replication_num" = "1");