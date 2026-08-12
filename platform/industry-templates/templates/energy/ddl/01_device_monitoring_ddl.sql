-- =============================================================================
-- File   : 01_device_monitoring_ddl.sql
-- Domain : 能源设备监测域（Device Monitoring）
-- Engine : Apache Doris（主）/ Apache Iceberg（备，注释中给出兼容写法）
-- Charset: UTF-8
-- Source : 能源行业模板 设备监测业务模型
-- Class  : 数据分级 L2（内部业务）/ L3（敏感运营）
-- Tables : energy_device / device_realtime_status / device_alarm_record /
--          device_health_score / device_metric_history / device_alarm_rule /
--          device_maintenance_log / device_status_change（8 张）
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- 业务说明：覆盖电力/水/天然气/蒸汽/压缩空气/冷量等能源计量设备的实时监测、
--           告警、健康度评分与维护管理全生命周期
-- =============================================================================
-- 健康度评分公式：
--   health_score = w1*availability_score + w2*performance_score + w3*alarm_score
--   默认权重 w1=0.4, w2=0.4, w3=0.2，取值范围 [0, 100]
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. energy_device : 能源设备主表
--    业务含义：能源计量设备台账，含设备编码/类型/位置/计量介质/额定参数
--    数据分级：L2（内部业务：设备资产信息）
--    分区策略：按 created_at 日期动态分区
--    外键关系：无（被 device_realtime_status / device_alarm_record 等引用）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_device (
    device_id          VARCHAR(64)   NOT NULL                COMMENT '设备ID（业务主键，雪花ID）',
    device_code        VARCHAR(64)   NOT NULL                COMMENT '设备编码（唯一业务编码，如 ELEC-METER-A01）',
    device_name        VARCHAR(128)  NOT NULL                COMMENT '设备名称',
    device_type        VARCHAR(32)   NOT NULL                COMMENT '设备类型：ELECTRIC_METER-电表 / WATER_METER-水表 / GAS_METER-燃气表 / HEAT_METER-热表 / COLD_METER-冷量表 / AIR_METER-压缩空气表 / STEAM_METER-蒸汽表',
    measure_medium     VARCHAR(16)   NOT NULL                COMMENT '计量介质：ELECTRIC-电 / WATER-水 / GAS-气 / STEAM-蒸汽 / AIR-空气 / COLD-冷量 / HEAT-热量',
    location_id        VARCHAR(64)                           COMMENT '所属位置ID（建筑/楼层/区域）',
    location_name      VARCHAR(128)                          COMMENT '位置名称',
    department         VARCHAR(64)                           COMMENT '所属部门',
    status             VARCHAR(16)   NOT NULL DEFAULT 'OFFLINE' COMMENT '当前状态：ONLINE-在线 / OFFLINE-离线 / FAULT-故障 / MAINT-维护 / SCRAPPED-报废',
    manufacturer       VARCHAR(128)                          COMMENT '制造商',
    model              VARCHAR(64)                           COMMENT '设备型号',
    rated_power        DECIMAL(18,4)                         COMMENT '额定功率（kW）',
    rated_capacity     DECIMAL(18,4)                         COMMENT '额定容量（对应介质单位/小时）',
    accuracy_class     VARCHAR(16)                           COMMENT '精度等级（如 0.5S / 1.0 / 2.0）',
    install_date       DATE                                  COMMENT '安装日期',
    commission_date    DATE                                  COMMENT '投运日期',
    iotdb_device_path  VARCHAR(256)                          COMMENT 'IoTDB 时序设备路径，如 root.energy.device.ELEC-METER-A01',
    communication_mode VARCHAR(16)                           COMMENT '通信方式：MODBUS-RTU / MODBUS-TCP / MQTT / OPCUA / IEC104 / DLT645',
    sampling_interval  INT                                   COMMENT '采样间隔（秒）',
    description        VARCHAR(512)                          COMMENT '设备描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人（工号）',
    updated_by         VARCHAR(64)   NOT NULL                COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (device_id, created_at)
COMMENT '能源设备主表 | 数据分级=L2 | 设备台账/计量介质/位置/状态 | IoTDB 时序路径'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (device_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_device                  IS '能源设备主表 | 数据分级=L2 | 设备台账/计量介质/位置/状态';
COMMENT ON COLUMN energy_device.device_id        IS '设备ID（业务主键）';
COMMENT ON COLUMN energy_device.device_code      IS '设备编码（唯一业务编码）';
COMMENT ON COLUMN energy_device.device_type      IS '设备类型：ELECTRIC_METER/WATER_METER/GAS_METER/HEAT_METER/COLD_METER/AIR_METER/STEAM_METER';
COMMENT ON COLUMN energy_device.measure_medium   IS '计量介质：ELECTRIC/WATER/GAS/STEAM/AIR/COLD/HEAT';
COMMENT ON COLUMN energy_device.status           IS '当前状态：ONLINE/OFFLINE/FAULT/MAINT/SCRAPPED';
COMMENT ON COLUMN energy_device.rated_power      IS '额定功率（kW）';
COMMENT ON COLUMN energy_device.iotdb_device_path IS 'IoTDB 时序设备路径，如 root.energy.device.ELEC-METER-A01';

-- -----------------------------------------------------------------------------
-- 2. device_realtime_status : 设备实时状态表
--    业务含义：设备最新采集状态，由 Flink 流作业实时更新，供看板与告警使用
--    数据分级：L2（内部业务：实时运行数据）
--    分区策略：按 updated_at 日期动态分区
--    外键关系：device_id -> energy_device.device_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_realtime_status (
    device_id          VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> energy_device.device_id）',
    device_code        VARCHAR(64)   NOT NULL                COMMENT '设备编码（冗余，便于查询）',
    measure_medium     VARCHAR(16)   NOT NULL                COMMENT '计量介质',
    current_value      DECIMAL(18,4)                         COMMENT '当前累计读数（对应介质单位）',
    instantaneous_rate DECIMAL(18,4)                         COMMENT '瞬时流量/功率（单位/分钟 或 kW）',
    voltage            DECIMAL(10,2)                         COMMENT '电压（V，电表专用）',
    current            DECIMAL(10,2)                         COMMENT '电流（A，电表专用）',
    power_factor       DECIMAL(6,4)                          COMMENT '功率因数（电表专用）',
    temperature        DECIMAL(6,2)                          COMMENT '设备温度（℃）',
    pressure           DECIMAL(10,2)                         COMMENT '管道压力（MPa，气/汽/水表专用）',
    flow_rate          DECIMAL(18,4)                         COMMENT '瞬时流量（m³/h 或 t/h）',
    signal_strength    INT                                   COMMENT '信号强度（dBm）',
    online_status      VARCHAR(16)   NOT NULL DEFAULT 'ONLINE' COMMENT '在线状态：ONLINE-在线 / OFFLINE-离线 / COMM_FAIL-通信中断',
    data_quality       VARCHAR(16)   NOT NULL DEFAULT 'GOOD'  COMMENT '数据质量：GOOD-正常 / SUSPECT-可疑 / BAD-异常',
    last_collect_time  DATETIME      NOT NULL                COMMENT '最近一次采集时间',
    updated_at         DATETIME      NOT NULL                COMMENT '记录更新时间'
)
ENGINE = OLAP
UNIQUE KEY (device_id)
COMMENT '设备实时状态表 | 数据分级=L2 | 最新采集值/瞬时值/数据质量 | 由 Flink 流作业实时更新'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (device_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-90',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  device_realtime_status                  IS '设备实时状态表 | 数据分级=L2 | 最新采集值/瞬时值/数据质量';
COMMENT ON COLUMN device_realtime_status.device_id        IS '设备ID（外键 -> energy_device.device_id）';
COMMENT ON COLUMN device_realtime_status.instantaneous_rate IS '瞬时流量/功率（单位/分钟 或 kW）';
COMMENT ON COLUMN device_realtime_status.online_status    IS '在线状态：ONLINE/OFFLINE/COMM_FAIL';
COMMENT ON COLUMN device_realtime_status.data_quality     IS '数据质量：GOOD/SUSPECT/BAD';

-- -----------------------------------------------------------------------------
-- 3. device_alarm_record : 设备告警记录表
--    业务含义：设备告警历史记录，含告警级别/类型/触发值/确认/恢复
--    数据分级：L3（敏感运营：告警事件）
--    分区策略：按 alarm_time 日期动态分区
--    外键关系：device_id -> energy_device.device_id；rule_id -> device_alarm_rule.rule_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_alarm_record (
    alarm_id           VARCHAR(64)   NOT NULL                COMMENT '告警ID（业务主键）',
    device_id          VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> energy_device.device_id）',
    device_code        VARCHAR(64)   NOT NULL                COMMENT '设备编码（冗余）',
    rule_id            VARCHAR(64)                           COMMENT '触发规则ID（外键 -> device_alarm_rule.rule_id）',
    alarm_type         VARCHAR(32)   NOT NULL                COMMENT '告警类型：THRESHOLD_OVER-越限 / THRESHOLD_UNDER-欠限 / COMM_FAIL-通信中断 / DATA_ABNORMAL-数据异常 / DEVICE_FAULT-设备故障 / CALIBRATION_DRIFT-标定漂移',
    alarm_level        VARCHAR(16)   NOT NULL                COMMENT '告警级别：INFO-提示 / WARNING-预警 / CRITICAL-严重 / EMERGENCY-紧急',
    metric_name        VARCHAR(64)                           COMMENT '告警指标名（如 voltage / current / temperature）',
    trigger_value      DECIMAL(18,4)                         COMMENT '触发值',
    threshold_value    DECIMAL(18,4)                         COMMENT '阈值',
    alarm_time         DATETIME      NOT NULL                COMMENT '告警触发时间',
    recover_time       DATETIME                             COMMENT '告警恢复时间',
    duration_seconds   INT                                   COMMENT '告警持续时长（秒）',
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '告警状态：ACTIVE-活跃 / ACKED-已确认 / RECOVERED-已恢复 / SUPPRESSED-已抑制',
    acked_by           VARCHAR(64)                           COMMENT '确认人（工号）',
    acked_at           DATETIME                             COMMENT '确认时间',
    ack_comment        VARCHAR(512)                          COMMENT '确认备注',
    description        VARCHAR(512)                          COMMENT '告警描述',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (alarm_id, alarm_time)
COMMENT '设备告警记录表 | 数据分级=L3 | 告警级别/触发值/确认/恢复 | 实时告警全生命周期'
PARTITION BY RANGE (alarm_time) ()
DISTRIBUTED BY HASH (device_id) BUCKETS 10
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-365',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  device_alarm_record                IS '设备告警记录表 | 数据分级=L3 | 告警级别/触发值/确认/恢复';
COMMENT ON COLUMN device_alarm_record.alarm_level    IS '告警级别：INFO/WARNING/CRITICAL/EMERGENCY';
COMMENT ON COLUMN device_alarm_record.alarm_type     IS '告警类型：THRESHOLD_OVER/THRESHOLD_UNDER/COMM_FAIL/DATA_ABNORMAL/DEVICE_FAULT/CALIBRATION_DRIFT';
COMMENT ON COLUMN device_alarm_record.status         IS '告警状态：ACTIVE/ACKED/RECOVERED/SUPPRESSED';

-- -----------------------------------------------------------------------------
-- 4. device_health_score : 设备健康度评分表
--    业务含义：设备健康度日级评分，融合可用率/性能率/告警率三维度
--    数据分级：L2（内部业务：设备健康度）
--    分区策略：按 stat_date 日期动态分区
--    外键关系：device_id -> energy_device.device_id
-- 评分公式：health_score = w1*availability_score + w2*performance_score + w3*alarm_score
--          默认 w1=0.4, w2=0.4, w3=0.2，取值范围 [0, 100]
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_health_score (
    score_id            VARCHAR(64)   NOT NULL                COMMENT '评分ID（业务主键）',
    device_id           VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> energy_device.device_id）',
    device_code         VARCHAR(64)   NOT NULL                COMMENT '设备编码（冗余）',
    stat_date           DATE          NOT NULL                COMMENT '统计日期',
    availability_score  DECIMAL(6,2)  NOT NULL                COMMENT '可用率评分 [0,100]：在线时长/总时长*100',
    performance_score   DECIMAL(6,2)  NOT NULL                COMMENT '性能评分 [0,100]：基于额定参数偏离度',
    alarm_score         DECIMAL(6,2)  NOT NULL                COMMENT '告警评分 [0,100]：100 - 告警扣分（按级别加权）',
    health_score        DECIMAL(6,2)  NOT NULL                COMMENT '综合健康度 [0,100]：w1*可用率+w2*性能+w3*告警',
    weight_availability DECIMAL(4,2)  NOT NULL DEFAULT 0.40   COMMENT '可用率权重',
    weight_performance  DECIMAL(4,2)  NOT NULL DEFAULT 0.40   COMMENT '性能权重',
    weight_alarm        DECIMAL(4,2)  NOT NULL DEFAULT 0.20   COMMENT '告警权重',
    health_grade        VARCHAR(8)    NOT NULL                COMMENT '健康等级：EXCELLENT-优秀(>=90) / GOOD-良好(80-90) / FAIR-一般(70-80) / POOR-差(60-70) / CRITICAL-危险(<60)',
    online_duration_sec INT                                   COMMENT '在线时长（秒）',
    total_duration_sec  INT                                   COMMENT '总时长（秒，默认86400）',
    alarm_count         INT                                   COMMENT '告警次数',
    critical_alarm_count INT                                  COMMENT '严重告警次数',
    suggestion           VARCHAR(512)                         COMMENT '运维建议',
    created_at          DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
UNIQUE KEY (device_id, stat_date)
COMMENT '设备健康度评分表 | 数据分级=L2 | 可用率/性能/告警三维度融合评分 | 日级'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (device_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  device_health_score                    IS '设备健康度评分表 | 数据分级=L2 | 可用率/性能/告警三维度融合评分';
COMMENT ON COLUMN device_health_score.health_score       IS '综合健康度 [0,100]：w1*可用率+w2*性能+w3*告警';
COMMENT ON COLUMN device_health_score.health_grade       IS '健康等级：EXCELLENT/GOOD/FAIR/POOR/CRITICAL';

-- -----------------------------------------------------------------------------
-- 5. device_metric_history : 设备指标历史表
--    业务含义：从 IoTDB 同步的设备指标时序历史，供趋势分析与回溯
--    数据分级：L2（内部业务：历史运行数据）
--    分区策略：按 collect_time 日期动态分区
--    外键关系：device_id -> energy_device.device_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_metric_history (
    id                 BIGINT        NOT NULL                COMMENT '自增ID（与 device_id/collect_time 组成唯一键）',
    device_id          VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> energy_device.device_id）',
    device_code        VARCHAR(64)   NOT NULL                COMMENT '设备编码（冗余）',
    metric_name        VARCHAR(64)   NOT NULL                COMMENT '指标名：voltage / current / power / temperature / pressure / flow_rate / cumulative_value',
    metric_value       DECIMAL(18,4)                         COMMENT '指标值',
    metric_unit        VARCHAR(16)                           COMMENT '指标单位：V / A / kW / ℃ / MPa / m3h / t/h / kWh / m3',
    data_quality       VARCHAR(16)   NOT NULL DEFAULT 'GOOD'  COMMENT '数据质量：GOOD/SUSPECT/BAD',
    collect_time       DATETIME      NOT NULL                COMMENT '采集时间（IoTDB 时间戳）',
    ingest_time        DATETIME      NOT NULL                COMMENT '入库时间'
)
ENGINE = OLAP
DUPLICATE KEY (id, collect_time)
COMMENT '设备指标历史表 | 数据分级=L2 | IoTDB 同步时序历史 | 供趋势分析与回溯'
PARTITION BY RANGE (collect_time) ()
DISTRIBUTED BY HASH (device_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-365',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  device_metric_history              IS '设备指标历史表 | 数据分级=L2 | IoTDB 同步时序历史';
COMMENT ON COLUMN device_metric_history.metric_name  IS '指标名：voltage/current/power/temperature/pressure/flow_rate/cumulative_value';
COMMENT ON COLUMN device_metric_history.data_quality IS '数据质量：GOOD/SUSPECT/BAD';

-- -----------------------------------------------------------------------------
-- 6. device_alarm_rule : 告警规则定义表
--    业务含义：设备告警规则配置，含阈值/级别/启用状态/抑制策略
--    数据分级：L2（内部业务：规则配置）
--    分区策略：按 updated_at 日期动态分区
--    外键关系：device_id -> energy_device.device_id（可为空，空表示全局规则）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_alarm_rule (
    rule_id            VARCHAR(64)   NOT NULL                COMMENT '规则ID（业务主键）',
    rule_name          VARCHAR(128)  NOT NULL                COMMENT '规则名称',
    rule_code          VARCHAR(64)   NOT NULL                COMMENT '规则编码（唯一）',
    device_id          VARCHAR(64)                           COMMENT '设备ID（空表示全局规则）',
    device_type        VARCHAR(32)                           COMMENT '设备类型（空表示不限类型）',
    metric_name        VARCHAR(64)   NOT NULL                COMMENT '监控指标名',
    alarm_type         VARCHAR(32)   NOT NULL                COMMENT '告警类型：THRESHOLD_OVER / THRESHOLD_UNDER / COMM_FAIL / DATA_ABNORMAL',
    warning_threshold  DECIMAL(18,4)                         COMMENT '预警阈值',
    critical_threshold DECIMAL(18,4)                         COMMENT '严重阈值',
    emergency_threshold DECIMAL(18,4)                        COMMENT '紧急阈值',
    comparison_op      VARCHAR(8)    NOT NULL DEFAULT 'GT'   COMMENT '比较运算符：GT-大于 / LT-小于 / GE-大于等于 / LE-小于等于 / EQ-等于',
    duration_sec       INT           NOT NULL DEFAULT 0      COMMENT '持续时长阈值（秒，0 表示立即触发）',
    suppress_sec       INT           NOT NULL DEFAULT 300    COMMENT '告警抑制时长（秒，相同告警在抑制期内不重复触发）',
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE   COMMENT '是否启用',
    description        VARCHAR(512)                          COMMENT '规则描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人',
    updated_by         VARCHAR(64)   NOT NULL                COMMENT '更新人'
)
ENGINE = OLAP
UNIQUE KEY (rule_id)
COMMENT '告警规则定义表 | 数据分级=L2 | 阈值/级别/抑制策略 | 设备级与全局级规则'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (rule_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  device_alarm_rule                       IS '告警规则定义表 | 数据分级=L2 | 阈值/级别/抑制策略';
COMMENT ON COLUMN device_alarm_rule.alarm_type            IS '告警类型：THRESHOLD_OVER/THRESHOLD_UNDER/COMM_FAIL/DATA_ABNORMAL';
COMMENT ON COLUMN device_alarm_rule.comparison_op         IS '比较运算符：GT/LT/GE/LE/EQ';

-- -----------------------------------------------------------------------------
-- 7. device_maintenance_log : 设备维护记录表
--    业务含义：设备维护/检修/校准记录，影响健康度评分
--    数据分级：L2（内部业务：维护记录）
--    分区策略：按 maintenance_start 日期动态分区
--    外键关系：device_id -> energy_device.device_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_maintenance_log (
    maintenance_id     VARCHAR(64)   NOT NULL                COMMENT '维护记录ID（业务主键）',
    device_id          VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> energy_device.device_id）',
    device_code        VARCHAR(64)   NOT NULL                COMMENT '设备编码（冗余）',
    maintenance_type   VARCHAR(32)   NOT NULL                COMMENT '维护类型：ROUTINE-例行 / REPAIR-故障维修 / CALIBRATION-校准 / REPLACEMENT-更换 / INSPECTION-巡检',
    maintenance_start  DATETIME      NOT NULL                COMMENT '维护开始时间',
    maintenance_end    DATETIME                             COMMENT '维护结束时间',
    duration_minutes   INT                                   COMMENT '维护时长（分钟）',
    result             VARCHAR(16)   NOT NULL                COMMENT '维护结果：SUCCESS-成功 / PARTIAL-部分完成 / FAILED-失败',
    cost               DECIMAL(18,2)                         COMMENT '维护费用（元）',
    operator           VARCHAR(64)                           COMMENT '执行人',
    description        VARCHAR(512)                          COMMENT '维护内容描述',
    remark             VARCHAR(512)                          COMMENT '备注',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (maintenance_id, maintenance_start)
COMMENT '设备维护记录表 | 数据分级=L2 | 维护/检修/校准记录 | 影响健康度评分'
PARTITION BY RANGE (maintenance_start) ()
DISTRIBUTED BY HASH (device_id) BUCKETS 6
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  device_maintenance_log                  IS '设备维护记录表 | 数据分级=L2 | 维护/检修/校准记录';
COMMENT ON COLUMN device_maintenance_log.maintenance_type IS '维护类型：ROUTINE/REPAIR/CALIBRATION/REPLACEMENT/INSPECTION';
COMMENT ON COLUMN device_maintenance_log.result           IS '维护结果：SUCCESS/PARTIAL/FAILED';

-- -----------------------------------------------------------------------------
-- 8. device_status_change : 设备状态变更日志表
--    业务含义：设备在线/离线/故障状态变更日志，用于可用率计算
--    数据分级：L2（内部业务：状态变更）
--    分区策略：按 occurred_at 日期动态分区
--    外键关系：device_id -> energy_device.device_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_status_change (
    change_id          VARCHAR(64)   NOT NULL                COMMENT '变更ID（业务主键）',
    device_id          VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> energy_device.device_id）',
    device_code        VARCHAR(64)   NOT NULL                COMMENT '设备编码（冗余）',
    status_from        VARCHAR(16)                           COMMENT '变更前状态：ONLINE/OFFLINE/FAULT/MAINT',
    status_to          VARCHAR(16)   NOT NULL                COMMENT '变更后状态：ONLINE/OFFLINE/FAULT/MAINT',
    change_reason      VARCHAR(32)                           COMMENT '变更原因：MANUAL-人工 / AUTO-自动 / ALARM-告警触发 / RECOVER-自动恢复',
    occurred_at        DATETIME      NOT NULL                COMMENT '变更发生时间',
    detected_by        VARCHAR(64)                           COMMENT '检测来源（作业名/系统名）',
    remark             VARCHAR(256)                          COMMENT '备注',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (change_id, occurred_at)
COMMENT '设备状态变更日志表 | 数据分级=L2 | 在线/离线/故障状态变更 | 用于可用率计算'
PARTITION BY RANGE (occurred_at) ()
DISTRIBUTED BY HASH (device_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-365',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  device_status_change             IS '设备状态变更日志表 | 数据分级=L2 | 在线/离线/故障状态变更';
COMMENT ON COLUMN device_status_change.status_to   IS '变更后状态：ONLINE/OFFLINE/FAULT/MAINT';
COMMENT ON COLUMN device_status_change.change_reason IS '变更原因：MANUAL/AUTO/ALARM/RECOVER';

-- =============================================================================
-- 设备监测域 DDL 完成：8 张表
--   energy_device / device_realtime_status / device_alarm_record /
--   device_health_score / device_metric_history / device_alarm_rule /
--   device_maintenance_log / device_status_change
-- =============================================================================