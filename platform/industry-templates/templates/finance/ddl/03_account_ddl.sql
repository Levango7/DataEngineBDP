-- =============================================================================
-- File   : 03_account_ddl.sql
-- Domain : 账户域 (Account)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Source : platform/industry-templates/templates/finance/docs/business-model.md §1.3 §2.3
-- Class  : platform/industry-templates/templates/finance/docs/data-classification.md §2.2
-- Tables : account / account_balance / account_transaction / account_status_log (4 张)
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. account : 账户信息主表
--    业务含义：账户主表，记录账户生命周期
--    数据分级：L4 (账户号 资金账户核心标识)
--    分区策略：按 opened_at 日期动态分区
--    外键关系：customer_id -> customer.customer_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account (
    account_id      VARCHAR(64)   NOT NULL                COMMENT '账户ID（业务主键，雪花ID）',
    account_no      VARCHAR(64)   NOT NULL                COMMENT '账户号（业务编号，L4 高敏感，加密存储+强脱敏）',
    customer_id     VARCHAR(64)   NOT NULL                COMMENT '客户ID（外键 -> customer.customer_id）',
    account_type    VARCHAR(16)   NOT NULL                COMMENT '账户类型：DEBIT-借记 / CREDIT-贷记 / CORP-对公 / SAVING-储蓄 / LOAN-贷款（L2 内部业务标识）',
    currency        VARCHAR(8)    NOT NULL DEFAULT 'CNY'  COMMENT '账户币种（L1 公开信息，ISO 4217）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT '账户状态：NORMAL-正常 / FROZEN-冻结 / RESTRICTED-限制 / CLOSED-销户（L2 内部业务状态）',
    balance         DECIMAL(18,2) NOT NULL DEFAULT 0.00   COMMENT '账户当前余额（L3 财务敏感）',
    available_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '可用余额（L3 财务敏感）',
    frozen_amount   DECIMAL(18,2) NOT NULL DEFAULT 0.00   COMMENT '冻结金额（L3 财务敏感）',
    overdraft_limit DECIMAL(18,2)          DEFAULT 0.00   COMMENT '透支额度（L3 财务敏感）',
    interest_rate   DECIMAL(10,6)                          COMMENT '账户利率（L2 内部业务）',
    branch_code     VARCHAR(32)                            COMMENT '开户网点编码',
    opened_at       DATETIME      NOT NULL                 COMMENT '开户时间',
    closed_at       DATETIME                               COMMENT '销户时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (account_id, opened_at)
COMMENT '账户信息主表 | 数据分级=L4（账户号为资金账户核心标识） | 账户生命周期主表 | 外键：customer_id -> customer.customer_id'
PARTITION BY RANGE (opened_at) ()
DISTRIBUTED BY HASH (account_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
-- Iceberg 兼容写法：
--   CREATE TABLE account (..., opened_at TIMESTAMP(6)) USING iceberg
--   PARTITIONED BY (days(opened_at))
--   TBLPROPERTIES ('format-version'='2');
COMMENT ON TABLE  account                  IS '账户信息主表 | 数据分级=L4（账户号为资金账户核心标识） | 账户生命周期主表';
COMMENT ON COLUMN account.account_id       IS '账户ID（业务主键）';
COMMENT ON COLUMN account.account_no       IS '账户号（业务编号，L4 高敏感，加密存储+强脱敏）';
COMMENT ON COLUMN account.customer_id      IS '客户ID（外键 -> customer.customer_id）';
COMMENT ON COLUMN account.account_type     IS '账户类型：DEBIT/CREDIT/CORP/SAVING/LOAN（L2 内部业务标识）';
COMMENT ON COLUMN account.currency         IS '账户币种（L1 公开信息，ISO 4217）';
COMMENT ON COLUMN account.status           IS '账户状态：NORMAL/FROZEN/RESTRICTED/CLOSED（L2 内部业务状态）';
COMMENT ON COLUMN account.balance          IS '账户当前余额（L3 财务敏感）';
COMMENT ON COLUMN account.available_balance IS '可用余额（L3 财务敏感）';
COMMENT ON COLUMN account.frozen_amount    IS '冻结金额（L3 财务敏感）';
COMMENT ON COLUMN account.overdraft_limit  IS '透支额度（L3 财务敏感）';
COMMENT ON COLUMN account.interest_rate    IS '账户利率（L2 内部业务）';
COMMENT ON COLUMN account.branch_code      IS '开户网点编码';
COMMENT ON COLUMN account.opened_at        IS '开户时间';
COMMENT ON COLUMN account.closed_at        IS '销户时间';
COMMENT ON COLUMN account.created_at       IS '创建时间（审计字段）';
COMMENT ON COLUMN account.updated_at       IS '更新时间（审计字段）';
COMMENT ON COLUMN account.created_by       IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN account.updated_by       IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 2. account_balance : 账户余额快照表
--    业务含义：日终/实时余额快照
--    数据分级：L3 (账户余额 财务敏感)
--    分区策略：按 snapshot_date 日期动态分区（按天快照，便于对账与冷热分离）
--    外键关系：account_id -> account.account_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_balance (
    balance_id      VARCHAR(64)   NOT NULL                COMMENT '余额快照ID（业务主键）',
    account_id      VARCHAR(64)   NOT NULL                COMMENT '账户ID（外键 -> account.account_id）',
    balance_type    VARCHAR(16)   NOT NULL                COMMENT '余额类型：AVAILABLE-可用 / FROZEN-冻结 / TOTAL-总余额 / INTEREST-计息',
    amount          DECIMAL(18,2) NOT NULL                 COMMENT '余额金额（L3 财务敏感）',
    currency        VARCHAR(8)    NOT NULL DEFAULT 'CNY'  COMMENT '币种（L1 公开信息）',
    snapshot_date   DATE          NOT NULL                 COMMENT '快照日期（日终对账日）',
    snapshot_time   DATETIME      NOT NULL                 COMMENT '快照时间（精确到秒）',
    snapshot_source VARCHAR(16)   NOT NULL                COMMENT '快照来源：EOD-日终 / REALTIME-实时 / ADHOC-临时',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (balance_id, snapshot_date)
COMMENT '账户余额快照表 | 数据分级=L3 | 日终/实时余额快照 | 外键：account_id -> account.account_id | 分区：按 snapshot_date 天级动态分区'
PARTITION BY RANGE (snapshot_date) ()
DISTRIBUTED BY HASH (account_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  account_balance              IS '账户余额快照表 | 数据分级=L3 | 日终/实时余额快照';
COMMENT ON COLUMN account_balance.balance_id   IS '余额快照ID（业务主键）';
COMMENT ON COLUMN account_balance.account_id   IS '账户ID（外键 -> account.account_id）';
COMMENT ON COLUMN account_balance.balance_type IS '余额类型：AVAILABLE/FROZEN/TOTAL/INTEREST';
COMMENT ON COLUMN account_balance.amount       IS '余额金额（L3 财务敏感）';
COMMENT ON COLUMN account_balance.currency     IS '币种（L1 公开信息）';
COMMENT ON COLUMN account_balance.snapshot_date IS '快照日期（日终对账日）';
COMMENT ON COLUMN account_balance.snapshot_time IS '快照时间（精确到秒）';
COMMENT ON COLUMN account_balance.snapshot_source IS '快照来源：EOD/REALTIME/ADHOC';
COMMENT ON COLUMN account_balance.created_at   IS '创建时间（审计字段）';
COMMENT ON COLUMN account_balance.updated_at   IS '更新时间（审计字段）';
COMMENT ON COLUMN account_balance.created_by   IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN account_balance.updated_by   IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 3. account_transaction : 账户流水表
--    业务含义：入账明细
--    数据分级：L3 (流水金额 财务敏感) + L4 (对方账户号 资金账户核心标识)，表级标 L4
--    分区策略：按 posted_at 日期动态分区（流水量大，按天分区）
--    外键关系：account_id -> account.account_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_transaction (
    txn_id          VARCHAR(64)   NOT NULL                COMMENT '流水ID（业务主键）',
    account_id      VARCHAR(64)   NOT NULL                COMMENT '账户ID（外键 -> account.account_id）',
    txn_no          VARCHAR(64)   NOT NULL                COMMENT '流水号（业务编号）',
    amount          DECIMAL(18,2) NOT NULL                 COMMENT '交易金额（L3 财务敏感）',
    currency        VARCHAR(8)    NOT NULL DEFAULT 'CNY'  COMMENT '币种（L1 公开信息）',
    direction       VARCHAR(4)    NOT NULL                 COMMENT '记账方向：DEBIT-借 / CREDIT-贷',
    balance_after   DECIMAL(18,2)                          COMMENT '记账后余额（L3 财务敏感）',
    counter_account VARCHAR(64)                            COMMENT '对方账户号（L4 高敏感，加密存储+强脱敏）',
    counter_name    VARCHAR(128)                           COMMENT '对方账户名称（L3 个人/商业隐私）',
    counter_bank    VARCHAR(64)                            COMMENT '对方开户行编码',
    txn_type        VARCHAR(32)                            COMMENT '交易类型：TRANSFER-转账 / DEPOSIT-存款 / WITHDRAW-取款 / SETTLE-结算',
    summary         VARCHAR(256)                           COMMENT '交易摘要/备注',
    ref_txn_id      VARCHAR(64)                            COMMENT '关联原流水ID（冲正/退款场景）',
    posted_at       DATETIME      NOT NULL                 COMMENT '入账时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (txn_id, posted_at)
COMMENT '账户流水表 | 数据分级=L4（按最高敏感字段 counter_account 定级，amount 为 L3） | 入账明细 | 外键：account_id -> account.account_id'
PARTITION BY RANGE (posted_at) ()
DISTRIBUTED BY HASH (account_id) BUCKETS 32
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  account_transaction               IS '账户流水表 | 数据分级=L4（按最高敏感字段 counter_account 定级） | 入账明细';
COMMENT ON COLUMN account_transaction.txn_id        IS '流水ID（业务主键）';
COMMENT ON COLUMN account_transaction.account_id    IS '账户ID（外键 -> account.account_id）';
COMMENT ON COLUMN account_transaction.txn_no        IS '流水号（业务编号）';
COMMENT ON COLUMN account_transaction.amount        IS '交易金额（L3 财务敏感）';
COMMENT ON COLUMN account_transaction.currency      IS '币种（L1 公开信息）';
COMMENT ON COLUMN account_transaction.direction     IS '记账方向：DEBIT/CREDIT';
COMMENT ON COLUMN account_transaction.balance_after IS '记账后余额（L3 财务敏感）';
COMMENT ON COLUMN account_transaction.counter_account IS '对方账户号（L4 高敏感，加密存储+强脱敏）';
COMMENT ON COLUMN account_transaction.counter_name  IS '对方账户名称（L3 个人/商业隐私）';
COMMENT ON COLUMN account_transaction.counter_bank  IS '对方开户行编码';
COMMENT ON COLUMN account_transaction.txn_type      IS '交易类型：TRANSFER/DEPOSIT/WITHDRAW/SETTLE';
COMMENT ON COLUMN account_transaction.summary       IS '交易摘要/备注';
COMMENT ON COLUMN account_transaction.ref_txn_id    IS '关联原流水ID（冲正/退款场景）';
COMMENT ON COLUMN account_transaction.posted_at     IS '入账时间';
COMMENT ON COLUMN account_transaction.created_at    IS '创建时间（审计字段）';
COMMENT ON COLUMN account_transaction.updated_at    IS '更新时间（审计字段）';
COMMENT ON COLUMN account_transaction.created_by    IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN account_transaction.updated_by    IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 4. account_status_log : 账户状态变更日志表
--    业务含义：账户状态变更全程留痕
--    数据分级：L2 (内部业务状态)
--    分区策略：按 changed_at 日期动态分区
--    外键关系：account_id -> account.account_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_status_log (
    log_id          VARCHAR(64)   NOT NULL                COMMENT '状态变更日志ID（业务主键）',
    account_id      VARCHAR(64)   NOT NULL                COMMENT '账户ID（外键 -> account.account_id）',
    from_status     VARCHAR(16)   NOT NULL                 COMMENT '变更前状态：NORMAL/FROZEN/RESTRICTED/CLOSED（L2 内部业务状态）',
    to_status       VARCHAR(16)   NOT NULL                 COMMENT '变更后状态：NORMAL/FROZEN/RESTRICTED/CLOSED（L2 内部业务状态）',
    change_type     VARCHAR(32)                            COMMENT '变更类型：OPEN-开户 / FREEZE-冻结 / UNFREEZE-解冻 / RESTRICT-限制 / CLOSE-销户',
    reason          VARCHAR(256)                           COMMENT '变更原因',
    operator        VARCHAR(64)                            COMMENT '操作人（工号/系统）',
    channel         VARCHAR(32)                            COMMENT '操作渠道：COUNTER-柜面 / ONLINE-网银 / MOBILE-移动 / SYSTEM-系统',
    changed_at      DATETIME      NOT NULL                 COMMENT '状态变更时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (log_id, changed_at)
COMMENT '账户状态变更日志表 | 数据分级=L2 | 账户状态变更全程留痕 | 外键：account_id -> account.account_id'
PARTITION BY RANGE (changed_at) ()
DISTRIBUTED BY HASH (account_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  account_status_log             IS '账户状态变更日志表 | 数据分级=L2 | 账户状态变更全程留痕';
COMMENT ON COLUMN account_status_log.log_id      IS '状态变更日志ID（业务主键）';
COMMENT ON COLUMN account_status_log.account_id  IS '账户ID（外键 -> account.account_id）';
COMMENT ON COLUMN account_status_log.from_status IS '变更前状态（L2 内部业务状态）';
COMMENT ON COLUMN account_status_log.to_status   IS '变更后状态（L2 内部业务状态）';
COMMENT ON COLUMN account_status_log.change_type IS '变更类型：OPEN/FREEZE/UNFREEZE/RESTRICT/CLOSE';
COMMENT ON COLUMN account_status_log.reason      IS '变更原因';
COMMENT ON COLUMN account_status_log.operator    IS '操作人（工号/系统）';
COMMENT ON COLUMN account_status_log.channel     IS '操作渠道：COUNTER/ONLINE/MOBILE/SYSTEM';
COMMENT ON COLUMN account_status_log.changed_at  IS '状态变更时间';
COMMENT ON COLUMN account_status_log.created_at  IS '创建时间（审计字段）';
COMMENT ON COLUMN account_status_log.updated_at  IS '更新时间（审计字段）';
COMMENT ON COLUMN account_status_log.created_by  IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN account_status_log.updated_by  IS '更新人（审计字段，工号/系统）';

-- =============================================================================
-- 账户域 DDL 结束 | 共 4 张表：account / account_balance / account_transaction / account_status_log
-- =============================================================================