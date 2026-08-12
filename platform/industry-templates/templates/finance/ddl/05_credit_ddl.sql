-- =============================================================================
-- File   : 05_credit_ddl.sql
-- Domain : 信贷域 (Credit / Loan)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Source : platform/industry-templates/templates/finance/docs/business-model.md §1.5 §2.5
-- Class  : platform/industry-templates/templates/finance/docs/data-classification.md §2.4
-- Tables : loan_application / loan_contract / repayment_plan / credit_score (4 张)
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. loan_application : 贷款申请表
--    业务含义：进件、审批、放款全流程
--    数据分级：L3 (申请金额 财务敏感) + L2 (申请号 内部标识)，表级标 L3
--    分区策略：按 applied_at 日期动态分区
--    外键关系：customer_id -> customer.customer_id（弱关联）
--             credit_score_id -> credit_score.score_id（弱关联，申请依据评分）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS loan_application (
    application_id      VARCHAR(64)   NOT NULL            COMMENT '贷款申请ID（业务主键，雪花ID）',
    application_no      VARCHAR(64)   NOT NULL            COMMENT '贷款申请号（业务编号，L2 内部标识）',
    customer_id         VARCHAR(64)   NOT NULL            COMMENT '申请客户ID（外键 -> customer.customer_id）',
    product_code        VARCHAR(64)   NOT NULL            COMMENT '贷款产品编码',
    product_name        VARCHAR(128)                       COMMENT '贷款产品名称',
    apply_amount        DECIMAL(18,2) NOT NULL            COMMENT '申请金额（L3 财务敏感）',
    approved_amount     DECIMAL(18,2)                      COMMENT '审批通过金额（L3 财务敏感）',
    apply_term          INT           NOT NULL            COMMENT '申请期数（月）',
    apply_rate          DECIMAL(10,6)                      COMMENT '申请利率（L2 内部业务）',
    purpose             VARCHAR(128)                       COMMENT '贷款用途',
    purpose_category    VARCHAR(32)                        COMMENT '贷款用途分类：CONSUME-消费 / OPERATE-经营 / HOUSE-购房 / CAR-购车 / EDUCATION-教育',
    guarantee_type      VARCHAR(32)                        COMMENT '担保方式：CREDIT-信用 / GUARANTEE-担保 / MORTGAGE-抵押 / PLEDGE-质押',
    credit_score_id     VARCHAR(64)                        COMMENT '依据的信贷评分ID（外键 -> credit_score.score_id）',
    status              VARCHAR(16)   NOT NULL DEFAULT 'APPLIED' COMMENT '申请状态：APPLIED-申请 / REVIEWING-审批中 / APPROVED-审批通过 / REJECTED-拒绝 / LOANED-已放款 / CANCELED-取消',
    apply_channel       VARCHAR(32)                        COMMENT '申请渠道：COUNTER-柜面 / ONLINE-网银 / MOBILE-移动 / PARTNER-合作方',
    applied_at          DATETIME      NOT NULL            COMMENT '申请时间',
    approved_at         DATETIME                           COMMENT '审批完成时间',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间',
    updated_at          DATETIME      NOT NULL            COMMENT '更新时间',
    created_by          VARCHAR(64)   NOT NULL            COMMENT '创建人（工号/系统）',
    updated_by          VARCHAR(64)   NOT NULL            COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (application_id, applied_at)
COMMENT '贷款申请表 | 数据分级=L3 | 进件/审批/放款全流程 | 外键：customer_id -> customer.customer_id；credit_score_id -> credit_score.score_id'
PARTITION BY RANGE (applied_at) ()
DISTRIBUTED BY HASH (application_id) BUCKETS 16
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
--   CREATE TABLE loan_application (..., applied_at TIMESTAMP(6)) USING iceberg
--   PARTITIONED BY (days(applied_at))
--   TBLPROPERTIES ('format-version'='2');
COMMENT ON TABLE  loan_application                  IS '贷款申请表 | 数据分级=L3 | 进件/审批/放款全流程';
COMMENT ON COLUMN loan_application.application_id   IS '贷款申请ID（业务主键）';
COMMENT ON COLUMN loan_application.application_no   IS '贷款申请号（业务编号，L2 内部标识）';
COMMENT ON COLUMN loan_application.customer_id      IS '申请客户ID（外键 -> customer.customer_id）';
COMMENT ON COLUMN loan_application.product_code     IS '贷款产品编码';
COMMENT ON COLUMN loan_application.product_name     IS '贷款产品名称';
COMMENT ON COLUMN loan_application.apply_amount     IS '申请金额（L3 财务敏感）';
COMMENT ON COLUMN loan_application.approved_amount  IS '审批通过金额（L3 财务敏感）';
COMMENT ON COLUMN loan_application.apply_term       IS '申请期数（月）';
COMMENT ON COLUMN loan_application.apply_rate       IS '申请利率（L2 内部业务）';
COMMENT ON COLUMN loan_application.purpose          IS '贷款用途';
COMMENT ON COLUMN loan_application.purpose_category IS '贷款用途分类：CONSUME/OPERATE/HOUSE/CAR/EDUCATION';
COMMENT ON COLUMN loan_application.guarantee_type   IS '担保方式：CREDIT/GUARANTEE/MORTGAGE/PLEDGE';
COMMENT ON COLUMN loan_application.credit_score_id  IS '依据的信贷评分ID（外键 -> credit_score.score_id）';
COMMENT ON COLUMN loan_application.status           IS '申请状态：APPLIED/REVIEWING/APPROVED/REJECTED/LOANED/CANCELED';
COMMENT ON COLUMN loan_application.apply_channel    IS '申请渠道：COUNTER/ONLINE/MOBILE/PARTNER';
COMMENT ON COLUMN loan_application.applied_at       IS '申请时间';
COMMENT ON COLUMN loan_application.approved_at      IS '审批完成时间';
COMMENT ON COLUMN loan_application.created_at       IS '创建时间（审计字段）';
COMMENT ON COLUMN loan_application.updated_at       IS '更新时间（审计字段）';
COMMENT ON COLUMN loan_application.created_by       IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN loan_application.updated_by       IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 2. loan_contract : 贷款合同表
--    业务含义：合同主表与条款
--    数据分级：L3 (合同本金 财务敏感) + L2 (合同编号 内部标识)，表级标 L3
--    分区策略：按 signed_at 日期动态分区
--    外键关系：application_id -> loan_application.application_id（弱关联）
--             repayment_account_id -> account.account_id（弱关联，还款账户）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS loan_contract (
    contract_id         VARCHAR(64)   NOT NULL            COMMENT '贷款合同ID（业务主键，雪花ID）',
    contract_no         VARCHAR(64)   NOT NULL            COMMENT '贷款合同编号（业务编号，L2 内部标识）',
    application_id      VARCHAR(64)   NOT NULL            COMMENT '关联贷款申请ID（外键 -> loan_application.application_id）',
    customer_id         VARCHAR(64)   NOT NULL            COMMENT '借款客户ID（外键 -> customer.customer_id）',
    product_code        VARCHAR(64)                       COMMENT '贷款产品编码',
    principal           DECIMAL(18,2) NOT NULL            COMMENT '合同本金（L3 财务敏感）',
    rate                DECIMAL(10,6) NOT NULL            COMMENT '合同利率（年化，L2 内部业务）',
    rate_type           VARCHAR(16)   NOT NULL            COMMENT '利率类型：FIXED-固定 / FLOAT-浮动',
    repay_method        VARCHAR(16)   NOT NULL            COMMENT '还款方式：EQUAL_INSTALLMENT-等额本息 / EQUAL_PRINCIPAL-等额本金 / ONE_TIME-到期还本 / INTEREST_FIRST-先息后本',
    term_count          INT           NOT NULL            COMMENT '期数（月）',
    repayment_account_id VARCHAR(64)                      COMMENT '还款账户ID（外键 -> account.account_id）',
    status              VARCHAR(16)   NOT NULL DEFAULT 'EFFECTIVE' COMMENT '合同状态：DRAFT-草稿 / EFFECTIVE-生效 / OVERDUE-逾期 / SETTLED-结清 / TERMINATED-终止',
    signed_at           DATETIME      NOT NULL            COMMENT '合同签订时间',
    maturity_at         DATETIME      NOT NULL            COMMENT '合同到期时间',
    settled_at          DATETIME                          COMMENT '结清时间',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间',
    updated_at          DATETIME      NOT NULL            COMMENT '更新时间',
    created_by          VARCHAR(64)   NOT NULL            COMMENT '创建人（工号/系统）',
    updated_by          VARCHAR(64)   NOT NULL            COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (contract_id, signed_at)
COMMENT '贷款合同表 | 数据分级=L3 | 合同主表与条款 | 外键：application_id -> loan_application；repayment_account_id -> account'
PARTITION BY RANGE (signed_at) ()
DISTRIBUTED BY HASH (contract_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  loan_contract                  IS '贷款合同表 | 数据分级=L3 | 合同主表与条款';
COMMENT ON COLUMN loan_contract.contract_id      IS '贷款合同ID（业务主键）';
COMMENT ON COLUMN loan_contract.contract_no      IS '贷款合同编号（业务编号，L2 内部标识）';
COMMENT ON COLUMN loan_contract.application_id   IS '关联贷款申请ID（外键 -> loan_application.application_id）';
COMMENT ON COLUMN loan_contract.customer_id      IS '借款客户ID（外键 -> customer.customer_id）';
COMMENT ON COLUMN loan_contract.product_code     IS '贷款产品编码';
COMMENT ON COLUMN loan_contract.principal        IS '合同本金（L3 财务敏感）';
COMMENT ON COLUMN loan_contract.rate             IS '合同利率（年化，L2 内部业务）';
COMMENT ON COLUMN loan_contract.rate_type        IS '利率类型：FIXED/FLOAT';
COMMENT ON COLUMN loan_contract.repay_method     IS '还款方式：EQUAL_INSTALLMENT/EQUAL_PRINCIPAL/ONE_TIME/INTEREST_FIRST';
COMMENT ON COLUMN loan_contract.term_count       IS '期数（月）';
COMMENT ON COLUMN loan_contract.repayment_account_id IS '还款账户ID（外键 -> account.account_id）';
COMMENT ON COLUMN loan_contract.status           IS '合同状态：DRAFT/EFFECTIVE/OVERDUE/SETTLED/TERMINATED';
COMMENT ON COLUMN loan_contract.signed_at        IS '合同签订时间';
COMMENT ON COLUMN loan_contract.maturity_at      IS '合同到期时间';
COMMENT ON COLUMN loan_contract.settled_at       IS '结清时间';
COMMENT ON COLUMN loan_contract.created_at       IS '创建时间（审计字段）';
COMMENT ON COLUMN loan_contract.updated_at       IS '更新时间（审计字段）';
COMMENT ON COLUMN loan_contract.created_by       IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN loan_contract.updated_by       IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 3. repayment_plan : 还款计划表
--    业务含义：期次、应还、实还
--    数据分级：L3 (应还本金/利息 财务敏感) + L2 (还款状态 内部业务)，表级标 L3
--    分区策略：按 due_date 日期动态分区（按到期日分区，便于逾期查询）
--    外键关系：contract_id -> loan_contract.contract_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS repayment_plan (
    plan_id             VARCHAR(64)   NOT NULL            COMMENT '还款计划ID（业务主键）',
    contract_id         VARCHAR(64)   NOT NULL            COMMENT '贷款合同ID（外键 -> loan_contract.contract_id）',
    term_no             INT           NOT NULL            COMMENT '期次号（从1开始）',
    due_date            DATE          NOT NULL            COMMENT '应还日期',
    due_principal       DECIMAL(18,2) NOT NULL            COMMENT '应还本金（L3 财务敏感）',
    due_interest        DECIMAL(18,2) NOT NULL            COMMENT '应还利息（L3 财务敏感）',
    due_amount          DECIMAL(18,2) NOT NULL            COMMENT '应还总额 = 应还本金 + 应还利息（L3 财务敏感）',
    paid_principal      DECIMAL(18,2) NOT NULL DEFAULT 0  COMMENT '已还本金（L3 财务敏感）',
    paid_interest       DECIMAL(18,2) NOT NULL DEFAULT 0  COMMENT '已还利息（L3 财务敏感）',
    paid_amount         DECIMAL(18,2) NOT NULL DEFAULT 0  COMMENT '已还总额（L3 财务敏感）',
    overdue_days        INT           NOT NULL DEFAULT 0  COMMENT '逾期天数',
    repay_status        VARCHAR(16)   NOT NULL DEFAULT 'UNPAID' COMMENT '还款状态：UNPAID-未还 / PARTIAL-部分 / PAID-已还 / OVERDUE-逾期 / BAD-坏账（L2 内部业务状态）',
    paid_at             DATETIME                          COMMENT '实际还款时间',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间',
    updated_at          DATETIME      NOT NULL            COMMENT '更新时间',
    created_by          VARCHAR(64)   NOT NULL            COMMENT '创建人（工号/系统）',
    updated_by          VARCHAR(64)   NOT NULL            COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (plan_id, due_date)
COMMENT '还款计划表 | 数据分级=L3 | 期次/应还/实还 | 外键：contract_id -> loan_contract.contract_id | 分区：按 due_date 天级动态分区'
PARTITION BY RANGE (due_date) ()
DISTRIBUTED BY HASH (contract_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '365',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  repayment_plan              IS '还款计划表 | 数据分级=L3 | 期次/应还/实还';
COMMENT ON COLUMN repayment_plan.plan_id      IS '还款计划ID（业务主键）';
COMMENT ON COLUMN repayment_plan.contract_id  IS '贷款合同ID（外键 -> loan_contract.contract_id）';
COMMENT ON COLUMN repayment_plan.term_no      IS '期次号（从1开始）';
COMMENT ON COLUMN repayment_plan.due_date     IS '应还日期';
COMMENT ON COLUMN repayment_plan.due_principal IS '应还本金（L3 财务敏感）';
COMMENT ON COLUMN repayment_plan.due_interest  IS '应还利息（L3 财务敏感）';
COMMENT ON COLUMN repayment_plan.due_amount   IS '应还总额 = 应还本金 + 应还利息（L3 财务敏感）';
COMMENT ON COLUMN repayment_plan.paid_principal IS '已还本金（L3 财务敏感）';
COMMENT ON COLUMN repayment_plan.paid_interest IS '已还利息（L3 财务敏感）';
COMMENT ON COLUMN repayment_plan.paid_amount  IS '已还总额（L3 财务敏感）';
COMMENT ON COLUMN repayment_plan.overdue_days  IS '逾期天数';
COMMENT ON COLUMN repayment_plan.repay_status IS '还款状态：UNPAID/PARTIAL/PAID/OVERDUE/BAD（L2 内部业务状态）';
COMMENT ON COLUMN repayment_plan.paid_at      IS '实际还款时间';
COMMENT ON COLUMN repayment_plan.created_at   IS '创建时间（审计字段）';
COMMENT ON COLUMN repayment_plan.updated_at   IS '更新时间（审计字段）';
COMMENT ON COLUMN repayment_plan.created_by   IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN repayment_plan.updated_by   IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 4. credit_score : 信用评分表
--    业务含义：内部评分与外部征信
--    数据分级：L3 (外部征信报告 个人金融隐私) + L2 (评分分值/来源 内部业务)，表级标 L3
--    分区策略：按 computed_at 日期动态分区
--    外键关系：customer_id -> customer.customer_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS credit_score (
    score_id        VARCHAR(64)   NOT NULL                COMMENT '信用评分ID（业务主键）',
    customer_id     VARCHAR(64)   NOT NULL                COMMENT '客户ID（外键 -> customer.customer_id）',
    score_source    VARCHAR(16)   NOT NULL                 COMMENT '评分来源：INTERNAL-内部 / PBOC-人行征信 / THIRD-第三方（L2 内部业务）',
    score           INT           NOT NULL                 COMMENT '信用评分分值（0~1000，L2 内部业务）',
    grade           VARCHAR(8)                             COMMENT '信用等级：AAA/AA/A/BBB/BB/B/C/D（L2 内部业务）',
    score_model     VARCHAR(64)                            COMMENT '评分模型编码（内部评分时填写）',
    report_no       VARCHAR(64)                            COMMENT '征信报告编号（外部征信时填写，L3 个人金融隐私）',
    report_summary  STRING                                 COMMENT '征信报告摘要（JSON，含信贷记录/查询记录/公共记录，L3 个人金融隐私）',
    risk_level      VARCHAR(8)                             COMMENT '风险等级：LOW-低 / MEDIUM-中 / HIGH-高（L2 内部业务）',
    valid_from      DATETIME                               COMMENT '评分生效开始时间',
    valid_to        DATETIME                               COMMENT '评分生效结束时间',
    computed_at     DATETIME      NOT NULL                 COMMENT '评分计算时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (score_id, computed_at)
COMMENT '信用评分表 | 数据分级=L3 | 内部评分与外部征信 | 外键：customer_id -> customer.customer_id'
PARTITION BY RANGE (computed_at) ()
DISTRIBUTED BY HASH (customer_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  credit_score             IS '信用评分表 | 数据分级=L3 | 内部评分与外部征信';
COMMENT ON COLUMN credit_score.score_id    IS '信用评分ID（业务主键）';
COMMENT ON COLUMN credit_score.customer_id IS '客户ID（外键 -> customer.customer_id）';
COMMENT ON COLUMN credit_score.score_source IS '评分来源：INTERNAL/PBOC/THIRD（L2 内部业务）';
COMMENT ON COLUMN credit_score.score       IS '信用评分分值（0~1000，L2 内部业务）';
COMMENT ON COLUMN credit_score.grade       IS '信用等级：AAA/AA/A/BBB/BB/B/C/D（L2 内部业务）';
COMMENT ON COLUMN credit_score.score_model IS '评分模型编码（内部评分时填写）';
COMMENT ON COLUMN credit_score.report_no   IS '征信报告编号（外部征信时填写，L3 个人金融隐私）';
COMMENT ON COLUMN credit_score.report_summary IS '征信报告摘要（JSON，L3 个人金融隐私）';
COMMENT ON COLUMN credit_score.risk_level  IS '风险等级：LOW/MEDIUM/HIGH（L2 内部业务）';
COMMENT ON COLUMN credit_score.valid_from  IS '评分生效开始时间';
COMMENT ON COLUMN credit_score.valid_to    IS '评分生效结束时间';
COMMENT ON COLUMN credit_score.computed_at IS '评分计算时间';
COMMENT ON COLUMN credit_score.created_at  IS '创建时间（审计字段）';
COMMENT ON COLUMN credit_score.updated_at  IS '更新时间（审计字段）';
COMMENT ON COLUMN credit_score.created_by  IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN credit_score.updated_by  IS '更新人（审计字段，工号/系统）';

-- =============================================================================
-- 信贷域 DDL 结束 | 共 4 张表：loan_application / loan_contract / repayment_plan / credit_score
-- =============================================================================