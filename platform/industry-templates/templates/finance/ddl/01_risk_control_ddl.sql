-- =============================================================================
-- File   : 01_risk_control_ddl.sql
-- Domain : 风控域 (Risk Control)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Source : platform/industry-templates/templates/finance/docs/business-model.md §1.1 §2.1
-- Class  : platform/industry-templates/templates/finance/docs/data-classification.md §2.5
-- Tables : risk_model / risk_rule / risk_feature / risk_evaluation / risk_alert (5 张)
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. risk_model : 风控模型主表
--    业务含义：评分卡 / ML 模型 / 规则集的元信息与版本
--    数据分级：L2 (内部业务标识/参数/权重)
--    分区策略：按 created_at 日期动态分区（Doris Dynamic Partition，按天，保留 3650 天）
--    外键关系：无（被 risk_rule / risk_feature / risk_evaluation 引用）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS risk_model (
    model_id        VARCHAR(64)   NOT NULL                COMMENT '风控模型ID（业务主键，雪花ID）',
    model_name      VARCHAR(128)  NOT NULL                COMMENT '风控模型名称',
    model_type      VARCHAR(32)   NOT NULL                COMMENT '模型类型：SCORECARD-评分卡 / ML-机器学习 / RULESET-规则集 / HYBRID-混合',
    version         VARCHAR(32)   NOT NULL                COMMENT '模型版本号（语义化版本，如 v1.0.0）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT '生命周期状态：DRAFT-草稿 / ONLINE-上线 / OFFLINE-下线 / ARCHIVED-归档',
    owner           VARCHAR(64)   NOT NULL                COMMENT '模型负责人（员工工号）',
    dept_code       VARCHAR(64)                            COMMENT '所属部门编码',
    algorithm       VARCHAR(64)                            COMMENT '算法名称：LR / XGBOOST / LIGHTGBM / PSO_LGB / RULE_ENGINE',
    weight          DECIMAL(10,4)           DEFAULT 1.0000 COMMENT '模型在组合决策中的权重（L2 内部业务）',
    params_json     STRING                                 COMMENT '模型参数 JSON（超参、阈值、特征权重，L2 内部业务）',
    description     VARCHAR(512)                           COMMENT '模型描述',
    effective_from  DATETIME                               COMMENT '生效开始时间',
    effective_to    DATETIME                               COMMENT '生效结束时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (model_id, created_at)
COMMENT '风控模型主表 | 数据分级=L2 | 业务含义：评分卡/ML模型/规则集元信息与版本 | 分区：按 created_at 天级动态分区'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (model_id) BUCKETS 8
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
--   CREATE TABLE risk_model (
--     ...,
--     created_at TIMESTAMP(6)
--   ) USING iceberg
--   PARTITIONED BY (days(created_at))
--   TBLPROPERTIES ('format-version'='2', 'write.metadata.delete-after-commit.enabled'='true');
COMMENT ON TABLE risk_model IS '风控模型主表 | 数据分级=L2 | 评分卡/ML模型/规则集元信息与版本';
COMMENT ON COLUMN risk_model.model_id       IS '风控模型ID（业务主键）';
COMMENT ON COLUMN risk_model.model_name     IS '风控模型名称';
COMMENT ON COLUMN risk_model.model_type     IS '模型类型：SCORECARD/ML/RULESET/HYBRID';
COMMENT ON COLUMN risk_model.version        IS '模型版本号（语义化版本）';
COMMENT ON COLUMN risk_model.status         IS '生命周期状态：DRAFT/ONLINE/OFFLINE/ARCHIVED';
COMMENT ON COLUMN risk_model.owner          IS '模型负责人（员工工号）';
COMMENT ON COLUMN risk_model.dept_code      IS '所属部门编码';
COMMENT ON COLUMN risk_model.algorithm      IS '算法名称：LR/XGBOOST/LIGHTGBM/RULE_ENGINE';
COMMENT ON COLUMN risk_model.weight         IS '模型在组合决策中的权重（L2 内部业务）';
COMMENT ON COLUMN risk_model.params_json    IS '模型参数 JSON（L2 内部业务）';
COMMENT ON COLUMN risk_model.description    IS '模型描述';
COMMENT ON COLUMN risk_model.effective_from IS '生效开始时间';
COMMENT ON COLUMN risk_model.effective_to   IS '生效结束时间';
COMMENT ON COLUMN risk_model.created_at     IS '创建时间（审计字段）';
COMMENT ON COLUMN risk_model.updated_at     IS '更新时间（审计字段）';
COMMENT ON COLUMN risk_model.created_by     IS '创建人（审计字段，工号）';
COMMENT ON COLUMN risk_model.updated_by     IS '更新人（审计字段，工号）';

-- -----------------------------------------------------------------------------
-- 2. risk_rule : 风控规则表
--    业务含义：规则集、阈值、命中动作
--    数据分级：L2 (内部业务：规则表达式)
--    分区策略：按 updated_at 日期动态分区
--    外键关系：model_id -> risk_model.model_id（弱关联，Doris 不强制）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS risk_rule (
    rule_id         VARCHAR(64)   NOT NULL                COMMENT '风控规则ID（业务主键）',
    model_id        VARCHAR(64)   NOT NULL                COMMENT '所属风控模型ID（外键 -> risk_model.model_id）',
    rule_name       VARCHAR(128)  NOT NULL                COMMENT '规则名称',
    rule_code       VARCHAR(64)   NOT NULL                COMMENT '规则编码（唯一业务编码）',
    expression      STRING        NOT NULL                COMMENT '规则表达式（DSL/SQL片段，L2 内部业务）',
    action          VARCHAR(16)   NOT NULL DEFAULT 'PASS' COMMENT '命中动作：PASS-通过 / REJECT-拒绝 / MANUAL-人工复核 / ALERT-告警',
    priority        INT           NOT NULL DEFAULT 100    COMMENT '规则优先级（数值越小优先级越高）',
    threshold       DECIMAL(18,4)                          COMMENT '规则阈值',
    hit_count       BIGINT                 DEFAULT 0      COMMENT '历史命中次数（统计字段）',
    enabled         BOOLEAN               DEFAULT TRUE    COMMENT '是否启用',
    description     VARCHAR(512)                          COMMENT '规则描述',
    created_at      DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (rule_id, updated_at)
COMMENT '风控规则表 | 数据分级=L2 | 规则集/阈值/命中动作 | 外键：model_id -> risk_model.model_id'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (rule_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  risk_rule             IS '风控规则表 | 数据分级=L2 | 规则集/阈值/命中动作 | 外键：model_id -> risk_model.model_id';
COMMENT ON COLUMN risk_rule.rule_id     IS '风控规则ID（业务主键）';
COMMENT ON COLUMN risk_rule.model_id    IS '所属风控模型ID（外键 -> risk_model.model_id）';
COMMENT ON COLUMN risk_rule.rule_name   IS '规则名称';
COMMENT ON COLUMN risk_rule.rule_code   IS '规则编码（唯一业务编码）';
COMMENT ON COLUMN risk_rule.expression  IS '规则表达式（DSL/SQL片段，L2 内部业务）';
COMMENT ON COLUMN risk_rule.action      IS '命中动作：PASS/REJECT/MANUAL/ALERT';
COMMENT ON COLUMN risk_rule.priority    IS '规则优先级（数值越小优先级越高）';
COMMENT ON COLUMN risk_rule.threshold   IS '规则阈值';
COMMENT ON COLUMN risk_rule.hit_count   IS '历史命中次数（统计字段）';
COMMENT ON COLUMN risk_rule.enabled     IS '是否启用';
COMMENT ON COLUMN risk_rule.description IS '规则描述';
COMMENT ON COLUMN risk_rule.created_at  IS '创建时间（审计字段）';
COMMENT ON COLUMN risk_rule.updated_at  IS '更新时间（审计字段）';
COMMENT ON COLUMN risk_rule.created_by  IS '创建人（审计字段，工号）';
COMMENT ON COLUMN risk_rule.updated_by  IS '更新人（审计字段，工号）';

-- -----------------------------------------------------------------------------
-- 3. risk_feature : 风控特征表
--    业务含义：特征定义、来源、统计口径
--    数据分级：L2 (内部业务：特征定义)
--    分区策略：按 updated_at 日期动态分区
--    外键关系：model_id -> risk_model.model_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS risk_feature (
    feature_id      VARCHAR(64)   NOT NULL                COMMENT '风控特征ID（业务主键）',
    model_id        VARCHAR(64)   NOT NULL                COMMENT '所属风控模型ID（外键 -> risk_model.model_id）',
    feature_name    VARCHAR(128)  NOT NULL                COMMENT '特征名称',
    feature_code    VARCHAR(64)   NOT NULL                COMMENT '特征编码（唯一业务编码）',
    source_table    VARCHAR(128)                          COMMENT '特征来源表（血缘锚点，L2 内部业务）',
    source_field    VARCHAR(128)                          COMMENT '特征来源字段',
    stat_expr       STRING                                 COMMENT '统计口径表达式（SQL/DSL，L2 内部业务）',
    dtype           VARCHAR(16)   NOT NULL                COMMENT '特征数据类型：INT/DECIMAL/STRING/BOOLEAN/DATETIME',
    default_value   STRING                                 COMMENT '默认值（特征缺失时填充）',
    is_online       BOOLEAN               DEFAULT TRUE    COMMENT '是否上线可用',
    description     VARCHAR(512)                          COMMENT '特征描述',
    created_at      DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                COMMENT '创建人（工号）',
    updated_by      VARCHAR(64)   NOT NULL                COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (feature_id, updated_at)
COMMENT '风控特征表 | 数据分级=L2 | 特征定义/来源/统计口径 | 外键：model_id -> risk_model.model_id'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (feature_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  risk_feature              IS '风控特征表 | 数据分级=L2 | 特征定义/来源/统计口径 | 外键：model_id -> risk_model.model_id';
COMMENT ON COLUMN risk_feature.feature_id   IS '风控特征ID（业务主键）';
COMMENT ON COLUMN risk_feature.model_id     IS '所属风控模型ID（外键 -> risk_model.model_id）';
COMMENT ON COLUMN risk_feature.feature_name IS '特征名称';
COMMENT ON COLUMN risk_feature.feature_code IS '特征编码（唯一业务编码）';
COMMENT ON COLUMN risk_feature.source_table IS '特征来源表（血缘锚点，L2 内部业务）';
COMMENT ON COLUMN risk_feature.source_field IS '特征来源字段';
COMMENT ON COLUMN risk_feature.stat_expr    IS '统计口径表达式（L2 内部业务）';
COMMENT ON COLUMN risk_feature.dtype        IS '特征数据类型：INT/DECIMAL/STRING/BOOLEAN/DATETIME';
COMMENT ON COLUMN risk_feature.default_value IS '默认值（特征缺失时填充）';
COMMENT ON COLUMN risk_feature.is_online    IS '是否上线可用';
COMMENT ON COLUMN risk_feature.description  IS '特征描述';
COMMENT ON COLUMN risk_feature.created_at   IS '创建时间（审计字段）';
COMMENT ON COLUMN risk_feature.updated_at   IS '更新时间（审计字段）';
COMMENT ON COLUMN risk_feature.created_by   IS '创建人（审计字段，工号）';
COMMENT ON COLUMN risk_feature.updated_by   IS '更新人（审计字段，工号）';

-- -----------------------------------------------------------------------------
-- 4. risk_evaluation : 风控评估结果表
--    业务含义：一次评估的输入、命中、决策
--    数据分级：L2 (内部业务：评估决策/评分)
--    分区策略：按 eval_at 日期动态分区（评估量大，按天分区便于冷热分离）
--    外键关系：model_id -> risk_model.model_id（弱关联）
--             biz_id 关联 loan_application.application_id / transaction.transaction_id（按 biz_type 路由）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS risk_evaluation (
    eval_id         VARCHAR(64)   NOT NULL                COMMENT '风控评估ID（业务主键）',
    model_id        VARCHAR(64)   NOT NULL                COMMENT '执行评估的风控模型ID（外键 -> risk_model.model_id）',
    biz_id          VARCHAR(64)   NOT NULL                COMMENT '业务单据ID（申请单/交易号，按 biz_type 路由）',
    biz_type        VARCHAR(16)   NOT NULL                COMMENT '业务类型：APPLICATION-申请 / TRANSACTION-交易 / BEHAVIOR-行为 / LOAN-信贷',
    decision        VARCHAR(16)   NOT NULL                COMMENT '评估决策：PASS-通过 / REJECT-拒绝 / MANUAL-人工复核（L2 内部业务）',
    score           DECIMAL(10,4)                          COMMENT '评估评分（L2 内部业务）',
    grade           VARCHAR(8)                             COMMENT '评估等级：A/B/C/D/E',
    hit_rules       STRING                                 COMMENT '命中规则ID列表（JSON 数组，L2 内部业务）',
    input_snapshot  STRING                                 COMMENT '评估输入快照（JSON，特征值，L2 内部业务）',
    eval_at         DATETIME      NOT NULL                 COMMENT '评估发生时间',
    duration_ms     INT                                    COMMENT '评估耗时（毫秒）',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (eval_id, eval_at)
COMMENT '风控评估结果表 | 数据分级=L2 | 一次评估的输入/命中/决策 | 外键：model_id -> risk_model.model_id；biz_id -> loan_application/transaction'
PARTITION BY RANGE (eval_at) ()
DISTRIBUTED BY HASH (eval_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  risk_evaluation              IS '风控评估结果表 | 数据分级=L2 | 一次评估的输入/命中/决策';
COMMENT ON COLUMN risk_evaluation.eval_id      IS '风控评估ID（业务主键）';
COMMENT ON COLUMN risk_evaluation.model_id     IS '执行评估的风控模型ID（外键 -> risk_model.model_id）';
COMMENT ON COLUMN risk_evaluation.biz_id       IS '业务单据ID（按 biz_type 路由到 loan_application/transaction）';
COMMENT ON COLUMN risk_evaluation.biz_type     IS '业务类型：APPLICATION/TRANSACTION/BEHAVIOR/LOAN';
COMMENT ON COLUMN risk_evaluation.decision     IS '评估决策：PASS/REJECT/MANUAL（L2 内部业务）';
COMMENT ON COLUMN risk_evaluation.score        IS '评估评分（L2 内部业务）';
COMMENT ON COLUMN risk_evaluation.grade        IS '评估等级：A/B/C/D/E';
COMMENT ON COLUMN risk_evaluation.hit_rules    IS '命中规则ID列表（JSON 数组，L2 内部业务）';
COMMENT ON COLUMN risk_evaluation.input_snapshot IS '评估输入快照（JSON，L2 内部业务）';
COMMENT ON COLUMN risk_evaluation.eval_at      IS '评估发生时间';
COMMENT ON COLUMN risk_evaluation.duration_ms  IS '评估耗时（毫秒）';
COMMENT ON COLUMN risk_evaluation.created_at   IS '创建时间（审计字段）';
COMMENT ON COLUMN risk_evaluation.updated_at   IS '更新时间（审计字段）';
COMMENT ON COLUMN risk_evaluation.created_by   IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN risk_evaluation.updated_by   IS '更新人（审计字段，工号/系统）';

-- -----------------------------------------------------------------------------
-- 5. risk_alert : 风控告警表
--    业务含义：评估产生的告警，含命中规则、告警级别、处置状态
--    数据分级：L3 (告警详情涉客户/交易敏感)
--    分区策略：按 alert_at 日期动态分区（告警量大，按天分区）
--    外键关系：eval_id -> risk_evaluation.eval_id（弱关联）
--             rule_id -> risk_rule.rule_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS risk_alert (
    alert_id        VARCHAR(64)   NOT NULL                COMMENT '风控告警ID（业务主键）',
    eval_id         VARCHAR(64)   NOT NULL                COMMENT '产生告警的评估ID（外键 -> risk_evaluation.eval_id）',
    rule_id         VARCHAR(64)                            COMMENT '命中的规则ID（外键 -> risk_rule.rule_id）',
    alert_level     VARCHAR(8)    NOT NULL                 COMMENT '告警级别：LOW-低 / MEDIUM-中 / HIGH-高 / CRITICAL-紧急',
    alert_status    VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '告警状态：PENDING-待处理 / PROCESSING-处理中 / RESOLVED-已处理 / IGNORED-已忽略',
    alert_type      VARCHAR(32)                            COMMENT '告警类型：FRAUD-欺诈 / AML-反洗钱 / CREDIT-信用 / BEHAVIOR-行为',
    alert_detail    STRING                                 COMMENT '告警详情（JSON，含命中要素/客户/交易摘要，L3 涉敏感）',
    handle_opinion  VARCHAR(512)                           COMMENT '处置意见',
    handled_by      VARCHAR(64)                            COMMENT '处置人（工号）',
    handled_at      DATETIME                               COMMENT '处置时间',
    alert_at        DATETIME      NOT NULL                 COMMENT '告警发生时间',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (alert_id, alert_at)
COMMENT '风控告警表 | 数据分级=L3 | 评估产生的告警，含命中规则/告警级别/处置状态 | 外键：eval_id -> risk_evaluation.eval_id；rule_id -> risk_rule.rule_id'
PARTITION BY RANGE (alert_at) ()
DISTRIBUTED BY HASH (alert_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  risk_alert               IS '风控告警表 | 数据分级=L3 | 评估产生的告警，含命中规则/告警级别/处置状态';
COMMENT ON COLUMN risk_alert.alert_id      IS '风控告警ID（业务主键）';
COMMENT ON COLUMN risk_alert.eval_id       IS '产生告警的评估ID（外键 -> risk_evaluation.eval_id）';
COMMENT ON COLUMN risk_alert.rule_id       IS '命中的规则ID（外键 -> risk_rule.rule_id）';
COMMENT ON COLUMN risk_alert.alert_level   IS '告警级别：LOW/MEDIUM/HIGH/CRITICAL';
COMMENT ON COLUMN risk_alert.alert_status  IS '告警状态：PENDING/PROCESSING/RESOLVED/IGNORED';
COMMENT ON COLUMN risk_alert.alert_type    IS '告警类型：FRAUD/AML/CREDIT/BEHAVIOR';
COMMENT ON COLUMN risk_alert.alert_detail  IS '告警详情（JSON，L3 涉客户/交易敏感）';
COMMENT ON COLUMN risk_alert.handle_opinion IS '处置意见';
COMMENT ON COLUMN risk_alert.handled_by    IS '处置人（工号）';
COMMENT ON COLUMN risk_alert.handled_at    IS '处置时间';
COMMENT ON COLUMN risk_alert.alert_at      IS '告警发生时间';
COMMENT ON COLUMN risk_alert.created_at    IS '创建时间（审计字段）';
COMMENT ON COLUMN risk_alert.updated_at    IS '更新时间（审计字段）';
COMMENT ON COLUMN risk_alert.created_by    IS '创建人（审计字段，工号/系统）';
COMMENT ON COLUMN risk_alert.updated_by    IS '更新人（审计字段，工号/系统）';

-- =============================================================================
-- 风控域 DDL 结束 | 共 5 张表：risk_model / risk_rule / risk_feature / risk_evaluation / risk_alert
-- =============================================================================