-- =============================================================================
-- File   : 02_customer_ddl.sql
-- Domain : 客户域 (Customer)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Source : platform/industry-templates/templates/finance/docs/business-model.md §1.2 §2.2
-- Class  : platform/industry-templates/templates/finance/docs/data-classification.md §2.1
-- Tables : customer / customer_tag / customer_profile / customer_relation (4 张)
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. customer : 客户基本信息主表
--    业务含义：自然人/对公客户法定属性，是风控/账户/交易/信贷域的主数据来源
--    数据分级：L3 (姓名/手机号/地址) + L4 (身份证号) 混合，表级标 L4（按最高敏感字段定级）
--    分区策略：按 created_at 日期动态分区
--    外键关系：无（被 account / transaction / loan_application / customer_relation 等引用）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer (
    customer_id     VARCHAR(64)   NOT NULL                COMMENT '客户ID（业务主键，雪花ID）',
    customer_type   VARCHAR(16)   NOT NULL                COMMENT '客户类型：PERSONAL-个人 / CORPORATE-对公（L2 内部业务标识）',
    customer_no     VARCHAR(64)   NOT NULL                COMMENT '客户业务编号（唯一，对外展示用）',
    name            VARCHAR(128)  NOT NULL                COMMENT '客户姓名/名称（L3 个人隐私，查询脱敏）',
    id_type         VARCHAR(16)                            COMMENT '证件类型：IDCARD-身份证 / PASSPORT-护照 / USCC-统一社会信用代码',
    id_card         VARCHAR(64)                            COMMENT '证件号码（L4 高敏感，加密存储+强脱敏）',
    phone           VARCHAR(32)                            COMMENT '手机号（L3 个人隐私，查询脱敏）',
    email           VARCHAR(128)                           COMMENT '电子邮箱（L3 个人隐私）',
    gender          VARCHAR(8)                             COMMENT '性别：M/F/U',
    birth_date      DATE                                   COMMENT '出生日期',
    nationality     VARCHAR(32)                            COMMENT '国籍/地区',
    address         VARCHAR(256)                           COMMENT '常住地址（L3 个人隐私）',
    occupation      VARCHAR(64)                            COMMENT '职业',
    industry_code   VARCHAR(32)                            COMMENT '行业编码',
    risk_level      VARCHAR(8)             DEFAULT 'C'     COMMENT '客户风险等级：A/B/C/D/E（L2 内部业务）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '客户状态：ACTIVE-活跃 / FROZEN-冻结 / CLOSED-销户 / BLACK-黑名单',
    blacklist_flag  BOOLEAN               DEFAULT FALSE   COMMENT '是否黑名单客户',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (customer_id, created_at)
COMMENT '客户基本信息主表 | 数据分级=L4（按最高敏感字段 id_card 定级，name/phone/address 为 L3） | 自然人/对公客户法定属性'
PARTITION BY RANGE (created_at) ()
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
-- Iceberg 兼容写法：
--   CREATE TABLE customer (..., created_at TIMESTAMP(6)) USING iceberg
--   PARTITIONED BY (days(created_at))
--   TBLPROPERTIES ('format-version'='2');
COMMENT ON TABLE  customer                IS '客户基本信息主表 | 数据分级=L4（按最高敏感字段 id_card 定级） | 自然人/对公客户法定属性';
COMMENT ON COLUMN customer.customer_id    IS '客户ID（业务主键）';
COMMENT ON COLUMN customer.customer_type  IS '客户类型：PERSONAL/CORPORATE（L2 内部业务标识）';
COMMENT ON COLUMN customer.customer_no    IS '客户业务编号（唯一，对外展示用）';
COMMENT ON COLUMN customer.name           IS '客户姓名/名称（L3 个人隐私，查询脱敏）';
COMMENT ON COLUMN customer.id_type        IS '证件类型：IDCARD/PASSPORT/USCC';
COMMENT ON COLUMN customer.id_card        IS '证件号码（L4 高敏感，加密存储+强脱敏）';
COMMENT ON COLUMN customer.phone          IS '手机号（L3 个人隐私，查询脱敏）';
COMMENT ON COLUMN customer.email          IS '电子邮箱（L3 个人隐私）';
COMMENT ON COLUMN customer.gender         IS '性别：M/F/U';
COMMENT ON COLUMN customer.birth_date     IS '出生日期';
COMMENT ON COLUMN customer.nationality    IS '国籍/地区';
COMMENT ON COLUMN customer.address        IS '常住地址（L3 个人隐私）';
COMMENT ON COLUMN customer.occupation     IS '职业';
COMMENT ON COLUMN customer.industry_code  IS '行业编码';
COMMENT ON COLUMN customer.risk_level     IS '客户风险等级：A/B/C/D/E（L2 内部业务）';
COMMENT ON COLUMN customer.status         IS '客户状态：ACTIVE/FROZEN/CLOSED/BLACK';
COMMENT ON COLUMN customer.blacklist_flag IS '是否黑名单客户';
COMMENT ON COLUMN customer.created_at     IS '创建时间（审计字段）';
COMMENT ON COLUMN customer.updated_at     IS '更新时间（审计字段）';
COMMENT ON COLUMN customer.created_by     IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN customer.updated_by     IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 2. customer_tag : 客户标签表
--    业务含义：业务标签、风险标签
--    数据分级：L2 (内部业务标签)
--    分区策略：按 tagged_at 日期动态分区
--    外键关系：customer_id -> customer.customer_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_tag (
    tag_id          VARCHAR(64)   NOT NULL                COMMENT '标签记录ID（业务主键）',
    customer_id     VARCHAR(64)   NOT NULL                COMMENT '客户ID（外键 -> customer.customer_id）',
    tag_code        VARCHAR(64)   NOT NULL                COMMENT '标签编码（如 VIP / HIGH_RISK / FRAUD_HISTORY）',
    tag_value       VARCHAR(128)                          COMMENT '标签值（L2 内部业务标签）',
    tag_category    VARCHAR(32)                            COMMENT '标签分类：BUSINESS-业务 / RISK-风险 / BEHAVIOR-行为 / MARKETING-营销',
    tag_source      VARCHAR(16)   NOT NULL                COMMENT '标签来源：MANUAL-人工 / RULE-规则 / MODEL-模型 / IMPORT-导入',
    confidence      DECIMAL(5,4)           DEFAULT 1.0000 COMMENT '标签置信度（0~1，模型打标时输出）',
    expire_at       DATETIME                               COMMENT '标签过期时间',
    tagged_at       DATETIME      NOT NULL                 COMMENT '打标时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (tag_id, tagged_at)
COMMENT '客户标签表 | 数据分级=L2 | 业务标签/风险标签 | 外键：customer_id -> customer.customer_id'
PARTITION BY RANGE (tagged_at) ()
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
COMMENT ON TABLE  customer_tag             IS '客户标签表 | 数据分级=L2 | 业务标签/风险标签 | 外键：customer_id -> customer.customer_id';
COMMENT ON COLUMN customer_tag.tag_id      IS '标签记录ID（业务主键）';
COMMENT ON COLUMN customer_tag.customer_id IS '客户ID（外键 -> customer.customer_id）';
COMMENT ON COLUMN customer_tag.tag_code    IS '标签编码（如 VIP/HIGH_RISK/FRAUD_HISTORY）';
COMMENT ON COLUMN customer_tag.tag_value   IS '标签值（L2 内部业务标签）';
COMMENT ON COLUMN customer_tag.tag_category IS '标签分类：BUSINESS/RISK/BEHAVIOR/MARKETING';
COMMENT ON COLUMN customer_tag.tag_source  IS '标签来源：MANUAL/RULE/MODEL/IMPORT';
COMMENT ON COLUMN customer_tag.confidence  IS '标签置信度（0~1）';
COMMENT ON COLUMN customer_tag.expire_at   IS '标签过期时间';
COMMENT ON COLUMN customer_tag.tagged_at   IS '打标时间';
COMMENT ON COLUMN customer_tag.created_at  IS '创建时间（审计字段）';
COMMENT ON COLUMN customer_tag.updated_at  IS '更新时间（审计字段）';
COMMENT ON COLUMN customer_tag.created_by  IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN customer_tag.updated_by  IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 3. customer_profile : 客户画像表
--    业务含义：计算型画像指标（资产/负债/风险等级等汇总）
--    数据分级：L3 (资产总额/负债总额 财务敏感)
--    分区策略：按 computed_at 日期动态分区（画像按日快照）
--    外键关系：customer_id -> customer.customer_id（弱关联，1:1 快照）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_profile (
    profile_id          VARCHAR(64)   NOT NULL            COMMENT '画像记录ID（业务主键）',
    customer_id         VARCHAR(64)   NOT NULL            COMMENT '客户ID（外键 -> customer.customer_id）',
    assets_total        DECIMAL(18,2) NOT NULL DEFAULT 0  COMMENT '资产总额（L3 财务敏感）',
    liabilities_total   DECIMAL(18,2) NOT NULL DEFAULT 0  COMMENT '负债总额（L3 财务敏感）',
    net_assets          DECIMAL(18,2)                     COMMENT '净资产 = 资产 - 负债（L3 财务敏感）',
    aum                 DECIMAL(18,2)                     COMMENT '管理资产规模 AUM（L3 财务敏感）',
    avg_balance_30d     DECIMAL(18,2)                     COMMENT '近30天日均余额（L3 财务敏感）',
    txn_count_30d       INT                               COMMENT '近30天交易笔数',
    txn_amount_30d      DECIMAL(18,2)                     COMMENT '近30天交易总金额（L3 财务敏感）',
    risk_level          VARCHAR(8)                       COMMENT '客户风险等级：A/B/C/D/E（L2 内部业务）',
    risk_score          DECIMAL(10,4)                    COMMENT '风险评分（L2 内部业务）',
    lifecycle_stage     VARCHAR(32)                      COMMENT '生命周期阶段：NEW-新客 / ACTIVE-活跃 / DORMANT-沉睡 / LOST-流失',
    profile_json        STRING                           COMMENT '扩展画像指标 JSON（L3 财务敏感汇总）',
    computed_at         DATETIME      NOT NULL            COMMENT '画像计算时间（快照时间）',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间',
    updated_at          DATETIME      NOT NULL            COMMENT '更新时间',
    created_by          VARCHAR(64)   NOT NULL            COMMENT '创建人（工号/系统）',
    updated_by          VARCHAR(64)   NOT NULL            COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (profile_id, computed_at)
COMMENT '客户画像表 | 数据分级=L3 | 计算型画像指标（资产/负债/风险等级汇总） | 外键：customer_id -> customer.customer_id'
PARTITION BY RANGE (computed_at) ()
DISTRIBUTED BY HASH (customer_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  customer_profile               IS '客户画像表 | 数据分级=L3 | 计算型画像指标';
COMMENT ON COLUMN customer_profile.profile_id    IS '画像记录ID（业务主键）';
COMMENT ON COLUMN customer_profile.customer_id   IS '客户ID（外键 -> customer.customer_id）';
COMMENT ON COLUMN customer_profile.assets_total  IS '资产总额（L3 财务敏感）';
COMMENT ON COLUMN customer_profile.liabilities_total IS '负债总额（L3 财务敏感）';
COMMENT ON COLUMN customer_profile.net_assets    IS '净资产 = 资产 - 负债（L3 财务敏感）';
COMMENT ON COLUMN customer_profile.aum           IS '管理资产规模 AUM（L3 财务敏感）';
COMMENT ON COLUMN customer_profile.avg_balance_30d IS '近30天日均余额（L3 财务敏感）';
COMMENT ON COLUMN customer_profile.txn_count_30d IS '近30天交易笔数';
COMMENT ON COLUMN customer_profile.txn_amount_30d IS '近30天交易总金额（L3 财务敏感）';
COMMENT ON COLUMN customer_profile.risk_level    IS '客户风险等级：A/B/C/D/E（L2 内部业务）';
COMMENT ON COLUMN customer_profile.risk_score    IS '风险评分（L2 内部业务）';
COMMENT ON COLUMN customer_profile.lifecycle_stage IS '生命周期阶段：NEW/ACTIVE/DORMANT/LOST';
COMMENT ON COLUMN customer_profile.profile_json  IS '扩展画像指标 JSON（L3 财务敏感汇总）';
COMMENT ON COLUMN customer_profile.computed_at   IS '画像计算时间（快照时间）';
COMMENT ON COLUMN customer_profile.created_at    IS '创建时间（审计字段）';
COMMENT ON COLUMN customer_profile.updated_at    IS '更新时间（审计字段）';
COMMENT ON COLUMN customer_profile.created_by    IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN customer_profile.updated_by    IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 4. customer_relation : 客户关系表
--    业务含义：担保、关联、家庭等客户间关系
--    数据分级：L3 (关系金额 财务敏感) + L2 (关系类型 内部业务)，表级标 L3
--    分区策略：按 created_at 日期动态分区
--    外键关系：subject_customer_id -> customer.customer_id（弱关联）
--             object_customer_id   -> customer.customer_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_relation (
    relation_id         VARCHAR(64)   NOT NULL            COMMENT '关系记录ID（业务主键）',
    subject_customer_id VARCHAR(64)  NOT NULL            COMMENT '主体客户ID（外键 -> customer.customer_id）',
    object_customer_id  VARCHAR(64)  NOT NULL            COMMENT '客体客户ID（外键 -> customer.customer_id）',
    relation_type       VARCHAR(16)  NOT NULL            COMMENT '关系类型：GUARANTEE-担保 / RELATED-关联 / FAMILY-家庭 / SPOUSE-配偶 / CONTROL-控制',
    relation_subtype    VARCHAR(32)                     COMMENT '关系子类型（如 FAMILY 下：PARENT/CHILD/SIBLING）',
    amount              DECIMAL(18,2)                    COMMENT '关系金额（担保金额，L3 财务敏感）',
    guarantee_ratio     DECIMAL(5,4)                     COMMENT '担保比例（0~1）',
    valid_from          DATETIME                         COMMENT '关系生效开始时间',
    valid_to            DATETIME                         COMMENT '关系生效结束时间',
    is_active           BOOLEAN             DEFAULT TRUE COMMENT '关系是否有效',
    evidence            VARCHAR(512)                     COMMENT '关系依据/证据描述',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间',
    updated_at          DATETIME      NOT NULL            COMMENT '更新时间',
    created_by          VARCHAR(64)   NOT NULL            COMMENT '创建人（工号/系统）',
    updated_by          VARCHAR(64)   NOT NULL            COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (relation_id, created_at)
COMMENT '客户关系表 | 数据分级=L3 | 担保/关联/家庭等客户间关系 | 外键：subject_customer_id/object_customer_id -> customer.customer_id'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (subject_customer_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  customer_relation                  IS '客户关系表 | 数据分级=L3 | 担保/关联/家庭等客户间关系';
COMMENT ON COLUMN customer_relation.relation_id      IS '关系记录ID（业务主键）';
COMMENT ON COLUMN customer_relation.subject_customer_id IS '主体客户ID（外键 -> customer.customer_id）';
COMMENT ON COLUMN customer_relation.object_customer_id  IS '客体客户ID（外键 -> customer.customer_id）';
COMMENT ON COLUMN customer_relation.relation_type    IS '关系类型：GUARANTEE/RELATED/FAMILY/SPOUSE/CONTROL';
COMMENT ON COLUMN customer_relation.relation_subtype IS '关系子类型（如 FAMILY 下：PARENT/CHILD/SIBLING）';
COMMENT ON COLUMN customer_relation.amount           IS '关系金额（担保金额，L3 财务敏感）';
COMMENT ON COLUMN customer_relation.guarantee_ratio  IS '担保比例（0~1）';
COMMENT ON COLUMN customer_relation.valid_from       IS '关系生效开始时间';
COMMENT ON COLUMN customer_relation.valid_to         IS '关系生效结束时间';
COMMENT ON COLUMN customer_relation.is_active        IS '关系是否有效';
COMMENT ON COLUMN customer_relation.evidence         IS '关系依据/证据描述';
COMMENT ON COLUMN customer_relation.created_at       IS '创建时间（审计字段）';
COMMENT ON COLUMN customer_relation.updated_at       IS '更新时间（审计字段）';
COMMENT ON COLUMN customer_relation.created_by       IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN customer_relation.updated_by       IS '更新人（审计字段，工号/系统）';

-- =============================================================================
-- 客户域 DDL 结束 | 共 4 张表：customer / customer_tag / customer_profile / customer_relation
-- =============================================================================