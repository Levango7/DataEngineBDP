-- =============================================================================
-- File   : quality_trace_ddl.sql
-- Domain : 质量追溯域 (Quality Traceability)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Tables : product_batch / work_order / process_route / process_record /
--          quality_parameter / defect_record / quality_trace_link (7 张)
-- Notice : 支持正向追溯（批次→工序→参数→缺陷）与反向追溯（缺陷→参数→工序→批次）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. product_batch : 产品批次主表
--    业务含义：生产批次，含批次号/产品/数量/状态/来源
--    数据分级：L2 (内部业务：生产批次信息)
--    分区策略：按 created_at 日期动态分区
--    外键关系：line_id -> production_line.line_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product_batch (
    batch_id        VARCHAR(64)   NOT NULL                COMMENT '批次ID（业务主键）',
    batch_no        VARCHAR(64)   NOT NULL                COMMENT '批次号（唯一业务编码，如 BAT-20260808-001）',
    product_code    VARCHAR(64)   NOT NULL                COMMENT '产品编码',
    product_name    VARCHAR(128)                           COMMENT '产品名称',
    line_id         VARCHAR(64)                            COMMENT '生产产线ID（外键 -> production_line.line_id）',
    line_code       VARCHAR(64)                            COMMENT '产线编码（冗余）',
    planned_qty     INT           NOT NULL                 COMMENT '计划数量（件）',
    actual_qty      INT                                    COMMENT '实际数量（件）',
    good_qty        INT                                    COMMENT '合格数量（件）',
    defect_qty      INT                                    COMMENT '不合格数量（件）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED-创建 / RUNNING-生产中 / COMPLETED-完成 / CLOSED-关闭',
    source_batch_id VARCHAR(64)                            COMMENT '来源批次ID（返工/返修时关联原批次）',
    start_time      DATETIME                               COMMENT '生产开始时间',
    end_time        DATETIME                               COMMENT '生产结束时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (batch_id, created_at)
COMMENT '产品批次主表 | 数据分级=L2 | 生产批次/产品/数量/状态 | 外键：line_id -> production_line.line_id'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (batch_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  product_batch              IS '产品批次主表 | 数据分级=L2 | 生产批次/产品/数量/状态';
COMMENT ON COLUMN product_batch.batch_no     IS '批次号（唯一业务编码）';
COMMENT ON COLUMN product_batch.source_batch_id IS '来源批次ID（返工/返修时关联原批次）';

-- -----------------------------------------------------------------------------
-- 2. work_order : 工单主表
--    业务含义：生产工单，含工单号/产品/数量/计划时间/状态
--    数据分级：L2 (内部业务)
--    分区策略：按 created_at 日期动态分区
--    外键关系：batch_id -> product_batch.batch_id；line_id -> production_line.line_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS work_order (
    order_id        VARCHAR(64)   NOT NULL                COMMENT '工单ID（业务主键）',
    order_no        VARCHAR(64)   NOT NULL                COMMENT '工单号（唯一业务编码，如 WO-20260808-001）',
    batch_id        VARCHAR(64)                            COMMENT '所属批次ID（外键 -> product_batch.batch_id）',
    product_code    VARCHAR(64)   NOT NULL                COMMENT '产品编码',
    product_name    VARCHAR(128)                           COMMENT '产品名称',
    line_id         VARCHAR(64)                            COMMENT '生产产线ID（外键 -> production_line.line_id）',
    planned_qty     INT           NOT NULL                 COMMENT '计划数量（件）',
    actual_qty      INT                                    COMMENT '实际数量（件）',
    good_qty        INT                                    COMMENT '合格数量（件）',
    defect_qty      INT                                    COMMENT '不合格数量（件）',
    priority        VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT '优先级：HIGH-高 / NORMAL-中 / LOW-低',
    status          VARCHAR(16)   NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED/RELEASED-下达/STARTED-开工/COMPLETED-完工/CLOSED-关闭',
    plan_start      DATETIME                               COMMENT '计划开始时间',
    plan_end        DATETIME                               COMMENT '计划结束时间',
    actual_start    DATETIME                               COMMENT '实际开始时间',
    actual_end      DATETIME                               COMMENT '实际结束时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (order_id, created_at)
COMMENT '工单主表 | 数据分级=L2 | 生产工单/产品/数量/计划时间 | 外键：batch_id -> product_batch.batch_id'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (order_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  work_order          IS '工单主表 | 数据分级=L2 | 生产工单/产品/数量/计划时间';
COMMENT ON COLUMN work_order.batch_id IS '所属批次ID（外键 -> product_batch.batch_id）';

-- -----------------------------------------------------------------------------
-- 3. process_route : 工艺路线表
--    业务含义：产品工艺路线定义，含工序序号/工序名称/设备/标准工时
--    数据分级：L2 (内部业务：工艺定义)
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无（被 process_record 引用）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS process_route (
    route_id        VARCHAR(64)   NOT NULL                COMMENT '工艺路线ID（业务主键）',
    product_code    VARCHAR(64)   NOT NULL                COMMENT '产品编码',
    route_name      VARCHAR(128)  NOT NULL                COMMENT '工艺路线名称',
    route_version   VARCHAR(32)   NOT NULL                COMMENT '工艺路线版本（如 v1.0）',
    seq_no          INT           NOT NULL                 COMMENT '工序序号（1,2,3...）',
    process_name    VARCHAR(128)  NOT NULL                COMMENT '工序名称（如：下料/粗加工/精加工/装配/检验/包装）',
    process_code    VARCHAR(64)   NOT NULL                COMMENT '工序编码',
    equipment_type  VARCHAR(32)                            COMMENT '所需设备类型',
    standard_ct     DECIMAL(10,2)                          COMMENT '标准工时（秒/件，cycle time）',
    is_key_process  BOOLEAN                DEFAULT FALSE   COMMENT '是否关键工序（关键工序必检）',
    is_inspection   BOOLEAN                DEFAULT FALSE   COMMENT '是否检验工序',
    description     VARCHAR(512)                           COMMENT '工序描述',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (route_id, updated_at)
COMMENT '工艺路线表 | 数据分级=L2 | 工序序号/工序名称/设备/标准工时 | 关键工序/检验工序标记'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (route_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  process_route                IS '工艺路线表 | 数据分级=L2 | 工序定义/标准工时';
COMMENT ON COLUMN process_route.is_key_process  IS '是否关键工序（关键工序必检）';
COMMENT ON COLUMN process_route.is_inspection   IS '是否检验工序';

-- -----------------------------------------------------------------------------
-- 4. process_record : 工序执行记录表
--    业务含义：工单工序执行记录，含批次/工单/工序/设备/操作员/时间/数量
--    数据分级：L2 (内部业务：工序执行实绩)
--    分区策略：按 occurred_at 日期动态分区
--    外键关系：batch_id -> product_batch.batch_id；order_id -> work_order.order_id；route_id -> process_route.route_id
--    追溯用途：正向追溯（批次→工序记录）与反向追溯（缺陷→工序记录→批次）的核心链路
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS process_record (
    record_id       VARCHAR(64)   NOT NULL                COMMENT '记录ID（业务主键）',
    batch_id        VARCHAR(64)   NOT NULL                COMMENT '批次ID（外键 -> product_batch.batch_id）',
    batch_no        VARCHAR(64)                            COMMENT '批次号（冗余，便于追溯查询）',
    order_id        VARCHAR(64)   NOT NULL                COMMENT '工单ID（外键 -> work_order.order_id）',
    order_no        VARCHAR(64)                            COMMENT '工单号（冗余）',
    route_id        VARCHAR(64)   NOT NULL                COMMENT '工艺路线ID（外键 -> process_route.route_id）',
    seq_no          INT           NOT NULL                 COMMENT '工序序号',
    process_name    VARCHAR(128)  NOT NULL                 COMMENT '工序名称',
    equipment_id    VARCHAR(64)                            COMMENT '执行设备ID（外键 -> equipment.equipment_id）',
    operator_id     VARCHAR(64)                            COMMENT '操作员ID（工号）',
    operator_name   VARCHAR(64)                            COMMENT '操作员姓名',
    input_qty       INT                                    COMMENT '投入数量（件）',
    output_qty      INT                                    COMMENT '产出数量（件）',
    good_qty        INT                                    COMMENT '合格数量（件）',
    defect_qty      INT                                    COMMENT '不合格数量（件）',
    occurred_at     DATETIME      NOT NULL                 COMMENT '工序执行时间',
    duration_sec    INT                                    COMMENT '工序耗时（秒）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'DONE'  COMMENT '执行状态：DONE-完成 / HOLD-暂停 / FAIL-失败',
    remark          VARCHAR(512)                           COMMENT '备注',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (record_id, occurred_at)
COMMENT '工序执行记录表 | 数据分级=L2 | 批次/工单/工序/设备/操作员/数量 | 正反向追溯核心链路'
PARTITION BY RANGE (occurred_at) ()
DISTRIBUTED BY HASH (batch_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  process_record            IS '工序执行记录表 | 数据分级=L2 | 正反向追溯核心链路';
COMMENT ON COLUMN process_record.batch_id   IS '批次ID（外键 -> product_batch.batch_id）';
COMMENT ON COLUMN process_record.order_id   IS '工单ID（外键 -> work_order.order_id）';
COMMENT ON COLUMN process_record.route_id   IS '工艺路线ID（外键 -> process_route.route_id）';

-- -----------------------------------------------------------------------------
-- 5. quality_parameter : 质量参数表
--    业务含义：工序质量参数记录（尺寸/重量/温度/压力等），含标准值/实测值/判定
--    数据分级：L2 (内部业务：质量参数)
--    分区策略：按 measured_at 日期动态分区
--    外键关系：record_id -> process_record.record_id
--    追溯用途：参数级追溯，支持缺陷根因定位（参数超差→缺陷关联）
--    数据来源：部分参数来自 IoTDB 时序数据（设备在线检测参数）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quality_parameter (
    param_id        VARCHAR(64)   NOT NULL                COMMENT '参数ID（业务主键）',
    record_id       VARCHAR(64)   NOT NULL                COMMENT '工序记录ID（外键 -> process_record.record_id）',
    batch_id        VARCHAR(64)                            COMMENT '批次ID（冗余，便于追溯）',
    param_name      VARCHAR(64)   NOT NULL                 COMMENT '参数名称（如：外径/内径/长度/重量/温度/压力/硬度）',
    param_code      VARCHAR(64)   NOT NULL                 COMMENT '参数编码',
    standard_value  DECIMAL(18,4)                          COMMENT '标准值（名义值）',
    upper_limit     DECIMAL(18,4)                          COMMENT '上限（规格上限 USL）',
    lower_limit     DECIMAL(18,4)                          COMMENT '下限（规格下限 LSL）',
    measured_value  DECIMAL(18,4)                          COMMENT '实测值',
    unit            VARCHAR(32)                            COMMENT '单位（mm/g/℃/MPa/HRC）',
    judgment        VARCHAR(16)   NOT NULL DEFAULT 'PASS'  COMMENT '判定：PASS-合格 / FAIL-不合格 / WARNING-预警',
    deviation       DECIMAL(18,4)                          COMMENT '偏差 = measured_value - standard_value',
    cpk             DECIMAL(6,2)                           COMMENT '工序能力指数 Cpk（批量计算时填充）',
    measured_at     DATETIME      NOT NULL                 COMMENT '测量时间',
    measured_by     VARCHAR(64)                            COMMENT '测量人/设备（工号或设备编码）',
    iotdb_path      VARCHAR(256)                           COMMENT 'IoTDB 时序路径（在线检测参数来源）',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (param_id, measured_at)
COMMENT '质量参数表 | 数据分级=L2 | 工序质量参数/标准值/实测值/判定 | 参数级追溯 | 部分来源 IoTDB'
PARTITION BY RANGE (measured_at) ()
DISTRIBUTED BY HASH (record_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  quality_parameter                IS '质量参数表 | 数据分级=L2 | 参数级追溯';
COMMENT ON COLUMN quality_parameter.upper_limit     IS '上限（规格上限 USL）';
COMMENT ON COLUMN quality_parameter.lower_limit     IS '下限（规格下限 LSL）';
COMMENT ON COLUMN quality_parameter.judgment        IS '判定：PASS/FAIL/WARNING';
COMMENT ON COLUMN quality_parameter.cpk             IS '工序能力指数 Cpk';

-- -----------------------------------------------------------------------------
-- 6. defect_record : 缺陷记录表
--    业务含义：不合格缺陷记录，含缺陷类型/位置/原因/处理方式
--    数据分级：L2 (内部业务：缺陷信息)
--    分区策略：按 occurred_at 日期动态分区
--    外键关系：batch_id -> product_batch.batch_id；record_id -> process_record.record_id
--    追溯用途：反向追溯起点（缺陷→参数→工序→批次→供应商→原料）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS defect_record (
    defect_id       VARCHAR(64)   NOT NULL                COMMENT '缺陷ID（业务主键）',
    batch_id        VARCHAR(64)   NOT NULL                COMMENT '批次ID（外键 -> product_batch.batch_id）',
    batch_no        VARCHAR(64)                            COMMENT '批次号（冗余）',
    record_id       VARCHAR(64)                            COMMENT '工序记录ID（外键 -> process_record.record_id）',
    order_id        VARCHAR(64)                            COMMENT '工单ID（冗余）',
    defect_code     VARCHAR(64)   NOT NULL                 COMMENT '缺陷编码（如 DEF-001）',
    defect_name     VARCHAR(128)  NOT NULL                 COMMENT '缺陷名称（如：尺寸超差/表面划伤/功能不良/虚焊/缺件）',
    defect_category VARCHAR(32)   NOT NULL                 COMMENT '缺陷类别：APPEARANCE-外观 / DIMENSION-尺寸 / FUNCTION-功能 / MATERIAL-材料 / PROCESS-工艺',
    defect_level    VARCHAR(16)   NOT NULL DEFAULT 'MAJOR' COMMENT '缺陷等级：CRITICAL-致命 / MAJOR-重要 / MINOR-次要',
    defect_qty      INT           NOT NULL                 COMMENT '缺陷数量（件）',
    defect_position VARCHAR(128)                           COMMENT '缺陷位置描述',
    root_cause      VARCHAR(512)                           COMMENT '根因分析',
    action          VARCHAR(32)                            COMMENT '处理方式：REWORK-返工 / REPAIR-返修 / SCRAP-报废 / ACCEPT-让步接收',
    action_result   VARCHAR(256)                           COMMENT '处理结果',
    occurred_at     DATETIME      NOT NULL                 COMMENT '缺陷发生时间',
    found_by        VARCHAR(64)                            COMMENT '发现人（工号）',
    found_at        DATETIME                               COMMENT '发现时间',
    status          VARCHAR(16)   NOT NULL DEFAULT 'OPEN'  COMMENT '状态：OPEN-待处理 / PROCESSING-处理中 / CLOSED-已关闭',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (defect_id, occurred_at)
COMMENT '缺陷记录表 | 数据分级=L2 | 缺陷类型/位置/原因/处理 | 反向追溯起点'
PARTITION BY RANGE (occurred_at) ()
DISTRIBUTED BY HASH (batch_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  defect_record                IS '缺陷记录表 | 数据分级=L2 | 反向追溯起点';
COMMENT ON COLUMN defect_record.defect_category IS '缺陷类别：APPEARANCE/DIMENSION/FUNCTION/MATERIAL/PROCESS';
COMMENT ON COLUMN defect_record.defect_level    IS '缺陷等级：CRITICAL/MAJOR/MINOR';
COMMENT ON COLUMN defect_record.action          IS '处理方式：REWORK/REPAIR/SCRAP/ACCEPT';

-- -----------------------------------------------------------------------------
-- 7. quality_trace_link : 质量追溯链路表
--    业务含义：追溯关系链路，记录批次/工序/参数/缺陷之间的追溯关联
--    数据分级：L2 (内部业务：追溯关系)
--    分区策略：按 created_at 日期动态分区
--    外键关系：source_id/target_id 指向各业务表主键（由 source_type/target_type 路由）
--    追溯用途：支持正向追溯（批次→工序→参数→缺陷）与反向追溯（缺陷→参数→工序→批次）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quality_trace_link (
    link_id         VARCHAR(64)   NOT NULL                COMMENT '链路ID（业务主键）',
    source_type     VARCHAR(32)   NOT NULL                 COMMENT '源节点类型：BATCH-批次 / PROCESS-工序 / PARAM-参数 / DEFECT-缺陷 / MATERIAL-原料 / SUPPLIER-供应商',
    source_id       VARCHAR(64)   NOT NULL                 COMMENT '源节点ID（指向对应业务表主键）',
    target_type     VARCHAR(32)   NOT NULL                 COMMENT '目标节点类型：BATCH/PROCESS/PARAM/DEFECT/MATERIAL/SUPPLIER',
    target_id       VARCHAR(64)   NOT NULL                 COMMENT '目标节点ID（指向对应业务表主键）',
    relation        VARCHAR(32)   NOT NULL                 COMMENT '关系类型：PRODUCED-产出 / CONSUMED-消耗 / MEASURED-测量 / CAUSED-导致 / DERIVED_FROM-源自 / REWORKED_FROM-返工自',
    trace_direction VARCHAR(16)   NOT NULL DEFAULT 'FORWARD' COMMENT '追溯方向：FORWARD-正向 / BACKWARD-反向 / BOTH-双向',
    batch_id        VARCHAR(64)                            COMMENT '关联批次ID（冗余，便于按批次查询全链路）',
    remark          VARCHAR(256)                           COMMENT '备注',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (link_id, created_at)
COMMENT '质量追溯链路表 | 数据分级=L2 | 追溯关系/正反向追溯 | 支持批次→工序→参数→缺陷全链路追溯'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (source_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  quality_trace_link                 IS '质量追溯链路表 | 数据分级=L2 | 正反向追溯';
COMMENT ON COLUMN quality_trace_link.source_type      IS '源节点类型：BATCH/PROCESS/PARAM/DEFECT/MATERIAL/SUPPLIER';
COMMENT ON COLUMN quality_trace_link.relation         IS '关系类型：PRODUCED/CONSUMED/MEASURED/CAUSED/DERIVED_FROM/REWORKED_FROM';
COMMENT ON COLUMN quality_trace_link.trace_direction  IS '追溯方向：FORWARD-正向 / BACKWARD-反向 / BOTH-双向';

-- =============================================================================
-- 质量追溯 DDL 完成：共 7 张表
-- product_batch / work_order / process_route / process_record /
-- quality_parameter / defect_record / quality_trace_link
-- 追溯能力: 正向（批次→工序→参数→缺陷）+ 反向（缺陷→参数→工序→批次→原料→供应商）
-- =============================================================================