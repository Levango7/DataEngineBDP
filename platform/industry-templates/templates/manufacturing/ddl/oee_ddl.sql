-- =============================================================================
-- File   : oee_ddl.sql
-- Domain : 设备 OEE 分析域 (Overall Equipment Effectiveness)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Source : 制造行业模板 OEE 分析业务模型
-- Class  : 数据分级 L2(内部业务) / L3(敏感运营)
-- Tables : equipment / production_line / shift / equipment_status_log /
--          equipment_oee_daily / equipment_oee_shift / equipment_sensor_metric (7 张)
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- OEE 公式: OEE = 可用率(Availability) × 性能率(Performance) × 质量率(Quality)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. equipment : 设备主表
--    业务含义：设备台账，含设备编码/名称/型号/产线归属/状态
--    数据分级：L2 (内部业务：设备资产信息)
--    分区策略：按 created_at 日期动态分区
--    外键关系：line_id -> production_line.line_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS equipment (
    equipment_id      VARCHAR(64)   NOT NULL                COMMENT '设备ID（业务主键，雪花ID）',
    equipment_code    VARCHAR(64)   NOT NULL                COMMENT '设备编码（唯一业务编码，如 EQP-001）',
    equipment_name    VARCHAR(128)  NOT NULL                COMMENT '设备名称',
    equipment_type    VARCHAR(32)   NOT NULL                COMMENT '设备类型：CNC-数控机床 / ROBOT-机器人 / PRESS-冲压 / INJECTION-注塑 / ASSEMBLY-装配 / TEST-测试',
    model             VARCHAR(64)                            COMMENT '设备型号',
    line_id           VARCHAR(64)   NOT NULL                COMMENT '所属产线ID（外键 -> production_line.line_id）',
    workshop          VARCHAR(64)                            COMMENT '所属车间',
    status            VARCHAR(16)   NOT NULL DEFAULT 'IDLE'  COMMENT '当前状态：RUNNING-运行 / IDLE-待机 / DOWN-停机 / MAINT-维护 / FAULT-故障',
    install_date      DATE                                   COMMENT '安装日期',
    manufacturer      VARCHAR(128)                           COMMENT '制造商',
    rated_capacity    DECIMAL(18,4)                          COMMENT '额定产能（件/小时）',
    rated_speed       DECIMAL(18,4)                          COMMENT '额定节拍速度（件/分钟）',
    iotdb_device_path VARCHAR(256)                           COMMENT 'IoTDB 时序设备路径，如 root.mfg.equipment.EQP-001',
    description       VARCHAR(512)                           COMMENT '设备描述',
    created_at        DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at        DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by        VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号）',
    updated_by        VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (equipment_id, created_at)
COMMENT '设备主表 | 数据分级=L2 | 设备台账/产线归属/状态 | 外键：line_id -> production_line.line_id'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (equipment_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  equipment                IS '设备主表 | 数据分级=L2 | 设备台账/产线归属/状态';
COMMENT ON COLUMN equipment.equipment_id   IS '设备ID（业务主键）';
COMMENT ON COLUMN equipment.equipment_code IS '设备编码（唯一业务编码）';
COMMENT ON COLUMN equipment.equipment_type IS '设备类型：CNC/ROBOT/PRESS/INJECTION/ASSEMBLY/TEST';
COMMENT ON COLUMN equipment.line_id        IS '所属产线ID（外键 -> production_line.line_id）';
COMMENT ON COLUMN equipment.status         IS '当前状态：RUNNING/IDLE/DOWN/MAINT/FAULT';
COMMENT ON COLUMN equipment.rated_capacity IS '额定产能（件/小时）';
COMMENT ON COLUMN equipment.rated_speed    IS '额定节拍速度（件/分钟）';
COMMENT ON COLUMN equipment.iotdb_device_path IS 'IoTDB 时序设备路径，如 root.mfg.equipment.EQP-001';

-- -----------------------------------------------------------------------------
-- 2. production_line : 产线主表
--    业务含义：产线定义，含产线编码/名称/车间/班次模式/节拍
--    数据分级：L2 (内部业务)
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无（被 equipment / equipment_oee_daily 引用）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS production_line (
    line_id         VARCHAR(64)   NOT NULL                COMMENT '产线ID（业务主键）',
    line_code       VARCHAR(64)   NOT NULL                COMMENT '产线编码（唯一业务编码，如 LINE-A01）',
    line_name       VARCHAR(128)  NOT NULL                COMMENT '产线名称',
    workshop        VARCHAR(64)   NOT NULL                COMMENT '所属车间',
    plant           VARCHAR(64)                            COMMENT '所属工厂',
    shift_mode      VARCHAR(16)   NOT NULL DEFAULT 'THREE_SHIFT' COMMENT '班次模式：ONE_SHIFT-单班 / TWO_SHIFT-两班 / THREE_SHIFT-三班',
    target_oee      DECIMAL(6,4)           DEFAULT 0.8500  COMMENT '目标 OEE（如 0.8500 表示 85%）',
    target_output   INT                                   COMMENT '目标日产量（件）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-启用 / INACTIVE-停用',
    description     VARCHAR(512)                          COMMENT '产线描述',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (line_id, updated_at)
COMMENT '产线主表 | 数据分级=L2 | 产线定义/车间/班次模式/节拍'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (line_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  production_line            IS '产线主表 | 数据分级=L2 | 产线定义/车间/班次模式/节拍';
COMMENT ON COLUMN production_line.line_id    IS '产线ID（业务主键）';
COMMENT ON COLUMN production_line.line_code  IS '产线编码（唯一业务编码）';
COMMENT ON COLUMN production_line.shift_mode IS '班次模式：ONE_SHIFT/TWO_SHIFT/THREE_SHIFT';
COMMENT ON COLUMN production_line.target_oee IS '目标 OEE（如 0.8500 表示 85%）';

-- -----------------------------------------------------------------------------
-- 3. shift : 班次主表
--    业务含义：班次定义，含班次编码/名称/开始结束时间/产线归属
--    数据分级：L2 (内部业务)
--    分区策略：按 updated_at 日期动态分区
--    外键关系：line_id -> production_line.line_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shift (
    shift_id        VARCHAR(64)   NOT NULL                COMMENT '班次ID（业务主键）',
    shift_code      VARCHAR(64)   NOT NULL                COMMENT '班次编码（唯一业务编码，如 SHIFT-DAY）',
    shift_name      VARCHAR(64)   NOT NULL                COMMENT '班次名称：早班/中班/夜班',
    line_id         VARCHAR(64)   NOT NULL                COMMENT '所属产线ID（外键 -> production_line.line_id）',
    start_time      TIME          NOT NULL                 COMMENT '班次开始时间（HH:MM:SS）',
    end_time        TIME          NOT NULL                 COMMENT '班次结束时间（HH:MM:SS）',
    is_cross_day    BOOLEAN                DEFAULT FALSE   COMMENT '是否跨天（夜班跨日）',
    rest_minutes    INT                    DEFAULT 60      COMMENT '休息时长（分钟）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-启用 / INACTIVE-停用',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (shift_id, updated_at)
COMMENT '班次主表 | 数据分级=L2 | 班次定义/开始结束时间/产线归属 | 外键：line_id -> production_line.line_id'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (shift_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  shift            IS '班次主表 | 数据分级=L2 | 班次定义/开始结束时间/产线归属';
COMMENT ON COLUMN shift.shift_id   IS '班次ID（业务主键）';
COMMENT ON COLUMN shift.line_id    IS '所属产线ID（外键 -> production_line.line_id）';
COMMENT ON COLUMN shift.is_cross_day IS '是否跨天（夜班跨日）';

-- -----------------------------------------------------------------------------
-- 4. equipment_status_log : 设备状态变更日志表
--    业务含义：设备状态变更记录，含运行/待机/停机/故障时段，用于计算可用率
--    数据分级：L2 (内部业务：设备状态时序)
--    分区策略：按 occurred_at 日期动态分区（日志量大，按天分区便于冷热分离）
--    外键关系：equipment_id -> equipment.equipment_id（弱关联）
--    数据来源：IoTDB 时序数据（设备状态变更事件）经 Flink/Spark 聚合写入
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS equipment_status_log (
    log_id          VARCHAR(64)   NOT NULL                COMMENT '日志ID（业务主键）',
    equipment_id    VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> equipment.equipment_id）',
    line_id         VARCHAR(64)                            COMMENT '产线ID（冗余，便于聚合查询）',
    shift_id        VARCHAR(64)                            COMMENT '班次ID（冗余，便于按班次聚合）',
    status_from     VARCHAR(16)                            COMMENT '变更前状态：RUNNING/IDLE/DOWN/MAINT/FAULT',
    status_to       VARCHAR(16)   NOT NULL                 COMMENT '变更后状态：RUNNING/IDLE/DOWN/MAINT/FAULT',
    occurred_at     DATETIME      NOT NULL                 COMMENT '状态变更发生时间',
    duration_sec    INT                                    COMMENT '该状态持续时长（秒，由下一变更时间差计算）',
    fault_code      VARCHAR(64)                            COMMENT '故障代码（status_to=FAULT 时记录）',
    fault_desc      VARCHAR(256)                           COMMENT '故障描述',
    iotdb_ts        BIGINT                                 COMMENT 'IoTDB 时间戳（毫秒，原始时序时间戳）',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (log_id, occurred_at)
COMMENT '设备状态变更日志表 | 数据分级=L2 | 设备状态时序/故障记录 | 外键：equipment_id -> equipment.equipment_id | 来源：IoTDB 时序数据'
PARTITION BY RANGE (occurred_at) ()
DISTRIBUTED BY HASH (equipment_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  equipment_status_log              IS '设备状态变更日志表 | 数据分级=L2 | 设备状态时序/故障记录 | 来源：IoTDB 时序数据';
COMMENT ON COLUMN equipment_status_log.equipment_id IS '设备ID（外键 -> equipment.equipment_id）';
COMMENT ON COLUMN equipment_status_log.status_to    IS '变更后状态：RUNNING/IDLE/DOWN/MAINT/FAULT';
COMMENT ON COLUMN equipment_status_log.duration_sec IS '该状态持续时长（秒）';
COMMENT ON COLUMN equipment_status_log.iotdb_ts     IS 'IoTDB 时间戳（毫秒，原始时序时间戳）';

-- -----------------------------------------------------------------------------
-- 5. equipment_oee_daily : 设备 OEE 日汇总表
--    业务含义：设备日级 OEE 计算结果，含可用率/性能率/质量率/OEE
--    数据分级：L2 (内部业务：OEE 指标)
--    分区策略：按 stat_date 日期动态分区
--    外键关系：equipment_id -> equipment.equipment_id；line_id -> production_line.line_id
--    计算来源：由 DAG oee_calculation 每日调度计算
--    OEE 公式: oee = availability * performance * quality
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS equipment_oee_daily (
    stat_id         VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    equipment_id    VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> equipment.equipment_id）',
    equipment_code  VARCHAR(64)                            COMMENT '设备编码（冗余，便于查询）',
    line_id         VARCHAR(64)   NOT NULL                 COMMENT '产线ID（外键 -> production_line.line_id）',
    line_code       VARCHAR(64)                            COMMENT '产线编码（冗余）',
    stat_date       DATE          NOT NULL                 COMMENT '统计日期',
    planned_time    DECIMAL(10,2)                          COMMENT '计划生产时间（分钟）',
    run_time        DECIMAL(10,2)                          COMMENT '实际运行时间（分钟）',
    down_time       DECIMAL(10,2)                          COMMENT '停机时间（分钟，含故障+维护）',
    idle_time       DECIMAL(10,2)                          COMMENT '待机时间（分钟）',
    availability    DECIMAL(6,4)                          COMMENT '可用率 = run_time / planned_time',
    ideal_output    INT                                    COMMENT '理论产量（件，= run_time * rated_speed）',
    actual_output   INT                                    COMMENT '实际产量（件）',
    performance     DECIMAL(6,4)                          COMMENT '性能率 = actual_output / ideal_output',
    good_output     INT                                    COMMENT '合格产量（件）',
    defect_output   INT                                    COMMENT '不合格产量（件）',
    quality         DECIMAL(6,4)                          COMMENT '质量率 = good_output / actual_output',
    oee             DECIMAL(6,4)                          COMMENT 'OEE = availability * performance * quality',
    target_oee      DECIMAL(6,4)                          COMMENT '目标 OEE（冗余自产线配置）',
    oee_gap         DECIMAL(6,4)                          COMMENT 'OEE 差距 = oee - target_oee',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_date)
COMMENT '设备 OEE 日汇总表 | 数据分级=L2 | 可用率/性能率/质量率/OEE | OEE=可用率×性能率×质量率 | 由 DAG oee_calculation 计算'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (equipment_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  equipment_oee_daily              IS '设备 OEE 日汇总表 | 数据分级=L2 | OEE=可用率×性能率×质量率';
COMMENT ON COLUMN equipment_oee_daily.availability  IS '可用率 = run_time / planned_time';
COMMENT ON COLUMN equipment_oee_daily.performance   IS '性能率 = actual_output / ideal_output';
COMMENT ON COLUMN equipment_oee_daily.quality       IS '质量率 = good_output / actual_output';
COMMENT ON COLUMN equipment_oee_daily.oee           IS 'OEE = availability * performance * quality';
COMMENT ON COLUMN equipment_oee_daily.oee_gap       IS 'OEE 差距 = oee - target_oee';

-- -----------------------------------------------------------------------------
-- 6. equipment_oee_shift : 设备 OEE 班次汇总表
--    业务含义：设备班次级 OEE 计算结果，粒度更细，用于班次对比分析
--    数据分级：L2 (内部业务：OEE 指标)
--    分区策略：按 stat_date 日期动态分区
--    外键关系：equipment_id -> equipment.equipment_id；shift_id -> shift.shift_id
--    计算来源：由 DAG oee_calculation 每班次结束触发计算
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS equipment_oee_shift (
    stat_id         VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    equipment_id    VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> equipment.equipment_id）',
    line_id         VARCHAR(64)   NOT NULL                 COMMENT '产线ID（外键 -> production_line.line_id）',
    shift_id        VARCHAR(64)   NOT NULL                 COMMENT '班次ID（外键 -> shift.shift_id）',
    shift_code      VARCHAR(64)                            COMMENT '班次编码（冗余）',
    stat_date       DATE          NOT NULL                 COMMENT '统计日期',
    shift_start     DATETIME      NOT NULL                 COMMENT '班次开始时间',
    shift_end       DATETIME      NOT NULL                 COMMENT '班次结束时间',
    planned_time    DECIMAL(10,2)                          COMMENT '计划生产时间（分钟）',
    run_time        DECIMAL(10,2)                          COMMENT '实际运行时间（分钟）',
    down_time       DECIMAL(10,2)                          COMMENT '停机时间（分钟）',
    availability    DECIMAL(6,4)                          COMMENT '可用率 = run_time / planned_time',
    ideal_output    INT                                    COMMENT '理论产量（件）',
    actual_output   INT                                    COMMENT '实际产量（件）',
    performance     DECIMAL(6,4)                          COMMENT '性能率 = actual_output / ideal_output',
    good_output     INT                                    COMMENT '合格产量（件）',
    defect_output   INT                                    COMMENT '不合格产量（件）',
    quality         DECIMAL(6,4)                          COMMENT '质量率 = good_output / actual_output',
    oee             DECIMAL(6,4)                          COMMENT 'OEE = availability * performance * quality',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_date)
COMMENT '设备 OEE 班次汇总表 | 数据分级=L2 | 班次级 OEE | OEE=可用率×性能率×质量率'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (equipment_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  equipment_oee_shift         IS '设备 OEE 班次汇总表 | 数据分级=L2 | 班次级 OEE';
COMMENT ON COLUMN equipment_oee_shift.oee     IS 'OEE = availability * performance * quality';

-- -----------------------------------------------------------------------------
-- 7. equipment_sensor_metric : 设备传感器指标表
--    业务含义：设备传感器采集指标（温度/振动/电流/压力等），来自 IoTDB 时序数据
--    数据分级：L2 (内部业务：设备运行参数)
--    分区策略：按 collected_at 日期动态分区（时序数据量大，按天分区）
--    外键关系：equipment_id -> equipment.equipment_id
--    数据来源：IoTDB 时序数据经 Flink IoTDB Connector 实时接入
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS equipment_sensor_metric (
    metric_id       VARCHAR(64)   NOT NULL                COMMENT '指标ID（业务主键）',
    equipment_id    VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> equipment.equipment_id）',
    metric_name     VARCHAR(64)   NOT NULL                COMMENT '指标名称：temperature-温度 / vibration-振动 / current-电流 / pressure-压力 / speed-转速',
    metric_value    DECIMAL(18,4)                          COMMENT '指标值',
    metric_unit     VARCHAR(32)                            COMMENT '指标单位：℃/mm/s/A/MPa/rpm',
    metric_status   VARCHAR(16)                            COMMENT '指标状态：NORMAL-正常 / WARNING-预警 / ALARM-告警',
    collected_at    DATETIME      NOT NULL                 COMMENT '采集时间',
    iotdb_ts        BIGINT        NOT NULL                 COMMENT 'IoTDB 时间戳（毫秒）',
    iotdb_path      VARCHAR(256)                           COMMENT 'IoTDB 时序路径，如 root.mfg.equipment.EQP-001.temperature',
    created_at      DATETIME      NOT NULL                 COMMENT '入库时间'
)
ENGINE = OLAP
DUPLICATE KEY (metric_id, collected_at)
COMMENT '设备传感器指标表 | 数据分级=L2 | 设备运行参数/温度/振动/电流 | 来源：IoTDB 时序数据经 Flink IoTDB Connector 接入'
PARTITION BY RANGE (collected_at) ()
DISTRIBUTED BY HASH (equipment_id) BUCKETS 32
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-365',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  equipment_sensor_metric              IS '设备传感器指标表 | 数据分级=L2 | 设备运行参数 | 来源：IoTDB 时序数据';
COMMENT ON COLUMN equipment_sensor_metric.equipment_id IS '设备ID（外键 -> equipment.equipment_id）';
COMMENT ON COLUMN equipment_sensor_metric.metric_name  IS '指标名称：temperature/vibration/current/pressure/speed';
COMMENT ON COLUMN equipment_sensor_metric.iotdb_ts     IS 'IoTDB 时间戳（毫秒）';
COMMENT ON COLUMN equipment_sensor_metric.iotdb_path   IS 'IoTDB 时序路径，如 root.mfg.equipment.EQP-001.temperature';

-- =============================================================================
-- OEE DDL 完成：共 7 张表
-- equipment / production_line / shift / equipment_status_log /
-- equipment_oee_daily / equipment_oee_shift / equipment_sensor_metric
-- OEE 公式: OEE = 可用率(Availability) × 性能率(Performance) × 质量率(Quality)
-- 数据来源: IoTDB 时序数据（设备传感器/状态变更）经 Flink/Spark 聚合计算
-- =============================================================================