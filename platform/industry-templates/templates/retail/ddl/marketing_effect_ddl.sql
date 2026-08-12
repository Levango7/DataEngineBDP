-- =============================================================================
-- File   : marketing_effect_ddl.sql
-- Domain : 营销效果域 (Marketing Effect)
-- Engine : Apache Doris (主) / Apache Iceberg (备，注释中给出兼容写法)
-- Charset: UTF-8
-- Source : T038 零售行业模板 - 营销效果（A/B 实验/转化漏斗/ROI）
-- Tables : ab_experiment / ab_experiment_variant / conversion_funnel /
--          marketing_campaign / marketing_roi / marketing_channel_stat (6 张)
-- Notice : Doris 不强制外键，关联关系以注释说明
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. ab_experiment : A/B 实验主表
--    业务含义：A/B 实验元信息（实验名称/假设/状态/起止时间）
--    数据分级：L2 (内部业务)
--    分区策略：按 created_at 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ab_experiment (
    experiment_id       VARCHAR(64)   NOT NULL            COMMENT '实验ID（业务主键）',
    experiment_name     VARCHAR(128)  NOT NULL            COMMENT '实验名称',
    experiment_code     VARCHAR(64)   NOT NULL            COMMENT '实验编码（唯一）',
    hypothesis          VARCHAR(512)                      COMMENT '实验假设（如 限时折扣 banner 可提升加购率 5%）',
    primary_metric      VARCHAR(64)   NOT NULL            COMMENT '主要观测指标（如 cart_rate / conversion_rate / gmv）',
    secondary_metrics   STRING                            COMMENT '次要观测指标 JSON 数组',
    status              VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT '实验状态：DRAFT-草稿 / RUNNING-运行中 / PAUSED-暂停 / COMPLETED-完成 / CANCELLED-取消',
    start_at            DATETIME                          COMMENT '实验开始时间',
    end_at              DATETIME                          COMMENT '实验结束时间',
    traffic_percentage  DECIMAL(5,2)            DEFAULT 100.00 COMMENT '流量占比（0~100，实验占总流量比例）',
    sample_size         INT                               COMMENT '样本量（每个变体所需样本数，由功效分析计算）',
    significance_level  DECIMAL(5,4)            DEFAULT 0.0500 COMMENT '显著性水平 α（默认 0.05）',
    statistical_power   DECIMAL(5,4)            DEFAULT 0.8000 COMMENT '统计功效 1-β（默认 0.8）',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间',
    updated_at          DATETIME      NOT NULL            COMMENT '更新时间',
    created_by          VARCHAR(64)   NOT NULL            COMMENT '创建人（工号/系统）',
    updated_by          VARCHAR(64)   NOT NULL            COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (experiment_id, created_at)
COMMENT 'A/B 实验主表 | 数据分级=L2 | 实验元信息'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (experiment_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  ab_experiment                       IS 'A/B 实验主表 | 实验元信息';
COMMENT ON COLUMN ab_experiment.primary_metric        IS '主要观测指标';
COMMENT ON COLUMN ab_experiment.significance_level    IS '显著性水平 α（默认 0.05）';
COMMENT ON COLUMN ab_experiment.statistical_power     IS '统计功效 1-β（默认 0.8）';

-- -----------------------------------------------------------------------------
-- 2. ab_experiment_variant : A/B 实验变体表
--    业务含义：A/B 实验的变体（对照组 / 实验组）与显著性检验结果
--    数据分级：L2 (内部业务)
--    分区策略：按 computed_at 日期动态分区
--    外键关系：experiment_id -> ab_experiment.experiment_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ab_experiment_variant (
    variant_id              VARCHAR(64)   NOT NULL        COMMENT '变体记录ID（业务主键）',
    experiment_id           VARCHAR(64)   NOT NULL        COMMENT '实验ID（外键 -> ab_experiment.experiment_id）',
    variant_code            VARCHAR(32)   NOT NULL        COMMENT '变体编码：CONTROL-对照组 / TREATMENT_A-实验组A / TREATMENT_B-实验组B',
    variant_name            VARCHAR(64)   NOT NULL        COMMENT '变体名称',
    user_count              INT           NOT NULL        COMMENT '分流用户数',
    metric_value            DECIMAL(18,6)                 COMMENT '主要指标值（如转化率 0.123456）',
    metric_value_secondary  STRING                        COMMENT '次要指标值 JSON',
    conversion_count        INT                           COMMENT '转化数（如完成下单的用户数）',
    conversion_rate         DECIMAL(8,6)                  COMMENT '转化率（0~1）',
    lift                    DECIMAL(10,6)                 COMMENT '提升度（相对对照组的提升百分比）',
    p_value                 DECIMAL(10,8)                 COMMENT 'P 值（显著性检验结果）',
    z_score                 DECIMAL(10,6)                 COMMENT 'Z 值（标准正态分布统计量）',
    confidence_interval     STRING                        COMMENT '95% 置信区间 JSON（如 {"lower":0.02,"upper":0.08}）',
    is_significant          BOOLEAN                       COMMENT '是否统计显著（P 值 < α）',
    is_winner               BOOLEAN                       COMMENT '是否获胜变体',
    test_method             VARCHAR(16)                   COMMENT '检验方法：Z_TEST-Z 检验 / T_TEST-T 检验 / CHI_SQUARE-卡方检验 / MANN_WHITNEY-U 检验',
    computed_at             DATETIME      NOT NULL        COMMENT '计算时间',
    created_at              DATETIME      NOT NULL        COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (variant_id, computed_at)
COMMENT 'A/B 实验变体表 | 数据分级=L2 | 对照组/实验组与显著性检验结果 | 外键：experiment_id -> ab_experiment.experiment_id'
PARTITION BY RANGE (computed_at) ()
DISTRIBUTED BY HASH (experiment_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  ab_experiment_variant                  IS 'A/B 实验变体表 | 显著性检验结果';
COMMENT ON COLUMN ab_experiment_variant.variant_code     IS '变体编码：CONTROL/TREATMENT_A/TREATMENT_B';
COMMENT ON COLUMN ab_experiment_variant.conversion_rate  IS '转化率（0~1）';
COMMENT ON COLUMN ab_experiment_variant.lift             IS '提升度（相对对照组的提升百分比）';
COMMENT ON COLUMN ab_experiment_variant.p_value          IS 'P 值';
COMMENT ON COLUMN ab_experiment_variant.z_score          IS 'Z 值';
COMMENT ON COLUMN ab_experiment_variant.is_significant   IS '是否统计显著（P 值 < α）';
COMMENT ON COLUMN ab_experiment_variant.test_method      IS '检验方法：Z_TEST/T_TEST/CHI_SQUARE/MANN_WHITNEY';

-- -----------------------------------------------------------------------------
-- 3. conversion_funnel : 转化漏斗表
--    业务含义：营销转化漏斗（曝光 → 点击 → 加购 → 下单 → 支付，5 步漏斗）
--    数据分级：L2 (内部业务)
--    分区策略：按 stat_date 日期动态分区
--    外键关系：campaign_id -> marketing_campaign.campaign_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversion_funnel (
    funnel_id              VARCHAR(64)   NOT NULL         COMMENT '漏斗记录ID（业务主键）',
    campaign_id            VARCHAR(64)                    COMMENT '营销活动ID（外键 -> marketing_campaign.campaign_id，全站漏斗可为 NULL）',
    funnel_name            VARCHAR(128)  NOT NULL         COMMENT '漏斗名称',
    stat_date              DATE          NOT NULL         COMMENT '统计日期',
    step_exposure_count    INT           NOT NULL DEFAULT 0 COMMENT '第 1 步：曝光数',
    step_click_count       INT                                COMMENT '第 2 步：点击数',
    step_cart_count        INT                                COMMENT '第 3 步：加购数',
    step_order_count       INT                                COMMENT '第 4 步：下单数',
    step_pay_count         INT                                COMMENT '第 5 步：支付数',
    step_click_rate        DECIMAL(8,6)                      COMMENT '点击率 = 点击/曝光（0~1）',
    step_cart_rate         DECIMAL(8,6)                      COMMENT '加购率 = 加购/点击（0~1）',
    step_order_rate        DECIMAL(8,6)                      COMMENT '下单率 = 下单/加购（0~1）',
    step_pay_rate          DECIMAL(8,6)                      COMMENT '支付率 = 支付/下单（0~1）',
    overall_conversion_rate DECIMAL(8,6)                     COMMENT '总体转化率 = 支付/曝光（0~1）',
    drop_off_exposure_click DECIMAL(8,6)                     COMMENT '曝光→点击流失率（0~1）',
    drop_off_click_cart    DECIMAL(8,6)                      COMMENT '点击→加购流失率（0~1）',
    drop_off_cart_order    DECIMAL(8,6)                      COMMENT '加购→下单流失率（0~1）',
    drop_off_order_pay     DECIMAL(8,6)                      COMMENT '下单→支付流失率（0~1）',
    avg_time_to_pay_seconds INT                              COMMENT '从曝光到支付的平均时长（秒）',
    computed_at            DATETIME      NOT NULL           COMMENT '计算时间',
    created_at             DATETIME      NOT NULL           COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (funnel_id, stat_date)
COMMENT '转化漏斗表 | 数据分级=L2 | 曝光→点击→加购→下单→支付 5 步漏斗'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (funnel_name) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  conversion_funnel                       IS '转化漏斗表 | 5 步漏斗';
COMMENT ON COLUMN conversion_funnel.step_exposure_count   IS '第 1 步：曝光数';
COMMENT ON COLUMN conversion_funnel.step_click_count      IS '第 2 步：点击数';
COMMENT ON COLUMN conversion_funnel.step_cart_count       IS '第 3 步：加购数';
COMMENT ON COLUMN conversion_funnel.step_order_count      IS '第 4 步：下单数';
COMMENT ON COLUMN conversion_funnel.step_pay_count        IS '第 5 步：支付数';
COMMENT ON COLUMN conversion_funnel.overall_conversion_rate IS '总体转化率 = 支付/曝光';

-- -----------------------------------------------------------------------------
-- 4. marketing_campaign : 营销活动主表
--    业务含义：营销活动元信息（活动名称/类型/预算/起止时间）
--    数据分级：L3 (预算/投入金额 财务敏感)
--    分区策略：按 created_at 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS marketing_campaign (
    campaign_id          VARCHAR(64)   NOT NULL           COMMENT '活动ID（业务主键）',
    campaign_name        VARCHAR(128)  NOT NULL           COMMENT '活动名称',
    campaign_code        VARCHAR(64)   NOT NULL           COMMENT '活动编码（唯一）',
    campaign_type        VARCHAR(32)   NOT NULL           COMMENT '活动类型：DISCOUNT-折扣 / COUPON-优惠券 / FULL_REDUCTION-满减 / BUNDLE-组合优惠 / FLASH_SALE-限时秒杀 / GROUP_BUY-拼团 / POINTS-积分',
    budget_amount        DECIMAL(18,2)                    COMMENT '活动预算金额（L3 财务敏感）',
    actual_cost          DECIMAL(18,2)                    COMMENT '实际投入金额（L3 财务敏感）',
    target_audience_count INT                             COMMENT '目标受众人数',
    actual_audience_count INT                             COMMENT '实际触达人数',
    start_at             DATETIME                         COMMENT '活动开始时间',
    end_at               DATETIME                         COMMENT '活动结束时间',
    status               VARCHAR(16)   NOT NULL DEFAULT 'PLANNED' COMMENT '活动状态：PLANNED-计划中 / RUNNING-进行中 / COMPLETED-已完成 / CANCELLED-已取消',
    description          STRING                           COMMENT '活动描述',
    created_at           DATETIME      NOT NULL           COMMENT '创建时间',
    updated_at           DATETIME      NOT NULL           COMMENT '更新时间',
    created_by           VARCHAR(64)   NOT NULL           COMMENT '创建人（工号/系统）',
    updated_by           VARCHAR(64)   NOT NULL           COMMENT '更新人（工号/系统）'
)
ENGINE = OLAP
DUPLICATE KEY (campaign_id, created_at)
COMMENT '营销活动主表 | 数据分级=L3 | 活动元信息'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (campaign_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  marketing_campaign               IS '营销活动主表 | 活动元信息';
COMMENT ON COLUMN marketing_campaign.campaign_type IS '活动类型：DISCOUNT/COUPON/FULL_REDUCTION/BUNDLE/FLASH_SALE/GROUP_BUY/POINTS';
COMMENT ON COLUMN marketing_campaign.budget_amount IS '活动预算金额（L3 财务敏感）';
COMMENT ON COLUMN marketing_campaign.actual_cost   IS '实际投入金额（L3 财务敏感）';

-- -----------------------------------------------------------------------------
-- 5. marketing_roi : 营销 ROI 分析表
--    业务含义：营销活动投入产出比分析（ROI = (产出 - 投入) / 投入）
--    数据分级：L3 (投入/产出金额 财务敏感)
--    分区策略：按 stat_date 日期动态分区
--    外键关系：campaign_id -> marketing_campaign.campaign_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS marketing_roi (
    roi_id                  VARCHAR(64)   NOT NULL        COMMENT 'ROI 记录ID（业务主键）',
    campaign_id             VARCHAR(64)   NOT NULL        COMMENT '活动ID（外键 -> marketing_campaign.campaign_id）',
    stat_date               DATE          NOT NULL        COMMENT '统计日期',
    investment_amount       DECIMAL(18,2) NOT NULL        COMMENT '投入金额（L3 财务敏感）',
    revenue_amount          DECIMAL(18,2) NOT NULL        COMMENT '产出金额（增量 GMV，L3 财务敏感）',
    profit_amount           DECIMAL(18,2)                 COMMENT '利润金额 = 产出 - 投入（L3 财务敏感）',
    roi                     DECIMAL(10,4)                 COMMENT 'ROI = (产出 - 投入) / 投入',
    roas                    DECIMAL(10,4)                 COMMENT 'ROAS = 产出 / 投入（广告支出回报率）',
    cpa                     DECIMAL(18,2)                 COMMENT 'CPA = 投入 / 转化数（每次行动成本，L3 财务敏感）',
    cpc                     DECIMAL(18,2)                 COMMENT 'CPC = 投入 / 点击数（每次点击成本，L3 财务敏感）',
    cpv                     DECIMAL(18,2)                 COMMENT 'CPV = 投入 / 曝光数（每次曝光成本，L3 财务敏感）',
    conversion_count        INT                           COMMENT '转化数',
    click_count             INT                           COMMENT '点击数',
    impression_count        INT                           COMMENT '曝光数',
    ctr                     DECIMAL(8,6)                  COMMENT 'CTR = 点击/曝光（0~1）',
    cvr                     DECIMAL(8,6)                  COMMENT 'CVR = 转化/点击（0~1）',
    payback_period_days     INT                           COMMENT '回本周期（天）',
    is_profitable           BOOLEAN                       COMMENT '是否盈利（ROI > 0）',
    computed_at             DATETIME      NOT NULL        COMMENT '计算时间',
    created_at              DATETIME      NOT NULL        COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (roi_id, stat_date)
COMMENT '营销 ROI 分析表 | 数据分级=L3 | 投入产出比分析 | 外键：campaign_id -> marketing_campaign.campaign_id'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (campaign_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  marketing_roi                  IS '营销 ROI 分析表 | 投入产出比分析';
COMMENT ON COLUMN marketing_roi.investment_amount IS '投入金额（L3 财务敏感）';
COMMENT ON COLUMN marketing_roi.revenue_amount    IS '产出金额（L3 财务敏感）';
COMMENT ON COLUMN marketing_roi.roi              IS 'ROI = (产出 - 投入) / 投入';
COMMENT ON COLUMN marketing_roi.roas             IS 'ROAS = 产出 / 投入';
COMMENT ON COLUMN marketing_roi.cpa              IS 'CPA = 投入 / 转化数';
COMMENT ON COLUMN marketing_roi.is_profitable    IS '是否盈利（ROI > 0）';

-- -----------------------------------------------------------------------------
-- 6. marketing_channel_stat : 营销渠道统计表
--    业务含义：各营销渠道效能统计（APP/短信/微信/邮件/广告渠道）
--    数据分级：L3 (投入/产出金额 财务敏感)
--    分区策略：按 stat_date 日期动态分区
--    外键关系：campaign_id -> marketing_campaign.campaign_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS marketing_channel_stat (
    stat_id                 VARCHAR(64)   NOT NULL        COMMENT '统计记录ID（业务主键）',
    campaign_id             VARCHAR(64)                   COMMENT '活动ID（外键 -> marketing_campaign.campaign_id，全站统计可为 NULL）',
    channel_code            VARCHAR(32)   NOT NULL        COMMENT '渠道编码：APP_PUSH / SMS / WECHAT / EMAIL / DISPLAY_AD / SEARCH_AD / SOCIAL_AD / KOL',
    channel_name            VARCHAR(64)                   COMMENT '渠道名称',
    stat_date               DATE          NOT NULL        COMMENT '统计日期',
    impression_count        INT                           COMMENT '曝光数',
    click_count             INT                           COMMENT '点击数',
    conversion_count        INT                           COMMENT '转化数',
    investment_amount       DECIMAL(18,2)                 COMMENT '渠道投入金额（L3 财务敏感）',
    revenue_amount          DECIMAL(18,2)                 COMMENT '渠道产出金额（L3 财务敏感）',
    ctr                     DECIMAL(8,6)                  COMMENT 'CTR = 点击/曝光（0~1）',
    cvr                     DECIMAL(8,6)                  COMMENT 'CVR = 转化/点击（0~1）',
    cpc                     DECIMAL(18,2)                 COMMENT 'CPC = 投入/点击（L3 财务敏感）',
    cpa                     DECIMAL(18,2)                 COMMENT 'CPA = 投入/转化（L3 财务敏感）',
    roas                    DECIMAL(10,4)                 COMMENT 'ROAS = 产出/投入',
    channel_rank            INT                           COMMENT '渠道效能排名',
    computed_at             DATETIME      NOT NULL        COMMENT '计算时间',
    created_at              DATETIME      NOT NULL        COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_date)
COMMENT '营销渠道统计表 | 数据分级=L3 | 各渠道效能统计'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (channel_code) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-1095',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  marketing_channel_stat                 IS '营销渠道统计表 | 各渠道效能统计';
COMMENT ON COLUMN marketing_channel_stat.channel_code    IS '渠道编码：APP_PUSH/SMS/WECHAT/EMAIL/DISPLAY_AD/SEARCH_AD/SOCIAL_AD/KOL';
COMMENT ON COLUMN marketing_channel_stat.investment_amount IS '渠道投入金额（L3 财务敏感）';
COMMENT ON COLUMN marketing_channel_stat.roas            IS 'ROAS = 产出/投入';
COMMENT ON COLUMN marketing_channel_stat.channel_rank    IS '渠道效能排名';

-- =============================================================================
-- 营销效果域 DDL 完成：6 张表
-- ab_experiment / ab_experiment_variant / conversion_funnel /
-- marketing_campaign / marketing_roi / marketing_channel_stat
-- =============================================================================