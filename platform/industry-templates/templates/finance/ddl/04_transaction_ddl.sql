-- =============================================================================
-- File   : 04_transaction_ddl.sql
-- Domain : 交易域 (Transaction)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Source : platform/industry-templates/templates/finance/docs/business-model.md §1.4 §2.4
-- Class  : platform/industry-templates/templates/finance/docs/data-classification.md §2.3
-- Tables : transaction / transaction_detail / aml_alert / transaction_monitor (4 张)
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. transaction : 交易记录主表
--    业务含义：交易主表，记录交易全链路发起
--    数据分级：L3 (交易金额/对手方 财务敏感/个人隐私)
--    分区策略：按 occurred_at 日期动态分区（交易量大，按天分区）
--    外键关系：customer_id -> customer.customer_id（弱关联）
--             debit_account_id  -> account.account_id（弱关联）
--             credit_account_id -> account.account_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction (
    transaction_id      VARCHAR(64)   NOT NULL            COMMENT '交易ID（业务主键，雪花ID）',
    transaction_no      VARCHAR(64)   NOT NULL            COMMENT '交易号（业务编号，L2 内部业务标识）',
    customer_id         VARCHAR(64)   NOT NULL            COMMENT '发起客户ID（外键 -> customer.customer_id）',
    debit_account_id    VARCHAR(64)                       COMMENT '借方账户ID（外键 -> account.account_id）',
    credit_account_id   VARCHAR(64)                       COMMENT '贷方账户ID（外键 -> account.account_id）',
    amount              DECIMAL(18,2) NOT NULL            COMMENT '交易金额（L3 财务敏感）',
    currency            VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '交易币种（L1 公开信息）',
    txn_type            VARCHAR(32)   NOT NULL            COMMENT '交易类型：TRANSFER-转账 / PAYMENT-支付 / DEPOSIT-存款 / WITHDRAW-取款 / REFUND-退款',
    channel             VARCHAR(32)   NOT NULL            COMMENT '交易渠道：COUNTER-柜面 / ONLINE-网银 / MOBILE-移动 / ATM-自助 / API-接口（L2 内部业务）',
    counterparty        VARCHAR(128)                      COMMENT '交易对手方名称（L3 个人/商业隐私）',
    counter_account     VARCHAR(64)                       COMMENT '对手账户号（L4 高敏感，加密存储+强脱敏）',
    status              VARCHAR(16)   NOT NULL DEFAULT 'INIT' COMMENT '交易状态：INIT-初始化 / SUCCESS-成功 / FAIL-失败 / REVERSED-冲正 / PENDING-处理中',
    summary             VARCHAR(256)                      COMMENT '交易摘要',
    occurred_at         DATETIME      NOT NULL            COMMENT '交易发生时间（L2 内部业务）',
    settled_at          DATETIME                          COMMENT '交易完成时间',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间',
    updated_at          DATETIME      NOT NULL            COMMENT '更新时间',
    created_by          VARCHAR(64)   NOT NULL            COMMENT '创建人（工号/系统）',
    updated_by          VARCHAR(64)   NOT NULL            COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (transaction_id, occurred_at)
COMMENT '交易记录主表 | 数据分级=L3 | 交易全链路发起 | 外键：customer_id -> customer；debit/credit_account_id -> account'
PARTITION BY RANGE (occurred_at) ()
DISTRIBUTED BY HASH (transaction_id) BUCKETS 32
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
-- Iceberg 兼容写法：
--   CREATE TABLE transaction (..., occurred_at TIMESTAMP(6)) USING iceberg
--   PARTITIONED BY (days(occurred_at))
--   TBLPROPERTIES ('format-version'='2');
COMMENT ON TABLE  transaction                  IS '交易记录主表 | 数据分级=L3 | 交易全链路发起';
COMMENT ON COLUMN transaction.transaction_id    IS '交易ID（业务主键）';
COMMENT ON COLUMN transaction.transaction_no    IS '交易号（业务编号，L2 内部业务标识）';
COMMENT ON COLUMN transaction.customer_id       IS '发起客户ID（外键 -> customer.customer_id）';
COMMENT ON COLUMN transaction.debit_account_id  IS '借方账户ID（外键 -> account.account_id）';
COMMENT ON COLUMN transaction.credit_account_id IS '贷方账户ID（外键 -> account.account_id）';
COMMENT ON COLUMN transaction.amount            IS '交易金额（L3 财务敏感）';
COMMENT ON COLUMN transaction.currency          IS '交易币种（L1 公开信息）';
COMMENT ON COLUMN transaction.txn_type          IS '交易类型：TRANSFER/PAYMENT/DEPOSIT/WITHDRAW/REFUND';
COMMENT ON COLUMN transaction.channel           IS '交易渠道：COUNTER/ONLINE/MOBILE/ATM/API（L2 内部业务）';
COMMENT ON COLUMN transaction.counterparty      IS '交易对手方名称（L3 个人/商业隐私）';
COMMENT ON COLUMN transaction.counter_account   IS '对手账户号（L4 高敏感，加密存储+强脱敏）';
COMMENT ON COLUMN transaction.status            IS '交易状态：INIT/SUCCESS/FAIL/REVERSED/PENDING';
COMMENT ON COLUMN transaction.summary           IS '交易摘要';
COMMENT ON COLUMN transaction.occurred_at       IS '交易发生时间（L2 内部业务）';
COMMENT ON COLUMN transaction.settled_at        IS '交易完成时间';
COMMENT ON COLUMN transaction.created_at        IS '创建时间（审计字段）';
COMMENT ON COLUMN transaction.updated_at        IS '更新时间（审计字段）';
COMMENT ON COLUMN transaction.created_by        IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN transaction.updated_by        IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 2. transaction_detail : 交易明细表
--    业务含义：交易明细行（一笔交易可包含多行明细）
--    数据分级：L3 (明细金额 财务敏感)
--    分区策略：按 created_at 日期动态分区
--    外键关系：transaction_id -> transaction.transaction_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction_detail (
    detail_id       VARCHAR(64)   NOT NULL                COMMENT '交易明细ID（业务主键）',
    transaction_id  VARCHAR(64)   NOT NULL                COMMENT '交易ID（外键 -> transaction.transaction_id）',
    line_no         INT           NOT NULL                 COMMENT '明细行号（同一交易内自增）',
    item_code       VARCHAR(64)                            COMMENT '明细项目编码（商品/费用编码）',
    item_name       VARCHAR(128)                           COMMENT '明细项目名称',
    item_type       VARCHAR(32)                            COMMENT '明细类型：GOODS-商品 / FEE-费用 / TAX-税费 / DISCOUNT-折扣',
    item_amount     DECIMAL(18,2) NOT NULL                 COMMENT '明细金额（L3 财务敏感）',
    quantity        DECIMAL(18,4)          DEFAULT 1.0000  COMMENT '数量',
    unit_price      DECIMAL(18,4)                          COMMENT '单价（L3 财务敏感）',
    currency        VARCHAR(8)    NOT NULL DEFAULT 'CNY'  COMMENT '币种（L1 公开信息）',
    remark          VARCHAR(256)                           COMMENT '明细备注',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (detail_id, created_at)
COMMENT '交易明细表 | 数据分级=L3 | 交易明细行（一笔交易可包含多行明细） | 外键：transaction_id -> transaction.transaction_id'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (transaction_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  transaction_detail              IS '交易明细表 | 数据分级=L3 | 交易明细行';
COMMENT ON COLUMN transaction_detail.detail_id    IS '交易明细ID（业务主键）';
COMMENT ON COLUMN transaction_detail.transaction_id IS '交易ID（外键 -> transaction.transaction_id）';
COMMENT ON COLUMN transaction_detail.line_no      IS '明细行号（同一交易内自增）';
COMMENT ON COLUMN transaction_detail.item_code    IS '明细项目编码';
COMMENT ON COLUMN transaction_detail.item_name    IS '明细项目名称';
COMMENT ON COLUMN transaction_detail.item_type    IS '明细类型：GOODS/FEE/TAX/DISCOUNT';
COMMENT ON COLUMN transaction_detail.item_amount  IS '明细金额（L3 财务敏感）';
COMMENT ON COLUMN transaction_detail.quantity     IS '数量';
COMMENT ON COLUMN transaction_detail.unit_price   IS '单价（L3 财务敏感）';
COMMENT ON COLUMN transaction_detail.currency     IS '币种（L1 公开信息）';
COMMENT ON COLUMN transaction_detail.remark       IS '明细备注';
COMMENT ON COLUMN transaction_detail.created_at   IS '创建时间（审计字段）';
COMMENT ON COLUMN transaction_detail.updated_at   IS '更新时间（审计字段）';
COMMENT ON COLUMN transaction_detail.created_by   IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN transaction_detail.updated_by   IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 3. aml_alert : 反洗钱告警表
--    业务含义：AML 命中与可疑报告
--    数据分级：L3 (可疑报告 涉案敏感) + L2 (命中场景/风险等级 内部风控)，表级标 L3
--    分区策略：按 detected_at 日期动态分区
--    外键关系：transaction_id -> transaction.transaction_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS aml_alert (
    aml_id          VARCHAR(64)   NOT NULL                COMMENT 'AML告警ID（业务主键）',
    transaction_id  VARCHAR(64)   NOT NULL                COMMENT '关联交易ID（外键 -> transaction.transaction_id）',
    customer_id     VARCHAR(64)                            COMMENT '涉案客户ID（外键 -> customer.customer_id）',
    scenario        VARCHAR(64)   NOT NULL                 COMMENT '命中场景：DISPERSE_IN-分散转入 / CENTRAL_OUT-集中转出 / FAST_IN_OUT-快进快出 / STRUCTURE-结构化 / SMURF-化整为零（L2 内部风控）',
    risk_level      VARCHAR(8)    NOT NULL                 COMMENT 'AML风险等级：LOW-低 / MEDIUM-中 / HIGH-高（L2 内部风控）',
    hit_rules       STRING                                 COMMENT '命中规则列表（JSON 数组，L2 内部风控）',
    hit_score       DECIMAL(10,4)                          COMMENT '命中评分（L2 内部风控）',
    report_status   VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '上报状态：PENDING-未上报 / REPORTED-已上报 / REJECTED-已驳回 / CLOSED-已关闭',
    report_no       VARCHAR(64)                            COMMENT '可疑报告编号（上报后生成，L3 涉案敏感）',
    report_content  STRING                                 COMMENT '可疑报告内容（JSON，L3 涉案敏感）',
    handle_opinion  VARCHAR(512)                           COMMENT '处置意见',
    handled_by      VARCHAR(64)                            COMMENT '处置人（工号）',
    handled_at      DATETIME                               COMMENT '处置时间',
    detected_at     DATETIME      NOT NULL                 COMMENT 'AML检测时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (aml_id, detected_at)
COMMENT '反洗钱告警表 | 数据分级=L3 | AML命中与可疑报告 | 外键：transaction_id -> transaction.transaction_id；customer_id -> customer.customer_id'
PARTITION BY RANGE (detected_at) ()
DISTRIBUTED BY HASH (aml_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1825',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  aml_alert               IS '反洗钱告警表 | 数据分级=L3 | AML命中与可疑报告';
COMMENT ON COLUMN aml_alert.aml_id        IS 'AML告警ID（业务主键）';
COMMENT ON COLUMN aml_alert.transaction_id IS '关联交易ID（外键 -> transaction.transaction_id）';
COMMENT ON COLUMN aml_alert.customer_id   IS '涉案客户ID（外键 -> customer.customer_id）';
COMMENT ON COLUMN aml_alert.scenario      IS '命中场景：DISPERSE_IN/CENTRAL_OUT/FAST_IN_OUT/STRUCTURE/SMURF（L2 内部风控）';
COMMENT ON COLUMN aml_alert.risk_level    IS 'AML风险等级：LOW/MEDIUM/HIGH（L2 内部风控）';
COMMENT ON COLUMN aml_alert.hit_rules     IS '命中规则列表（JSON 数组，L2 内部风控）';
COMMENT ON COLUMN aml_alert.hit_score     IS '命中评分（L2 内部风控）';
COMMENT ON COLUMN aml_alert.report_status IS '上报状态：PENDING/REPORTED/REJECTED/CLOSED';
COMMENT ON COLUMN aml_alert.report_no     IS '可疑报告编号（L3 涉案敏感）';
COMMENT ON COLUMN aml_alert.report_content IS '可疑报告内容（JSON，L3 涉案敏感）';
COMMENT ON COLUMN aml_alert.handle_opinion IS '处置意见';
COMMENT ON COLUMN aml_alert.handled_by    IS '处置人（工号）';
COMMENT ON COLUMN aml_alert.handled_at    IS '处置时间';
COMMENT ON COLUMN aml_alert.detected_at   IS 'AML检测时间';
COMMENT ON COLUMN aml_alert.created_at    IS '创建时间（审计字段）';
COMMENT ON COLUMN aml_alert.updated_at    IS '更新时间（审计字段）';
COMMENT ON COLUMN aml_alert.created_by    IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN aml_alert.updated_by    IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 4. transaction_monitor : 交易监控表
--    业务含义：监控规则命中与告警（每笔交易均经监控规则校验）
--    数据分级：L2 (内部风控：监控拦截结果)
--    分区策略：按 checked_at 日期动态分区
--    外键关系：transaction_id -> transaction.transaction_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction_monitor (
    monitor_id      VARCHAR(64)   NOT NULL                COMMENT '监控记录ID（业务主键）',
    transaction_id  VARCHAR(64)   NOT NULL                COMMENT '关联交易ID（外键 -> transaction.transaction_id）',
    rule_code       VARCHAR(64)   NOT NULL                 COMMENT '监控规则编码（L2 内部风控）',
    rule_name       VARCHAR(128)                           COMMENT '监控规则名称',
    result          VARCHAR(16)   NOT NULL                 COMMENT '监控结果：NORMAL-正常 / WARN-预警 / BLOCK-拦截 / REVIEW-人工复核（L2 内部风控）',
    risk_score      DECIMAL(10,4)                          COMMENT '风险评分（L2 内部风控）',
    hit_detail      STRING                                 COMMENT '命中详情（JSON，含命中要素/阈值，L2 内部风控）',
    action_taken    VARCHAR(32)                            COMMENT '已执行动作：PASS-放行 / HOLD-挂起 / REJECT-拒绝 / NOTIFY-通知',
    checked_at      DATETIME      NOT NULL                 COMMENT '监控校验时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (monitor_id, checked_at)
COMMENT '交易监控表 | 数据分级=L2 | 监控规则命中与告警（每笔交易均经监控规则校验） | 外键：transaction_id -> transaction.transaction_id'
PARTITION BY RANGE (checked_at) ()
DISTRIBUTED BY HASH (transaction_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  transaction_monitor             IS '交易监控表 | 数据分级=L2 | 监控规则命中与告警';
COMMENT ON COLUMN transaction_monitor.monitor_id  IS '监控记录ID（业务主键）';
COMMENT ON COLUMN transaction_monitor.transaction_id IS '关联交易ID（外键 -> transaction.transaction_id）';
COMMENT ON COLUMN transaction_monitor.rule_code   IS '监控规则编码（L2 内部风控）';
COMMENT ON COLUMN transaction_monitor.rule_name   IS '监控规则名称';
COMMENT ON COLUMN transaction_monitor.result      IS '监控结果：NORMAL/WARN/BLOCK/REVIEW（L2 内部风控）';
COMMENT ON COLUMN transaction_monitor.risk_score  IS '风险评分（L2 内部风控）';
COMMENT ON COLUMN transaction_monitor.hit_detail  IS '命中详情（JSON，L2 内部风控）';
COMMENT ON COLUMN transaction_monitor.action_taken IS '已执行动作：PASS/HOLD/REJECT/NOTIFY';
COMMENT ON COLUMN transaction_monitor.checked_at  IS '监控校验时间';
COMMENT ON COLUMN transaction_monitor.created_at  IS '创建时间（审计字段）';
COMMENT ON COLUMN transaction_monitor.updated_at  IS '更新时间（审计字段）';
COMMENT ON COLUMN transaction_monitor.created_by  IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN transaction_monitor.updated_by  IS '更新人（审计字段，工号/系统）';

-- =============================================================================
-- 交易域 DDL 结束 | 共 4 张表：transaction / transaction_detail / aml_alert / transaction_monitor
-- =============================================================================