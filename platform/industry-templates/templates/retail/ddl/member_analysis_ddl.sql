-- =============================================================================
-- File   : member_analysis_ddl.sql
-- Domain : 会员分析域 (Member Analysis)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Source : T038 零售行业模板 - 会员分析（RFM/流失预测/LTV）
-- Tables : member / member_rfm / member_churn_prediction / member_ltv /
--          member_tag / member_behavior_profile (6 张)
-- Notice : Doris 不强制外键，关联关系以注释说明
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. member : 会员基本信息主表
--    业务含义：注册会员主数据，含基础属性/等级/积分等
--    数据分级：L3 (姓名/手机号/地址 个人隐私) 混合，表级标 L3
--    分区策略：按 created_at 日期动态分区
--    外键关系：无（被 member_rfm / member_churn_prediction / member_ltv / member_tag 引用）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member (
    member_id       VARCHAR(64)   NOT NULL                COMMENT '会员ID（业务主键，雪花ID）',
    member_no       VARCHAR(64)   NOT NULL                COMMENT '会员编号（唯一，对外展示用）',
    name            VARCHAR(128)                          COMMENT '会员姓名（L3 个人隐私，查询脱敏）',
    phone           VARCHAR(32)                           COMMENT '手机号（L3 个人隐私，查询脱敏）',
    email           VARCHAR(128)                          COMMENT '电子邮箱（L3 个人隐私）',
    gender          VARCHAR(8)                            COMMENT '性别：M/F/U',
    birth_date      DATE                                  COMMENT '出生日期',
    city            VARCHAR(64)                           COMMENT '所在城市',
    province        VARCHAR(64)                           COMMENT '所在省份',
    member_level    VARCHAR(16)   NOT NULL DEFAULT 'BRONZE' COMMENT '会员等级：BRONZE-青铜 / SILVER-白银 / GOLD-黄金 / PLATINUM-铂金 / DIAMOND-钻石',
    points_balance  INT                                   COMMENT '积分余额',
    total_points    INT                                   COMMENT '累计积分',
    register_channel VARCHAR(32)                          COMMENT '注册渠道：APP / WEB / WECHAT_MINI / OFFLINE / TAOBAO / JD',
    register_at     DATETIME                              COMMENT '注册时间',
    last_login_at   DATETIME                              COMMENT '最近登录时间',
    last_purchase_at DATETIME                             COMMENT '最近购买时间',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '会员状态：ACTIVE-活跃 / DORMANT-沉睡 / LOST-流失 / CLOSED-注销',
    created_at      DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (member_id, created_at)
COMMENT '会员基本信息主表 | 数据分级=L3 | 注册会员主数据'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (member_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  member                  IS '会员基本信息主表 | 数据分级=L3 | 注册会员主数据';
COMMENT ON COLUMN member.member_id        IS '会员ID（业务主键）';
COMMENT ON COLUMN member.member_level     IS '会员等级：BRONZE/SILVER/GOLD/PLATINUM/DIAMOND';
COMMENT ON COLUMN member.register_channel IS '注册渠道：APP/WEB/WECHAT_MINI/OFFLINE/TAOBAO/JD';
COMMENT ON COLUMN member.status           IS '会员状态：ACTIVE/DORMANT/LOST/CLOSED';

-- -----------------------------------------------------------------------------
-- 2. member_rfm : 会员 RFM 分群表
--    业务含义：RFM 模型分群结果（R=最近购买间隔，F=购买频率，M=累计金额）
--    数据分级：L3 (累计金额 财务敏感)
--    分区策略：按 computed_at 日期动态分区（按日快照）
--    外键关系：member_id -> member.member_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_rfm (
    rfm_id              VARCHAR(64)   NOT NULL            COMMENT 'RFM 记录ID（业务主键）',
    member_id           VARCHAR(64)   NOT NULL            COMMENT '会员ID（外键 -> member.member_id）',
    stat_date           DATE          NOT NULL            COMMENT '统计日期',
    recency_days        INT           NOT NULL            COMMENT 'R：最近购买距今天数（越小越优）',
    frequency           INT           NOT NULL            COMMENT 'F：统计周期内购买次数',
    monetary            DECIMAL(18,2) NOT NULL            COMMENT 'M：统计周期内累计消费金额（L3 财务敏感）',
    avg_order_value     DECIMAL(18,2)                     COMMENT '平均客单价 = M / F（L3 财务敏感）',
    r_score             INT           NOT NULL            COMMENT 'R 评分（1~5，5 最优）',
    f_score             INT           NOT NULL            COMMENT 'F 评分（1~5，5 最优）',
    m_score             INT           NOT NULL            COMMENT 'M 评分（1~5，5 最优）',
    rfm_score           INT           NOT NULL            COMMENT 'RFM 总分 = R*100 + F*10 + M（125~555）',
    rfm_segment         VARCHAR(32)   NOT NULL            COMMENT 'RFM 分群：CHAMPION-冠军 / LOYAL-忠诚 / POTENTIAL_LOYAL-潜力忠诚 / NEW-新客 / PROMISING-有潜力 / NEED_ATTENTION-需关注 / ABOUT_TO_SLEEP-将沉睡 / HIBERNATING-沉睡 / LOST-流失 / LOST_CHEAP-低价值流失',
    segment_value_level VARCHAR(16)                       COMMENT '分群价值等级：HIGH-高价值 / MEDIUM-中价值 / LOW-低价值',
    stat_period_days    INT                               COMMENT '统计周期天数（如 365）',
    computed_at         DATETIME      NOT NULL            COMMENT '计算时间',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (rfm_id, stat_date)
COMMENT '会员 RFM 分群表 | 数据分级=L3 | RFM 模型分群结果 | 外键：member_id -> member.member_id'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (member_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  member_rfm                  IS '会员 RFM 分群表 | RFM 模型分群结果';
COMMENT ON COLUMN member_rfm.recency_days     IS 'R：最近购买距今天数（越小越优）';
COMMENT ON COLUMN member_rfm.frequency        IS 'F：统计周期内购买次数';
COMMENT ON COLUMN member_rfm.monetary         IS 'M：累计消费金额（L3 财务敏感）';
COMMENT ON COLUMN member_rfm.r_score          IS 'R 评分（1~5，5 最优）';
COMMENT ON COLUMN member_rfm.f_score          IS 'F 评分（1~5，5 最优）';
COMMENT ON COLUMN member_rfm.m_score          IS 'M 评分（1~5，5 最优）';
COMMENT ON COLUMN member_rfm.rfm_segment      IS 'RFM 分群（10 个分群）';
COMMENT ON COLUMN member_rfm.segment_value_level IS '分群价值等级：HIGH/MEDIUM/LOW';

-- -----------------------------------------------------------------------------
-- 3. member_churn_prediction : 会员流失预测表
--    业务含义：基于机器学习二分类模型（逻辑回归 + GBDT 集成）预测会员流失概率
--    数据分级：L2 (内部业务)
--    分区策略：按 predicted_at 日期动态分区
--    外键关系：member_id -> member.member_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_churn_prediction (
    prediction_id       VARCHAR(64)   NOT NULL            COMMENT '预测记录ID（业务主键）',
    member_id           VARCHAR(64)   NOT NULL            COMMENT '会员ID（外键 -> member.member_id）',
    predicted_at        DATETIME      NOT NULL            COMMENT '预测时间',
    churn_probability   DECIMAL(5,4)  NOT NULL            COMMENT '流失概率（0~1，模型输出）',
    churn_label         VARCHAR(8)    NOT NULL            COMMENT '流失标签：YES-将流失 / NO-不流失（按阈值二分类）',
    risk_level          VARCHAR(16)   NOT NULL            COMMENT '风险等级：HIGH-高风险(>0.7) / MEDIUM-中风险(0.3~0.7) / LOW-低风险(<0.3)',
    model_name          VARCHAR(64)   NOT NULL            COMMENT '模型名称（如 churn_lr_gbdt_ensemble_v1）',
    model_version       VARCHAR(32)   NOT NULL            COMMENT '模型版本',
    feature_importance  STRING                            COMMENT '特征重要性 JSON（如 [{"feature":"recency_days","importance":0.32}]）',
    prediction_window   INT                               COMMENT '预测窗口天数（如 30 表示预测未来 30 天流失）',
    threshold           DECIMAL(5,4)                      COMMENT '二分类阈值（默认 0.5）',
    computed_at         DATETIME      NOT NULL            COMMENT '计算时间',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (prediction_id, predicted_at)
COMMENT '会员流失预测表 | 数据分级=L2 | ML 二分类模型预测结果 | 外键：member_id -> member.member_id'
PARTITION BY RANGE (predicted_at) ()
DISTRIBUTED BY HASH (member_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  member_churn_prediction                  IS '会员流失预测表 | ML 二分类模型预测结果';
COMMENT ON COLUMN member_churn_prediction.churn_probability IS '流失概率（0~1）';
COMMENT ON COLUMN member_churn_prediction.churn_label      IS '流失标签：YES/NO';
COMMENT ON COLUMN member_churn_prediction.risk_level       IS '风险等级：HIGH/MEDIUM/LOW';
COMMENT ON COLUMN member_churn_prediction.model_name       IS '模型名称';
COMMENT ON COLUMN member_churn_prediction.feature_importance IS '特征重要性 JSON';

-- -----------------------------------------------------------------------------
-- 4. member_ltv : 会员生命周期价值表
--    业务含义：基于 BG/NBD + Gamma-Gamma 模型预测会员未来生命周期价值
--    数据分级：L3 (LTV 金额 财务敏感)
--    分区策略：按 computed_at 日期动态分区
--    外键关系：member_id -> member.member_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_ltv (
    ltv_id                 VARCHAR(64)   NOT NULL          COMMENT 'LTV 记录ID（业务主键）',
    member_id              VARCHAR(64)   NOT NULL          COMMENT '会员ID（外键 -> member.member_id）',
    computed_at            DATETIME      NOT NULL          COMMENT '计算时间',
    historical_value       DECIMAL(18,2) NOT NULL          COMMENT '历史累计消费金额（L3 财务敏感）',
    predicted_value_30d    DECIMAL(18,2)                   COMMENT '未来 30 天预测消费金额（L3 财务敏感）',
    predicted_value_90d    DECIMAL(18,2)                   COMMENT '未来 90 天预测消费金额（L3 财务敏感）',
    predicted_value_180d   DECIMAL(18,2)                   COMMENT '未来 180 天预测消费金额（L3 财务敏感）',
    predicted_value_365d   DECIMAL(18,2)                   COMMENT '未来 365 天预测消费金额（L3 财务敏感）',
    total_ltv              DECIMAL(18,2)                   COMMENT '总 LTV = 历史价值 + 未来 365 天预测价值（L3 财务敏感）',
    ltv_segment            VARCHAR(32)                     COMMENT 'LTV 分层：VIP-超高层(>10000) / HIGH-高层(5000~10000) / MEDIUM-中层(1000~5000) / LOW-低层(100~1000) / BOTTOM-底层(<100)',
    predicted_p_alive      DECIMAL(5,4)                    COMMENT 'BG/NBD 模型 P(Alive) 概率（0~1，会员仍活跃概率）',
    predicted_p_purchase   DECIMAL(5,4)                    COMMENT '未来 30 天预期购买次数',
    model_name             VARCHAR(64)                     COMMENT '模型名称（如 bgnbd_gamma_gamma_v1）',
    model_version          VARCHAR(32)                     COMMENT '模型版本',
    confidence_interval    STRING                          COMMENT '95% 置信区间 JSON（如 {"lower":800,"upper":1200}）',
    created_at             DATETIME      NOT NULL          COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (ltv_id, computed_at)
COMMENT '会员生命周期价值表 | 数据分级=L3 | BG/NBD + Gamma-Gamma 模型预测 | 外键：member_id -> member.member_id'
PARTITION BY RANGE (computed_at) ()
DISTRIBUTED BY HASH (member_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  member_ltv                       IS '会员生命周期价值表 | BG/NBD + Gamma-Gamma 模型预测';
COMMENT ON COLUMN member_ltv.historical_value      IS '历史累计消费金额（L3 财务敏感）';
COMMENT ON COLUMN member_ltv.predicted_value_365d  IS '未来 365 天预测消费金额（L3 财务敏感）';
COMMENT ON COLUMN member_ltv.total_ltv             IS '总 LTV（L3 财务敏感）';
COMMENT ON COLUMN member_ltv.ltv_segment           IS 'LTV 分层：VIP/HIGH/MEDIUM/LOW/BOTTOM';
COMMENT ON COLUMN member_ltv.predicted_p_alive     IS 'BG/NBD P(Alive) 概率（0~1）';

-- -----------------------------------------------------------------------------
-- 5. member_tag : 会员标签表
--    业务含义：会员业务标签（RFM 等级/流失风险/LTV 分层/活跃度/品类偏好等），由标签引擎计算
--    数据分级：L2 (内部业务)
--    分区策略：按 tagged_at 日期动态分区
--    外键关系：member_id -> member.member_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_tag (
    tag_id          VARCHAR(64)   NOT NULL                COMMENT '标签记录ID（业务主键）',
    member_id       VARCHAR(64)   NOT NULL                COMMENT '会员ID（外键 -> member.member_id）',
    tag_code        VARCHAR(64)   NOT NULL                COMMENT '标签编码（如 RFM_CHAMPION / CHURN_HIGH_RISK / LTV_VIP / PRICE_SENSITIVE / CATEGORY_FASHION_LOVER）',
    tag_value       VARCHAR(128)                          COMMENT '标签值（L2 内部业务标签）',
    tag_category    VARCHAR(32)   NOT NULL                COMMENT '标签分类：RFM-分群 / CHURN-流失 / LTV-价值 / BEHAVIOR-行为 / PREFERENCE-偏好 / DEMOGRAPHIC-人口',
    tag_source      VARCHAR(16)   NOT NULL                COMMENT '标签来源：MANUAL-人工 / RULE-规则 / MODEL-模型 / IMPORT-导入',
    confidence      DECIMAL(5,4)           DEFAULT 1.0000 COMMENT '标签置信度（0~1，模型打标时输出）',
    expire_at       DATETIME                              COMMENT '标签过期时间',
    tagged_at       DATETIME      NOT NULL                COMMENT '打标时间',
    created_at      DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                COMMENT '创建人（工号/系统）',
    updated_by      VARCHAR(64)   NOT NULL                COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (tag_id, tagged_at)
COMMENT '会员标签表 | 数据分级=L2 | 会员业务标签 | 外键：member_id -> member.member_id'
PARTITION BY RANGE (tagged_at) ()
DISTRIBUTED BY HASH (member_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  member_tag              IS '会员标签表 | 会员业务标签 | 外键：member_id -> member.member_id';
COMMENT ON COLUMN member_tag.tag_code     IS '标签编码（RFM_CHAMPION/CHURN_HIGH_RISK/LTV_VIP 等）';
COMMENT ON COLUMN member_tag.tag_category IS '标签分类：RFM/CHURN/LTV/BEHAVIOR/PREFERENCE/DEMOGRAPHIC';
COMMENT ON COLUMN member_tag.tag_source   IS '标签来源：MANUAL/RULE/MODEL/IMPORT';
COMMENT ON COLUMN member_tag.confidence   IS '标签置信度（0~1）';

-- -----------------------------------------------------------------------------
-- 6. member_behavior_profile : 会员行为画像表
--    业务含义：会员行为画像指标（活跃度/购买偏好/价格敏感度/渠道偏好等）
--    数据分级：L3 (消费金额 财务敏感)
--    分区策略：按 computed_at 日期动态分区
--    外键关系：member_id -> member.member_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_behavior_profile (
    profile_id              VARCHAR(64)   NOT NULL        COMMENT '画像记录ID（业务主键）',
    member_id               VARCHAR(64)   NOT NULL        COMMENT '会员ID（外键 -> member.member_id）',
    computed_at             DATETIME      NOT NULL        COMMENT '画像计算时间（快照时间）',
    active_days_30d         INT                           COMMENT '近 30 天活跃天数',
    login_count_30d         INT                           COMMENT '近 30 天登录次数',
    browse_count_30d        INT                           COMMENT '近 30 天浏览商品次数',
    cart_count_30d          INT                           COMMENT '近 30 天加购次数',
    purchase_count_30d      INT                           COMMENT '近 30 天购买次数',
    purchase_amount_30d     DECIMAL(18,2)                 COMMENT '近 30 天消费金额（L3 财务敏感）',
    avg_order_value_30d     DECIMAL(18,2)                 COMMENT '近 30 天平均客单价（L3 财务敏感）',
    favorite_category       VARCHAR(64)                   COMMENT '最常购买类目',
    favorite_brand          VARCHAR(64)                   COMMENT '最常购买品牌',
    price_sensitivity       DECIMAL(5,4)                  COMMENT '价格敏感度（0~1，1 表示极度敏感）',
    preferred_channel       VARCHAR(32)                   COMMENT '首选购买渠道',
    preferred_payment       VARCHAR(32)                   COMMENT '首选支付方式',
    browse_to_purchase_rate DECIMAL(5,4)                  COMMENT '浏览到购买转化率（0~1）',
    cart_to_purchase_rate   DECIMAL(5,4)                  COMMENT '加购到购买转化率（0~1）',
    return_rate             DECIMAL(5,4)                  COMMENT '退货率（0~1）',
    activity_score          DECIMAL(5,4)                  COMMENT '活跃度评分（0~1，加权计算）',
    profile_json            STRING                        COMMENT '扩展画像指标 JSON',
    created_at              DATETIME      NOT NULL        COMMENT '创建时间',
    updated_at              DATETIME      NOT NULL        COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (profile_id, computed_at)
COMMENT '会员行为画像表 | 数据分级=L3 | 活跃度/购买偏好/价格敏感度/渠道偏好 | 外键：member_id -> member.member_id'
PARTITION BY RANGE (computed_at) ()
DISTRIBUTED BY HASH (member_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  member_behavior_profile                       IS '会员行为画像表 | 活跃度/购买偏好/价格敏感度';
COMMENT ON COLUMN member_behavior_profile.price_sensitivity      IS '价格敏感度（0~1，1 表示极度敏感）';
COMMENT ON COLUMN member_behavior_profile.browse_to_purchase_rate IS '浏览到购买转化率（0~1）';
COMMENT ON COLUMN member_behavior_profile.activity_score         IS '活跃度评分（0~1）';

-- =============================================================================
-- 会员分析域 DDL 完成：6 张表
-- member / member_rfm / member_churn_prediction / member_ltv /
-- member_tag / member_behavior_profile
-- =============================================================================