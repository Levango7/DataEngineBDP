-- =============================================================================
-- File   : 03_carbon_emission_ddl.sql
-- Domain : 碳排放核算域（Carbon Emission Accounting）
-- Engine : Apache Doris（主）/ Apache Iceberg（备，注释中给出兼容写法）
-- Charset: UTF-8
-- Source : 能源行业模板 碳排放核算业务模型
-- Class  : 数据分级 L3（敏感运营）/ L4（合规披露）
-- Tables : emission_factor_library / emission_source /
--          emission_calculation_result / emission_calculation_model /
--          emission_scope_classification / emission_reduction_target /
--          emission_report（7 张）
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- 业务说明：依据 ISO 14064 / GHG Protocol 进行温室气体排放核算，
--           覆盖 Scope1（直接排放）/Scope2（外购电力间接排放）/Scope3（其他间接排放）
-- =============================================================================
-- 排放量计算公式：E = AD × EF × GWP
--   E   : 排放量（tCO2e）
--   AD  : 活动数据（Activity Data，如燃料消耗量、用电量）
--   EF  : 排放因子（Emission Factor，tCO2/单位活动数据）
--   GWP : 全球变暖潜势（Global Warming Potential，CO2=1, CH4=28, N2O=265）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. emission_factor_library : 排放因子库
--    业务含义：排放因子字典，含默认因子/自定义因子/来源/版本
--    数据分级：L3（敏感运营：核算基准）
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无（被 emission_source / emission_calculation_result 引用）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS emission_factor_library (
    factor_id          VARCHAR(64)   NOT NULL                COMMENT '因子ID（业务主键）',
    factor_code        VARCHAR(64)   NOT NULL                COMMENT '因子编码（唯一）',
    factor_name        VARCHAR(128)  NOT NULL                COMMENT '因子名称',
    gas_type           VARCHAR(16)   NOT NULL                COMMENT '温室气体类型：CO2 / CH4 / N2O / HFCs / PFCs / SF6 / NF3',
    scope              VARCHAR(8)    NOT NULL                COMMENT '排放范围：SCOPE1-直接 / SCOPE2-电力间接 / SCOPE3-其他间接',
    category           VARCHAR(64)   NOT NULL                COMMENT '排放类别：STATIONARY_COMBUSTION-固定燃烧 / MOBILE_COMBUSTION-移动燃烧 / PURCHASED_ELECTRICITY-外购电力 / PURCHASED_HEAT-外购热力 / PURCHASED_STEAM-外购蒸汽 / PROCESS_EMISSION-过程排放 / FUGITIVE_EMISSION-逸散排放',
    fuel_or_activity   VARCHAR(64)                           COMMENT '燃料或活动类型（如 天然气/柴油/无烟煤/外购电力）',
    factor_value       DECIMAL(18,8) NOT NULL                COMMENT '排放因子值（tCO2e/单位活动数据）',
    unit               VARCHAR(32)   NOT NULL                COMMENT '因子单位（如 tCO2/m3 / tCO2/kWh / tCO2/t）',
    activity_unit      VARCHAR(16)                           COMMENT '活动数据单位（如 m3 / kWh / t / GJ）',
    gwp                DECIMAL(10,4) NOT NULL DEFAULT 1.0000 COMMENT '全球变暖潜势（CO2=1, CH4=28, N2O=265）',
    source             VARCHAR(128)                          COMMENT '因子来源（如 IPCC-2006 / 国家发改委-2023 / 企业自测）',
    source_version     VARCHAR(32)                           COMMENT '来源版本',
    applicable_region  VARCHAR(64)                           COMMENT '适用区域（如 CN-全国 / CN-NW-华北电网）',
    effective_from     DATE                                  COMMENT '生效起始日期',
    effective_to       DATE                                  COMMENT '生效结束日期（空表示长期有效）',
    is_custom          BOOLEAN       NOT NULL DEFAULT FALSE  COMMENT '是否自定义因子（FALSE 表示使用官方默认）',
    description        VARCHAR(512)                          COMMENT '因子描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人',
    updated_by         VARCHAR(64)   NOT NULL                COMMENT '更新人'
)
ENGINE = OLAP
UNIQUE KEY (factor_id)
COMMENT '排放因子库 | 数据分级=L3 | 默认因子/自定义因子/来源/版本 | IPCC/GHG Protocol'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (factor_id) BUCKETS 6
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  emission_factor_library                IS '排放因子库 | 数据分级=L3 | 默认因子/自定义因子/来源/版本';
COMMENT ON COLUMN emission_factor_library.gas_type       IS '温室气体类型：CO2/CH4/N2O/HFCs/PFCs/SF6/NF3';
COMMENT ON COLUMN emission_factor_library.scope          IS '排放范围：SCOPE1/SCOPE2/SCOPE3';
COMMENT ON COLUMN emission_factor_library.factor_value   IS '排放因子值（tCO2e/单位活动数据）';
COMMENT ON COLUMN emission_factor_library.gwp            IS '全球变暖潜势（CO2=1, CH4=28, N2O=265）';

-- -----------------------------------------------------------------------------
-- 2. emission_source : 排放源表
--    业务含义：排放源定义，关联设备/工序/部门，配置活动数据来源
--    数据分级：L3（敏感运营：排放源配置）
--    分区策略：按 updated_at 日期动态分区
--    外键关系：factor_id -> emission_factor_library.factor_id
--             device_id -> energy_device.device_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS emission_source (
    source_id          VARCHAR(64)   NOT NULL                COMMENT '排放源ID（业务主键）',
    source_code        VARCHAR(64)   NOT NULL                COMMENT '排放源编码（唯一）',
    source_name        VARCHAR(128)  NOT NULL                COMMENT '排放源名称',
    scope              VARCHAR(8)    NOT NULL                COMMENT '排放范围：SCOPE1/SCOPE2/SCOPE3',
    category           VARCHAR(64)   NOT NULL                COMMENT '排放类别',
    factor_id          VARCHAR(64)   NOT NULL                COMMENT '排放因子ID（外键 -> emission_factor_library.factor_id）',
    device_id          VARCHAR(64)                           COMMENT '关联设备ID（外键 -> energy_device.device_id，可空）',
    location_id        VARCHAR(64)                           COMMENT '位置ID',
    location_name      VARCHAR(128)                          COMMENT '位置名称',
    department         VARCHAR(64)                           COMMENT '所属部门',
    process_name       VARCHAR(128)                          COMMENT '所属工序',
    activity_data_source VARCHAR(32) NOT NULL                COMMENT '活动数据来源：METER_READING-表计读数 / FUEL_PURCHASE-燃料采购 / ELECTRICITY_BILL-电费账单 / MANUAL_INPUT-人工录入 / CALCULATED-计算',
    activity_data_table VARCHAR(128)                         COMMENT '活动数据来源表（如 energy_consumption_summary）',
    activity_data_field VARCHAR(64)                          COMMENT '活动数据来源字段（如 total_consumption）',
    activity_unit      VARCHAR(16)                           COMMENT '活动数据单位',
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE   COMMENT '是否启用',
    description        VARCHAR(512)                          COMMENT '排放源描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人',
    updated_by         VARCHAR(64)   NOT NULL                COMMENT '更新人'
)
ENGINE = OLAP
UNIQUE KEY (source_id)
COMMENT '排放源表 | 数据分级=L3 | 关联设备/工序/部门 | 配置活动数据来源'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (source_id) BUCKETS 6
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  emission_source                       IS '排放源表 | 数据分级=L3 | 关联设备/工序/部门';
COMMENT ON COLUMN emission_source.scope                 IS '排放范围：SCOPE1/SCOPE2/SCOPE3';
COMMENT ON COLUMN emission_source.activity_data_source  IS '活动数据来源：METER_READING/FUEL_PURCHASE/ELECTRICITY_BILL/MANUAL_INPUT/CALCULATED';

-- -----------------------------------------------------------------------------
-- 3. emission_calculation_result : 排放核算结果表
--    业务含义：排放核算结果，含活动数据/排放量/折 CO2 当量
--    数据分级：L4（合规披露：核算结果）
--    分区策略：按 stat_date 日期动态分区
--    外键关系：source_id -> emission_source.source_id
--             factor_id -> emission_factor_library.factor_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS emission_calculation_result (
    result_id          VARCHAR(64)   NOT NULL                COMMENT '结果ID（业务主键）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期',
    stat_period        VARCHAR(8)    NOT NULL                COMMENT '统计周期：MONTH/QUARTER/YEAR',
    source_id          VARCHAR(64)   NOT NULL                COMMENT '排放源ID（外键 -> emission_source.source_id）',
    source_name        VARCHAR(128)                          COMMENT '排放源名称（冗余）',
    scope              VARCHAR(8)    NOT NULL                COMMENT '排放范围：SCOPE1/SCOPE2/SCOPE3',
    category           VARCHAR(64)   NOT NULL                COMMENT '排放类别',
    gas_type           VARCHAR(16)   NOT NULL                COMMENT '温室气体类型',
    factor_id          VARCHAR(64)   NOT NULL                COMMENT '排放因子ID（冗余）',
    activity_data      DECIMAL(18,4) NOT NULL                COMMENT '活动数据（消耗量）',
    activity_unit      VARCHAR(16)                           COMMENT '活动数据单位',
    factor_value       DECIMAL(18,8) NOT NULL                COMMENT '排放因子值（冗余）',
    gwp                DECIMAL(10,4) NOT NULL                COMMENT '全球变暖潜势（冗余）',
    emission_amount    DECIMAL(18,6) NOT NULL                COMMENT '排放量（tCO2e）= 活动数据 × 因子 × GWP',
    emission_amount_pure DECIMAL(18,6)                       COMMENT '纯排放量（t，未折 CO2 当量）',
    calculation_method VARCHAR(32)                           COMMENT '核算方法：OPERATIONAL-运营控制 / EQUITY-股权比例 / FINANCIAL-财务控制',
    ownership_ratio    DECIMAL(6,4)           DEFAULT 1.0000  COMMENT '持股比例（股权法时使用）',
    remark             VARCHAR(512)                          COMMENT '备注',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (result_id, stat_date)
COMMENT '排放核算结果表 | 数据分级=L4 | 活动数据×因子×GWP=排放量 | 月/季/年'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (result_id) BUCKETS 10
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  emission_calculation_result              IS '排放核算结果表 | 数据分级=L4 | 活动数据×因子×GWP=排放量';
COMMENT ON COLUMN emission_calculation_result.emission_amount IS '排放量（tCO2e）= 活动数据 × 因子 × GWP';
COMMENT ON COLUMN emission_calculation_result.calculation_method IS '核算方法：OPERATIONAL/EQUITY/FINANCIAL';

-- -----------------------------------------------------------------------------
-- 4. emission_calculation_model : 核算模型表
--    业务含义：核算模型定义，含合并方法/边界/基准年
--    数据分级：L3（敏感运营：核算模型）
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS emission_calculation_model (
    model_id           VARCHAR(64)   NOT NULL                COMMENT '模型ID（业务主键）',
    model_code         VARCHAR(64)   NOT NULL                COMMENT '模型编码（唯一）',
    model_name         VARCHAR(128)  NOT NULL                COMMENT '模型名称',
    standard           VARCHAR(32)   NOT NULL                COMMENT '核算标准：ISO_14064-1 / GHG_PROTOCOL_CORPORATE / GHG_PROTOCOL_SCOPE3 / CDP / 国家发改委行业指南',
    consolidation_method VARCHAR(32) NOT NULL                COMMENT '合并方法：OPERATIONAL_CONTROL-运营控制 / EQUITY_SHARE-股权比例 / FINANCIAL_CONTROL-财务控制',
    organizational_boundary VARCHAR(512)                     COMMENT '组织边界描述',
    operational_boundary VARCHAR(512)                        COMMENT '运营边界描述',
    base_year          INT                                   COMMENT '基准年（如 2020）',
    base_year_emission DECIMAL(18,6)                         COMMENT '基准年排放量（tCO2e）',
    reporting_period   VARCHAR(16)                           COMMENT '报告周期：MONTHLY/QUARTERLY/ANNUALLY',
    included_scopes    VARCHAR(32)    NOT NULL DEFAULT '1,2' COMMENT '纳入核算的 Scope（逗号分隔，如 1,2,3）',
    included_gases     VARCHAR(64)   NOT NULL DEFAULT 'CO2,CH4,N2O' COMMENT '纳入核算的温室气体（逗号分隔）',
    version            VARCHAR(16)   NOT NULL DEFAULT '1.0'  COMMENT '模型版本',
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE   COMMENT '是否启用',
    description        VARCHAR(512)                          COMMENT '模型描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人',
    updated_by         VARCHAR(64)   NOT NULL                COMMENT '更新人'
)
ENGINE = OLAP
UNIQUE KEY (model_id)
COMMENT '核算模型表 | 数据分级=L3 | 合并方法/边界/基准年 | ISO 14064/GHG Protocol'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (model_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  emission_calculation_model                   IS '核算模型表 | 数据分级=L3 | 合并方法/边界/基准年';
COMMENT ON COLUMN emission_calculation_model.standard           IS '核算标准：ISO_14064-1/GHG_PROTOCOL_CORPORATE/CDP/国家发改委行业指南';
COMMENT ON COLUMN emission_calculation_model.consolidation_method IS '合并方法：OPERATIONAL_CONTROL/EQUITY_SHARE/FINANCIAL_CONTROL';

-- -----------------------------------------------------------------------------
-- 5. emission_scope_classification : 排放范围分类表
--    业务含义：排放按 Scope1/2/3 分类的汇总，供看板与报告
--    数据分级：L4（合规披露：分类汇总）
--    分区策略：按 stat_date 日期动态分区
--    外键关系：无（聚合自 emission_calculation_result）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS emission_scope_classification (
    classification_id  VARCHAR(64)   NOT NULL                COMMENT '分类ID（业务主键）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期',
    stat_period        VARCHAR(8)    NOT NULL                COMMENT '统计周期：MONTH/QUARTER/YEAR',
    scope              VARCHAR(8)    NOT NULL                COMMENT '排放范围：SCOPE1/SCOPE2/SCOPE3',
    scope_name         VARCHAR(32)   NOT NULL                COMMENT '范围名称：直接排放/电力间接排放/其他间接排放',
    category           VARCHAR(64)                           COMMENT '排放类别（可空，空表示 Scope 汇总）',
    total_emission     DECIMAL(18,6) NOT NULL                COMMENT '总排放量（tCO2e）',
    emission_ratio     DECIMAL(8,4)                          COMMENT '排放占比（在该 Scope 或总量中的比例，小数）',
    source_count       INT                                   COMMENT '排放源数量',
    yoy_growth_rate    DECIMAL(8,4)                          COMMENT '同比增长率（小数）',
    base_year_ratio    DECIMAL(8,4)                          COMMENT '相对基准年减排比例（小数，正数表示减排）',
    remark             VARCHAR(256)                          COMMENT '备注',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
UNIQUE KEY (classification_id, stat_date)
COMMENT '排放范围分类表 | 数据分级=L4 | Scope1/2/3 分类汇总 | 含占比与基准年对比'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (classification_id) BUCKETS 6
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  emission_scope_classification            IS '排放范围分类表 | 数据分级=L4 | Scope1/2/3 分类汇总';
COMMENT ON COLUMN emission_scope_classification.scope      IS '排放范围：SCOPE1/SCOPE2/SCOPE3';
COMMENT ON COLUMN emission_scope_classification.base_year_ratio IS '相对基准年减排比例（小数，正数表示减排）';

-- -----------------------------------------------------------------------------
-- 6. emission_reduction_target : 减排目标表
--    业务含义：减排目标定义，含基准年/目标年/减排比例/路径
--    数据分级：L3（敏感运营：减排目标）
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS emission_reduction_target (
    target_id          VARCHAR(64)   NOT NULL                COMMENT '目标ID（业务主键）',
    target_name        VARCHAR(128)  NOT NULL                COMMENT '目标名称',
    target_code        VARCHAR(64)   NOT NULL                COMMENT '目标编码（唯一）',
    scope              VARCHAR(8)                            COMMENT '排放范围（空表示全部 Scope）',
    base_year          INT           NOT NULL                COMMENT '基准年',
    base_year_emission DECIMAL(18,6) NOT NULL                COMMENT '基准年排放量（tCO2e）',
    target_year        INT           NOT NULL                COMMENT '目标年',
    reduction_ratio    DECIMAL(8,4)  NOT NULL                COMMENT '减排比例（小数，0.3 表示减排 30%）',
    target_emission    DECIMAL(18,6)                         COMMENT '目标排放量（tCO2e）= 基准年排放量 × (1 - 减排比例）',
    reduction_path     VARCHAR(32)   NOT NULL                COMMENT '减排路径：ENERGY_EFFICIENCY-能效提升 / RENEWABLE_ENERGY-可再生能源 / FUEL_SWITCH-燃料替代 / PROCESS_OPTIMIZATION-工艺优化 / CCUS-碳捕集 / OFFSET-抵消',
    annual_reduction   DECIMAL(18,6)                         COMMENT '年均减排量（tCO2e/年）',
    progress           DECIMAL(8,4)                          COMMENT '当前进度（小数，已完成减排比例）',
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-进行中 / ACHIEVED-已达成 / AT_RISK-有风险 / EXCEEDED-已超额',
    description        VARCHAR(512)                          COMMENT '目标描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人',
    updated_by         VARCHAR(64)   NOT NULL                COMMENT '更新人'
)
ENGINE = OLAP
UNIQUE KEY (target_id)
COMMENT '减排目标表 | 数据分级=L3 | 基准年/目标年/减排比例/路径 | 进度跟踪'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (target_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  emission_reduction_target                IS '减排目标表 | 数据分级=L3 | 基准年/目标年/减排比例/路径';
COMMENT ON COLUMN emission_reduction_target.reduction_path IS '减排路径：ENERGY_EFFICIENCY/RENEWABLE_ENERGY/FUEL_SWITCH/PROCESS_OPTIMIZATION/CCUS/OFFSET';
COMMENT ON COLUMN emission_reduction_target.target_emission IS '目标排放量（tCO2e）= 基准年排放量 × (1 - 减排比例）';

-- -----------------------------------------------------------------------------
-- 7. emission_report : 核算报告表
--    业务含义：核算报告元数据，关联核算结果与模型
--    数据分级：L4（合规披露：报告）
--    分区策略：按 report_period 日期动态分区
--    外键关系：model_id -> emission_calculation_model.model_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS emission_report (
    report_id          VARCHAR(64)   NOT NULL                COMMENT '报告ID（业务主键）',
    report_name        VARCHAR(128)  NOT NULL                COMMENT '报告名称',
    report_code        VARCHAR(64)   NOT NULL                COMMENT '报告编码（唯一）',
    model_id           VARCHAR(64)   NOT NULL                COMMENT '核算模型ID（外键 -> emission_calculation_model.model_id）',
    report_period      VARCHAR(8)    NOT NULL                COMMENT '报告周期：MONTH/QUARTER/YEAR',
    period_start       DATE          NOT NULL                COMMENT '周期起始日期',
    period_end         DATE          NOT NULL                COMMENT '周期结束日期',
    total_emission     DECIMAL(18,6) NOT NULL                COMMENT '总排放量（tCO2e）',
    scope1_emission    DECIMAL(18,6)                         COMMENT 'Scope1 排放量（tCO2e）',
    scope2_emission    DECIMAL(18,6)                         COMMENT 'Scope2 排放量（tCO2e）',
    scope3_emission    DECIMAL(18,6)                         COMMENT 'Scope3 排放量（tCO2e）',
    carbon_intensity   DECIMAL(18,6)                         COMMENT '碳强度（tCO2e/单位产品或营收）',
    intensity_unit     VARCHAR(32)                           COMMENT '碳强度单位',
    status             VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT '报告状态：DRAFT-草稿 / SUBMITTED-已提交 / APPROVED-已审批 / PUBLISHED-已发布',
    approved_by        VARCHAR(64)                           COMMENT '审批人',
    approved_at        DATETIME                             COMMENT '审批时间',
    file_url           VARCHAR(512)                          COMMENT '报告文件 URL（PDF/Excel）',
    remark             VARCHAR(512)                          COMMENT '备注',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
UNIQUE KEY (report_id)
COMMENT '核算报告表 | 数据分级=L4 | 报告元数据/总排放/碳强度/审批状态'
PARTITION BY RANGE (period_start) ()
DISTRIBUTED BY HASH (report_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  emission_report                  IS '核算报告表 | 数据分级=L4 | 报告元数据/总排放/碳强度/审批状态';
COMMENT ON COLUMN emission_report.total_emission   IS '总排放量（tCO2e）';
COMMENT ON COLUMN emission_report.carbon_intensity IS '碳强度（tCO2e/单位产品或营收）';
COMMENT ON COLUMN emission_report.status           IS '报告状态：DRAFT/SUBMITTED/APPROVED/PUBLISHED';

-- =============================================================================
-- 碳排放核算域 DDL 完成：7 张表
--   emission_factor_library / emission_source /
--   emission_calculation_result / emission_calculation_model /
--   emission_scope_classification / emission_reduction_target /
--   emission_report
-- 排放量计算公式：E = AD × EF × GWP
-- =============================================================================