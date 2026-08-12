-- =============================================================================
-- File   : 02_economic_operation_ddl.sql
-- Domain : 经济运行域（Economic Operation）
-- Engine : Apache Doris（主）/ Apache Iceberg（备）
-- Charset: UTF-8
-- Source : 政务行业模板 经济运行业务模型
-- Class  : 数据分级 L2(内部业务) / L3(敏感运营)
-- Tables : gdp / industry_structure / fixed_asset_investment /
--          social_retail_consumption / foreign_trade / fiscal_revenue /
--          fiscal_expenditure / economic_indicator (8 张)
-- Notice : Doris 不强制外键，关联关系以注释说明
-- 合规   : 经济数据按政务数据分级分类，财政敏感数据 L3
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. gdp : GDP 核算表
--    业务含义：按年度/季度核算 GDP，含生产法/支出法/收入法三种方法
--    数据分级：L2（内部业务：宏观经济指标）
--    分区策略：按 stat_year 年度分区
--    GDP 公式：生产法 = Σ各行业增加值；支出法 = 消费+投资+净出口；收入法 = 劳动报酬+生产税净+固定资产折旧+营业盈余
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gdp (
    gdp_id             VARCHAR(64)   NOT NULL                COMMENT 'GDP 记录ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    stat_quarter       VARCHAR(8)                            COMMENT '统计季度：Q1/Q2/Q3/Q4/ANNUAL',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)                           COMMENT '市（NULL 表示省级）',
    district           VARCHAR(64)                           COMMENT '区县（NULL 表示市级）',
    gdp_value          DECIMAL(18,2) NOT NULL                COMMENT 'GDP 总值（亿元）',
    gdp_growth_rate    DECIMAL(8,4)                          COMMENT 'GDP 同比增长率',
    gdp_per_capita     DECIMAL(18,2)                         COMMENT '人均 GDP（元）',
    calculation_method VARCHAR(16)   NOT NULL                COMMENT '核算方法：PRODUCTION-生产法 / EXPENDITURE-支出法 / INCOME-收入法',
    primary_industry_value  DECIMAL(18,2)                    COMMENT '第一产业增加值（亿元）',
    secondary_industry_value DECIMAL(18,2)                   COMMENT '第二产业增加值（亿元）',
    tertiary_industry_value  DECIMAL(18,2)                   COMMENT '第三产业增加值（亿元）',
    primary_ratio      DECIMAL(6,4)                          COMMENT '第一产业占比',
    secondary_ratio    DECIMAL(6,4)                          COMMENT '第二产业占比',
    tertiary_ratio     DECIMAL(6,4)                          COMMENT '第三产业占比',
    final_consumption  DECIMAL(18,2)                         COMMENT '最终消费支出（支出法，亿元）',
    capital_formation  DECIMAL(18,2)                         COMMENT '资本形成总额（支出法，亿元）',
    net_export         DECIMAL(18,2)                         COMMENT '货物和服务净出口（支出法，亿元）',
    labor_compensation DECIMAL(18,2)                         COMMENT '劳动者报酬（收入法，亿元）',
    net_production_tax DECIMAL(18,2)                         COMMENT '生产税净额（收入法，亿元）',
    depreciation       DECIMAL(18,2)                         COMMENT '固定资产折旧（收入法，亿元）',
    operating_surplus  DECIMAL(18,2)                         COMMENT '营业盈余（收入法，亿元）',
    is_preliminary     BOOLEAN                               COMMENT '是否初步核算值：true-初步 / false-核实',
    data_source        VARCHAR(32)   NOT NULL                COMMENT '数据来源：NBS-统计局 / LOCAL-地方 / ESTIMATE-估算',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (gdp_id, stat_year)
COMMENT 'GDP 核算表 | 数据分级=L2 | 生产法/支出法/收入法三法核算 | 三次产业增加值'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (gdp_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  gdp                              IS 'GDP 核算表 | 数据分级=L2 | 生产法/支出法/收入法';
COMMENT ON COLUMN gdp.calculation_method          IS '核算方法：PRODUCTION/EXPENDITURE/INCOME';
COMMENT ON COLUMN gdp.primary_industry_value      IS '第一产业增加值（亿元）';
COMMENT ON COLUMN gdp.final_consumption           IS '最终消费支出（支出法，亿元）';
COMMENT ON COLUMN gdp.labor_compensation          IS '劳动者报酬（收入法，亿元）';

-- -----------------------------------------------------------------------------
-- 2. industry_structure : 产业结构表
--    业务含义：按行业大类汇总增加值/从业人数/企业数，用于行业贡献度分析
--    数据分级：L2（内部业务：行业统计）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS industry_structure (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)                           COMMENT '市',
    industry_category  VARCHAR(16)   NOT NULL                COMMENT '产业类别：PRIMARY-第一产业 / SECONDARY-第二产业 / TERTIARY-第三产业',
    industry_code      VARCHAR(16)   NOT NULL                COMMENT '行业代码（国标行业分类，如 A-农林牧渔 / B-采矿 / C-制造）',
    industry_name      VARCHAR(64)   NOT NULL                COMMENT '行业名称',
    added_value        DECIMAL(18,2)                         COMMENT '增加值（亿元）',
    value_ratio        DECIMAL(6,4)                          COMMENT '增加值占 GDP 比重',
    employee_count     BIGINT                                COMMENT '从业人数',
    enterprise_count   BIGINT                                COMMENT '企业数',
    avg_wage           DECIMAL(18,2)                         COMMENT '行业平均工资（元/年）',
    growth_rate        DECIMAL(8,4)                          COMMENT '行业增加值同比增长率',
    contribution_rate  DECIMAL(6,4)                          COMMENT '对 GDP 增长贡献率',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '产业结构表 | 数据分级=L2 | 按行业汇总增加值/从业/企业/工资/贡献度'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  industry_structure                       IS '产业结构表 | 数据分级=L2';
COMMENT ON COLUMN industry_structure.industry_category     IS '产业类别：PRIMARY/SECONDARY/TERTIARY';
COMMENT ON COLUMN industry_structure.contribution_rate     IS '对 GDP 增长贡献率';

-- -----------------------------------------------------------------------------
-- 3. fixed_asset_investment : 固定资产投资表
--    业务含义：按区县/年度/投资类型汇总固定资产投资额
--    数据分级：L2（内部业务：投资统计）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fixed_asset_investment (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    stat_month         INT                                   COMMENT '统计月份（NULL 表示年度汇总）',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)                           COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县',
    investment_type    VARCHAR(32)   NOT NULL                COMMENT '投资类型：INFRASTRUCTURE-基础设施 / MANUFACTURING-制造业 / REAL_ESTATE-房地产 / OTHER-其他',
    investment_sector  VARCHAR(64)                           COMMENT '投资行业（国标行业分类）',
    investment_amount  DECIMAL(18,2) NOT NULL                COMMENT '投资完成额（亿元）',
    growth_rate        DECIMAL(8,4)                          COMMENT '同比增长率',
    private_investment DECIMAL(18,2)                         COMMENT '民间投资额（亿元）',
    public_investment  DECIMAL(18,2)                         COMMENT '国有投资额（亿元）',
    foreign_investment DECIMAL(18,2)                         COMMENT '外商投资额（亿元）',
    project_count      BIGINT                                COMMENT '在建项目数',
    new_project_count  BIGINT                                COMMENT '新开工项目数',
    completed_project_count BIGINT                           COMMENT '竣工项目数',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '固定资产投资表 | 数据分级=L2 | 按投资类型/行业汇总，含民间/国有/外商投资'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  fixed_asset_investment                       IS '固定资产投资表 | 数据分级=L2';
COMMENT ON COLUMN fixed_asset_investment.investment_type       IS '投资类型：INFRASTRUCTURE/MANUFACTURING/REAL_ESTATE/OTHER';

-- -----------------------------------------------------------------------------
-- 4. social_retail_consumption : 社会消费品零售表
--    业务含义：按区县/年度/月度/消费类型汇总社会消费品零售额
--    数据分级：L2（内部业务：消费统计）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_retail_consumption (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    stat_month         INT                                   COMMENT '统计月份（NULL 表示年度汇总）',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)                           COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县',
    consumption_type   VARCHAR(32)   NOT NULL                COMMENT '消费类型：GOODS-商品零售 / CATERING-餐饮 / SERVICE-服务消费',
    retail_category    VARCHAR(64)                           COMMENT '零售类别（如 粮油/服装/家电/汽车）',
    retail_amount      DECIMAL(18,2) NOT NULL                COMMENT '零售额（亿元）',
    growth_rate        DECIMAL(8,4)                          COMMENT '同比增长率',
    urban_retail       DECIMAL(18,2)                         COMMENT '城镇零售额（亿元）',
    rural_retail       DECIMAL(18,2)                         COMMENT '乡村零售额（亿元）',
    online_retail      DECIMAL(18,2)                         COMMENT '网上零售额（亿元）',
    offline_retail     DECIMAL(18,2)                         COMMENT '实体店零售额（亿元）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '社会消费品零售表 | 数据分级=L2 | 按消费类型/类别汇总，含城乡/线上线下'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  social_retail_consumption                       IS '社会消费品零售表 | 数据分级=L2';
COMMENT ON COLUMN social_retail_consumption.consumption_type      IS '消费类型：GOODS/CATERING/SERVICE';

-- -----------------------------------------------------------------------------
-- 5. foreign_trade : 外贸进出口表
--    业务含义：按区县/年度/月度/贸易方式汇总进出口额
--    数据分级：L2（内部业务：贸易统计）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS foreign_trade (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    stat_month         INT                                   COMMENT '统计月份（NULL 表示年度汇总）',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)                           COMMENT '市',
    trade_type         VARCHAR(32)   NOT NULL                COMMENT '贸易方式：GENERAL-一般贸易 / PROCESSING-加工贸易 / BONDED-保税 / OTHER-其他',
    trade_direction    VARCHAR(16)   NOT NULL                COMMENT '贸易方向：EXPORT-出口 / IMPORT-进口',
    trade_amount       DECIMAL(18,2) NOT NULL                COMMENT '进出口额（亿元）',
    growth_rate        DECIMAL(8,4)                          COMMENT '同比增长率',
    trade_partner      VARCHAR(64)                           COMMENT '贸易伙伴国/地区',
    commodity_category VARCHAR(64)                           COMMENT '商品类别（HS 编码大类）',
    enterprise_count   BIGINT                                COMMENT '进出口企业数',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '外贸进出口表 | 数据分级=L2 | 按贸易方式/方向/伙伴汇总'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  foreign_trade                       IS '外贸进出口表 | 数据分级=L2';
COMMENT ON COLUMN foreign_trade.trade_type            IS '贸易方式：GENERAL/PROCESSING/BONDED/OTHER';
COMMENT ON COLUMN foreign_trade.trade_direction       IS '贸易方向：EXPORT/IMPORT';

-- -----------------------------------------------------------------------------
-- 6. fiscal_revenue : 财政收入表
--    业务含义：按区县/年度/月度/收入类型汇总财政收入
--    数据分级：L3（敏感运营：财政数据）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fiscal_revenue (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    stat_month         INT                                   COMMENT '统计月份（NULL 表示年度汇总）',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)                           COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县',
    revenue_type       VARCHAR(32)   NOT NULL                COMMENT '收入类型：TAX-税收 / NON_TAX-非税 / FUND-政府性基金',
    tax_category       VARCHAR(64)                           COMMENT '税种（如 增值税/企业所得税/个人所得税/消费税）',
    revenue_amount     DECIMAL(18,2) NOT NULL                COMMENT '收入金额（亿元）',
    growth_rate        DECIMAL(8,4)                          COMMENT '同比增长率',
    budget_revenue     DECIMAL(18,2)                         COMMENT '一般公共预算收入（亿元）',
    fund_revenue       DECIMAL(18,2)                         COMMENT '政府性基金收入（亿元）',
    state_tax_revenue  DECIMAL(18,2)                         COMMENT '中央税收入（亿元）',
    local_tax_revenue  DECIMAL(18,2)                         COMMENT '地方税收入（亿元）',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L3'    COMMENT '数据分级：L3（敏感运营）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '财政收入表 | 数据分级=L3 | 按收入类型/税种汇总，含中央/地方'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  fiscal_revenue                       IS '财政收入表 | 数据分级=L3 | 敏感运营数据';
COMMENT ON COLUMN fiscal_revenue.revenue_type         IS '收入类型：TAX/NON_TAX/FUND';
COMMENT ON COLUMN fiscal_revenue.data_classification  IS '数据分级：L3（敏感运营）';

-- -----------------------------------------------------------------------------
-- 7. fiscal_expenditure : 财政支出表
--    业务含义：按区县/年度/月度/支出类型汇总财政支出
--    数据分级：L3（敏感运营：财政数据）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fiscal_expenditure (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    stat_month         INT                                   COMMENT '统计月份（NULL 表示年度汇总）',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)                           COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县',
    expenditure_type   VARCHAR(32)   NOT NULL                COMMENT '支出类型：GENERAL-一般公共预算 / FUND-政府性基金 / STATE_OWNED-国有资本经营',
    function_category  VARCHAR(64)                           COMMENT '功能分类（如 教育支出/社会保障/医疗卫生/农林水）',
    expenditure_amount DECIMAL(18,2) NOT NULL                COMMENT '支出金额（亿元）',
    growth_rate        DECIMAL(8,4)                          COMMENT '同比增长率',
    budget_expenditure DECIMAL(18,2)                         COMMENT '一般公共预算支出（亿元）',
    fund_expenditure   DECIMAL(18,2)                         COMMENT '政府性基金支出（亿元）',
    personnel_expense  DECIMAL(18,2)                         COMMENT '人员经费支出（亿元）',
    public_expense     DECIMAL(18,2)                         COMMENT '公用经费支出（亿元）',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L3'    COMMENT '数据分级：L3（敏感运营）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '财政支出表 | 数据分级=L3 | 按支出类型/功能分类汇总'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  fiscal_expenditure                       IS '财政支出表 | 数据分级=L3 | 敏感运营数据';
COMMENT ON COLUMN fiscal_expenditure.expenditure_type     IS '支出类型：GENERAL/FUND/STATE_OWNED';

-- -----------------------------------------------------------------------------
-- 8. economic_indicator : 经济综合指标表
--    业务含义：汇总各类经济综合指标，如 CPI/PPI/PMI/失业率/用电量/货运量
--    数据分级：L2（内部业务：综合指标）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS economic_indicator (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    stat_month         INT                                   COMMENT '统计月份（NULL 表示年度）',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)                           COMMENT '市',
    indicator_code     VARCHAR(32)   NOT NULL                COMMENT '指标代码（如 CPI/PPI/PMI/UNEMPLOYMENT_RATE/ELECTRICITY/FREIGHT）',
    indicator_name     VARCHAR(64)   NOT NULL                COMMENT '指标名称',
    indicator_value    DECIMAL(18,4) NOT NULL                COMMENT '指标值',
    indicator_unit     VARCHAR(16)   NOT NULL                COMMENT '指标单位（如 %/亿元/亿千瓦时/万吨）',
    growth_rate        DECIMAL(8,4)                          COMMENT '同比增长率',
    mom_growth_rate    DECIMAL(8,4)                          COMMENT '环比增长率',
    is_preliminary     BOOLEAN                               COMMENT '是否初步数据',
    data_source        VARCHAR(32)   NOT NULL                COMMENT '数据来源',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '经济综合指标表 | 数据分级=L2 | CPI/PPI/PMI/失业率/用电量/货运量等'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  economic_indicator                  IS '经济综合指标表 | 数据分级=L2';
COMMENT ON COLUMN economic_indicator.indicator_code   IS '指标代码：CPI/PPI/PMI/UNEMPLOYMENT_RATE/ELECTRICITY/FREIGHT';

-- =============================================================================
-- End of 02_economic_operation_ddl.sql
-- =============================================================================