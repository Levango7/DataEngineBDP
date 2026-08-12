-- =============================================================================
-- File   : supply_chain_ddl.sql
-- Domain : 供应链协同域 (Supply Chain Collaboration)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Tables : supplier / purchase_order / inventory / inventory_movement /
--          sales_order / logistics_shipment / supply_chain_event (7 张)
-- Notice : 订单/库存/物流协同，支持供应链全链路可视与预警
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. supplier : 供应商主表
--    业务含义：供应商档案，含编码/名称/等级/联系人/状态
--    数据分级：L2 (内部业务：供应商信息)
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无（被 purchase_order / quality_trace_link 引用）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS supplier (
    supplier_id     VARCHAR(64)   NOT NULL                COMMENT '供应商ID（业务主键）',
    supplier_code   VARCHAR(64)   NOT NULL                COMMENT '供应商编码（唯一业务编码，如 SUP-001）',
    supplier_name   VARCHAR(128)  NOT NULL                COMMENT '供应商名称',
    supplier_level  VARCHAR(16)   NOT NULL DEFAULT 'B'    COMMENT '供应商等级：S/A/B/C/D',
    contact_name    VARCHAR(64)                            COMMENT '联系人姓名',
    contact_phone   VARCHAR(32)                            COMMENT '联系电话',
    contact_email   VARCHAR(128)                           COMMENT '联系邮箱',
    address         VARCHAR(256)                           COMMENT '地址',
    region          VARCHAR(64)                            COMMENT '所在区域',
    lead_time_days  INT                                    COMMENT '平均交货周期（天）',
    on_time_rate    DECIMAL(6,4)                           COMMENT '准时交货率（按历史订单计算）',
    quality_rate    DECIMAL(6,4)                           COMMENT '来料合格率（按历史检验计算）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-合作中 / SUSPENDED-暂停 / BLACKLIST-黑名单',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (supplier_id, updated_at)
COMMENT '供应商主表 | 数据分级=L2 | 供应商档案/等级/交货周期/合格率'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (supplier_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  supplier                IS '供应商主表 | 数据分级=L2 | 供应商档案/等级/交货周期/合格率';
COMMENT ON COLUMN supplier.supplier_level  IS '供应商等级：S/A/B/C/D';
COMMENT ON COLUMN supplier.on_time_rate    IS '准时交货率（按历史订单计算）';
COMMENT ON COLUMN supplier.quality_rate    IS '来料合格率（按历史检验计算）';

-- -----------------------------------------------------------------------------
-- 2. purchase_order : 采购订单表
--    业务含义：采购订单，含订单号/供应商/物料/数量/金额/交期/状态
--    数据分级：L2 (内部业务：采购订单)
--    分区策略：按 created_at 日期动态分区
--    外键关系：supplier_id -> supplier.supplier_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS purchase_order (
    po_id           VARCHAR(64)   NOT NULL                COMMENT '采购订单ID（业务主键）',
    po_no           VARCHAR(64)   NOT NULL                COMMENT '采购订单号（唯一业务编码，如 PO-20260808-001）',
    supplier_id     VARCHAR(64)   NOT NULL                COMMENT '供应商ID（外键 -> supplier.supplier_id）',
    supplier_code   VARCHAR(64)                            COMMENT '供应商编码（冗余）',
    material_code   VARCHAR(64)   NOT NULL                COMMENT '物料编码',
    material_name   VARCHAR(128)                           COMMENT '物料名称',
    spec            VARCHAR(256)                           COMMENT '规格型号',
    quantity        INT           NOT NULL                 COMMENT '采购数量',
    unit            VARCHAR(16)   NOT NULL DEFAULT 'PCS'  COMMENT '单位：PCS/SET/KG/M',
    unit_price      DECIMAL(18,4)                          COMMENT '单价（元）',
    total_amount    DECIMAL(18,2)                          COMMENT '总金额（元）',
    currency        VARCHAR(8)             DEFAULT 'CNY'   COMMENT '币种：CNY/USD/EUR',
    plan_delivery   DATE                                   COMMENT '计划交货日期',
    actual_delivery DATE                                   COMMENT '实际交货日期',
    received_qty    INT                                    COMMENT '已收货数量',
    status          VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT-草稿/CONFIRMED-确认/SENT-已下发/RECEIVING-收货中/CLOSED-关闭',
    remark          VARCHAR(512)                           COMMENT '备注',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (po_id, created_at)
COMMENT '采购订单表 | 数据分级=L2 | 采购订单/供应商/物料/数量/金额/交期 | 外键：supplier_id -> supplier.supplier_id'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (po_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  purchase_order            IS '采购订单表 | 数据分级=L2 | 采购订单/供应商/物料';
COMMENT ON COLUMN purchase_order.supplier_id IS '供应商ID（外键 -> supplier.supplier_id）';

-- -----------------------------------------------------------------------------
-- 3. inventory : 库存主表
--    业务含义：库存台账，含物料/仓库/库位/数量/状态
--    数据分级：L2 (内部业务：库存信息)
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无（被 inventory_movement 引用）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inventory (
    inventory_id    VARCHAR(64)   NOT NULL                COMMENT '库存ID（业务主键）',
    material_code   VARCHAR(64)   NOT NULL                COMMENT '物料编码',
    material_name   VARCHAR(128)                           COMMENT '物料名称',
    warehouse       VARCHAR(64)   NOT NULL                COMMENT '仓库编码',
    location        VARCHAR(64)                            COMMENT '库位编码',
    batch_no        VARCHAR(64)                            COMMENT '库存批次号（关联产品批次或来料批次）',
    quantity        INT           NOT NULL                 COMMENT '当前库存数量',
    unit            VARCHAR(16)   NOT NULL DEFAULT 'PCS'  COMMENT '单位',
    safety_stock    INT                                    COMMENT '安全库存',
    max_stock       INT                                    COMMENT '最大库存',
    reorder_point   INT                                    COMMENT '再订货点',
    stock_status    VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT '库存状态：NORMAL-正常 / LOW-偏低 / OVERSTOCK-积压 / SHORTAGE-短缺',
    inbound_date    DATE                                   COMMENT '最新入库日期',
    expiry_date     DATE                                   COMMENT '过期日期（如有）',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (inventory_id, updated_at)
COMMENT '库存主表 | 数据分级=L2 | 物料/仓库/库位/数量/状态 | 安全库存/再订货点预警'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (inventory_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  inventory               IS '库存主表 | 数据分级=L2 | 物料/仓库/库位/数量/状态';
COMMENT ON COLUMN inventory.safety_stock   IS '安全库存';
COMMENT ON COLUMN inventory.reorder_point  IS '再订货点';
COMMENT ON COLUMN inventory.stock_status   IS '库存状态：NORMAL/LOW/OVERSTOCK/SHORTAGE';

-- -----------------------------------------------------------------------------
-- 4. inventory_movement : 库存流水表
--    业务含义：库存出入库流水，含入库/出库/调拨/盘点
--    数据分级：L2 (内部业务：库存流水)
--    分区策略：按 occurred_at 日期动态分区
--    外键关系：inventory_id -> inventory.inventory_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inventory_movement (
    movement_id     VARCHAR(64)   NOT NULL                COMMENT '流水ID（业务主键）',
    inventory_id    VARCHAR(64)   NOT NULL                COMMENT '库存ID（外键 -> inventory.inventory_id）',
    material_code   VARCHAR(64)   NOT NULL                COMMENT '物料编码（冗余）',
    movement_type   VARCHAR(16)   NOT NULL                 COMMENT '移动类型：INBOUND-入库 / OUTBOUND-出库 / TRANSFER-调拨 / ADJUST-盘点调整',
    direction       VARCHAR(8)    NOT NULL                 COMMENT '方向：IN-入 / OUT-出',
    quantity        INT           NOT NULL                 COMMENT '移动数量',
    before_qty      INT                                    COMMENT '变动前数量',
    after_qty       INT                                    COMMENT '变动后数量',
    ref_type        VARCHAR(32)                            COMMENT '关联单据类型：PO-采购订单 / SO-销售订单 / WO-工单 / MANUAL-手工',
    ref_id          VARCHAR(64)                            COMMENT '关联单据ID',
    ref_no          VARCHAR(64)                            COMMENT '关联单据号',
    warehouse       VARCHAR(64)   NOT NULL                 COMMENT '仓库编码',
    occurred_at     DATETIME      NOT NULL                 COMMENT '发生时间',
    operator_id     VARCHAR(64)                            COMMENT '操作员ID（工号）',
    remark          VARCHAR(256)                           COMMENT '备注',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (movement_id, occurred_at)
COMMENT '库存流水表 | 数据分级=L2 | 入库/出库/调拨/盘点 | 外键：inventory_id -> inventory.inventory_id'
PARTITION BY RANGE (occurred_at) ()
DISTRIBUTED BY HASH (inventory_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  inventory_movement                IS '库存流水表 | 数据分级=L2 | 入库/出库/调拨/盘点';
COMMENT ON COLUMN inventory_movement.movement_type   IS '移动类型：INBOUND/OUTBOUND/TRANSFER/ADJUST';
COMMENT ON COLUMN inventory_movement.ref_type        IS '关联单据类型：PO/SO/WO/MANUAL';

-- -----------------------------------------------------------------------------
-- 5. sales_order : 销售订单表
--    业务含义：销售订单，含订单号/客户/产品/数量/金额/交期/状态
--    数据分级：L2 (内部业务：销售订单)
--    分区策略：按 created_at 日期动态分区
--    外键关系：无（被 logistics_shipment 引用）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sales_order (
    so_id           VARCHAR(64)   NOT NULL                COMMENT '销售订单ID（业务主键）',
    so_no           VARCHAR(64)   NOT NULL                COMMENT '销售订单号（唯一业务编码，如 SO-20260808-001）',
    customer_code   VARCHAR(64)   NOT NULL                COMMENT '客户编码',
    customer_name   VARCHAR(128)                           COMMENT '客户名称',
    product_code    VARCHAR(64)   NOT NULL                COMMENT '产品编码',
    product_name    VARCHAR(128)                           COMMENT '产品名称',
    quantity        INT           NOT NULL                 COMMENT '订单数量',
    unit            VARCHAR(16)   NOT NULL DEFAULT 'PCS'  COMMENT '单位',
    unit_price      DECIMAL(18,4)                          COMMENT '单价（元）',
    total_amount    DECIMAL(18,2)                          COMMENT '总金额（元）',
    currency        VARCHAR(8)             DEFAULT 'CNY'   COMMENT '币种',
    plan_delivery   DATE                                   COMMENT '计划交货日期',
    actual_delivery DATE                                   COMMENT '实际交货日期',
    delivered_qty   INT                                    COMMENT '已发货数量',
    status          VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/CONFIRMED/PRODUCING-生产中/SHIPPING-发货中/COMPLETED-完成/CLOSED-关闭',
    priority        VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT '优先级：HIGH/NORMAL/LOW',
    remark          VARCHAR(512)                           COMMENT '备注',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (so_id, created_at)
COMMENT '销售订单表 | 数据分级=L2 | 销售订单/客户/产品/数量/金额/交期'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (so_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  sales_order        IS '销售订单表 | 数据分级=L2 | 销售订单/客户/产品/数量/金额/交期';
COMMENT ON COLUMN sales_order.status IS '状态：DRAFT/CONFIRMED/PRODUCING/SHIPPING/COMPLETED/CLOSED';

-- -----------------------------------------------------------------------------
-- 6. logistics_shipment : 物流发货表
--    业务含义：物流发货单，含发货号/订单/承运商/物流单号/状态/轨迹
--    数据分级：L2 (内部业务：物流信息)
--    分区策略：按 created_at 日期动态分区
--    外键关系：so_id -> sales_order.so_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS logistics_shipment (
    shipment_id     VARCHAR(64)   NOT NULL                COMMENT '发货单ID（业务主键）',
    shipment_no     VARCHAR(64)   NOT NULL                COMMENT '发货单号（唯一业务编码，如 SHIP-20260808-001）',
    so_id           VARCHAR(64)   NOT NULL                COMMENT '销售订单ID（外键 -> sales_order.so_id）',
    so_no           VARCHAR(64)                            COMMENT '销售订单号（冗余）',
    carrier         VARCHAR(64)   NOT NULL                 COMMENT '承运商（如：SF-顺丰 / JD-京东 / ZTO-中通 / SELF-自送）',
    tracking_no     VARCHAR(64)                            COMMENT '物流单号',
    ship_from       VARCHAR(128)                           COMMENT '发货地址',
    ship_to         VARCHAR(256)                           COMMENT '收货地址',
    consignee       VARCHAR(64)                            COMMENT '收货人',
    consignee_phone VARCHAR(32)                            COMMENT '收货人电话',
    quantity        INT                                    COMMENT '发货数量',
    weight          DECIMAL(10,2)                          COMMENT '重量（kg）',
    volume          DECIMAL(10,2)                          COMMENT '体积（m³）',
    freight         DECIMAL(18,2)                          COMMENT '运费（元）',
    ship_time       DATETIME                               COMMENT '发货时间',
    expect_arrival  DATETIME                               COMMENT '预计到达时间',
    actual_arrival  DATETIME                               COMMENT '实际到达时间',
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待发货 / SHIPPED-已发货 / IN_TRANSIT-运输中 / DELIVERED-已送达 / EXCEPTION-异常',
    tracking_info   STRING                                 COMMENT '物流轨迹 JSON（最新轨迹快照）',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (shipment_id, created_at)
COMMENT '物流发货表 | 数据分级=L2 | 发货单/承运商/物流单号/状态/轨迹 | 外键：so_id -> sales_order.so_id'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (shipment_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  logistics_shipment            IS '物流发货表 | 数据分级=L2 | 发货单/承运商/物流单号/状态/轨迹';
COMMENT ON COLUMN logistics_shipment.so_id      IS '销售订单ID（外键 -> sales_order.so_id）';
COMMENT ON COLUMN logistics_shipment.tracking_info IS '物流轨迹 JSON（最新轨迹快照）';

-- -----------------------------------------------------------------------------
-- 7. supply_chain_event : 供应链事件表
--    业务含义：供应链协同事件，含库存预警/交期预警/订单变更/异常事件
--    数据分级：L2 (内部业务：供应链事件）
--    分区策略：按 occurred_at 日期动态分区
--    外键关系：ref_id 指向各业务表主键（由 ref_type 路由）
--    协同用途：库存偏低/交期延迟/订单变更等事件触发供应链协同响应
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS supply_chain_event (
    event_id        VARCHAR(64)   NOT NULL                COMMENT '事件ID（业务主键）',
    event_type      VARCHAR(32)   NOT NULL                 COMMENT '事件类型：STOCK_LOW-库存偏低 / STOCK_OVER-库存积压 / DELAY-交期延迟 / ORDER_CHANGE-订单变更 / QUALITY_ISSUE-质量问题 / LOGISTICS_EXCEPTION-物流异常',
    event_level     VARCHAR(16)   NOT NULL DEFAULT 'WARN'  COMMENT '事件级别：INFO-信息 / WARN-预警 / CRITICAL-严重',
    ref_type        VARCHAR(32)                            COMMENT '关联单据类型：PO/SO/INVENTORY/SHIPMENT/SUPPLIER',
    ref_id          VARCHAR(64)                            COMMENT '关联单据ID',
    ref_no          VARCHAR(64)                            COMMENT '关联单据号',
    event_desc      VARCHAR(512)                           COMMENT '事件描述',
    impact_analysis VARCHAR(512)                           COMMENT '影响分析',
    action_suggest  VARCHAR(512)                           COMMENT '建议措施',
    action_taken    VARCHAR(512)                           COMMENT '已采取措施',
    occurred_at     DATETIME      NOT NULL                 COMMENT '事件发生时间',
    detected_at     DATETIME      NOT NULL                 COMMENT '事件检测时间',
    status          VARCHAR(16)   NOT NULL DEFAULT 'OPEN'  COMMENT '状态：OPEN-待处理 / PROCESSING-处理中 / RESOLVED-已解决 / IGNORED-已忽略',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (event_id, occurred_at)
COMMENT '供应链事件表 | 数据分级=L2 | 库存预警/交期预警/订单变更/异常事件 | 供应链协同响应触发'
PARTITION BY RANGE (occurred_at) ()
DISTRIBUTED BY HASH (event_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  supply_chain_event            IS '供应链事件表 | 数据分级=L2 | 供应链协同响应触发';
COMMENT ON COLUMN supply_chain_event.event_type  IS '事件类型：STOCK_LOW/STOCK_OVER/DELAY/ORDER_CHANGE/QUALITY_ISSUE/LOGISTICS_EXCEPTION';
COMMENT ON COLUMN supply_chain_event.event_level IS '事件级别：INFO/WARN/CRITICAL';

-- =============================================================================
-- 供应链协同 DDL 完成：共 7 张表
-- supplier / purchase_order / inventory / inventory_movement /
-- sales_order / logistics_shipment / supply_chain_event
-- 协同能力: 订单/库存/物流全链路可视 + 库存预警/交期预警/异常事件协同响应
-- =============================================================================