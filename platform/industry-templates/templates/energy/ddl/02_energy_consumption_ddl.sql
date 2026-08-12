-- =============================================================================
-- File   : 02_energy_consumption_ddl.sql
-- Domain : 用能分析域（Energy Consumption Analysis）
-- Engine : Apache Doris（主）/ Apache Iceberg（备，注释中给出兼容写法）
-- Charset: UTF-8
-- Source : 能源行业模板 用能分析业务模型
-- Class  : 数据分级 L2（内部业务）/ L3（敏感运营）
-- Tables : energy_consumption_detail / energy_consumption_summary /
--          energy_dimension_compare / energy_trend_data / energy_quota /
--          energy_balance / energy_cost_analysis（7 张）
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- 业务说明：覆盖电/水/气/汽/冷/热等多类型能源的明细采集、多维聚合、
--           定额管理、能源平衡与成本分析
-- =============================================================================
-- 同比增长率 = (本期值 - 同期值) / 同期值 × 100%
-- 环比增长率 = (本期值 - 上期值) / 上期值 × 100%
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. energy_consumption_detail : 能耗明细表
--    业务含义：设备级/小时级能耗明细，由 Flink 流作业从设备读数差分计算
--    数据分级：L2（内部业务：能耗明细）
--    分区策略：按 stat_time 日期动态分区
--    外键关系：device_id -> energy_device.device_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_consumption_detail (
    detail_id          VARCHAR(64)   NOT NULL                COMMENT '明细ID（业务主键）',
    device_id          VARCHAR(64)   NOT NULL                COMMENT '设备ID（外键 -> energy_device.device_id）',
    device_code        VARCHAR(64)   NOT NULL                COMMENT '设备编码（冗余）',
    measure_medium     VARCHAR(16)   NOT NULL                COMMENT '计量介质：ELECTRIC/WATER/GAS/STEAM/AIR/COLD/HEAT',
    location_id        VARCHAR(64)                           COMMENT '位置ID',
    location_name      VARCHAR(128)                          COMMENT '位置名称',
    department         VARCHAR(64)                           COMMENT '所属部门',
    stat_time          DATETIME      NOT NULL                COMMENT '统计时间（小时级整点）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期（冗余，便于查询）',
    start_reading      DECIMAL(18,4)                         COMMENT '起始读数',
    end_reading        DECIMAL(18,4)                         COMMENT '结束读数',
    consumption        DECIMAL(18,4) NOT NULL                COMMENT '消耗量（结束读数-起始读数）',
    unit               VARCHAR(16)   NOT NULL                COMMENT '计量单位：kWh / m3 / t / GJ',
    peak_value         DECIMAL(18,4)                         COMMENT '区间峰值',
    valley_value       DECIMAL(18,4)                         COMMENT '区间谷值',
    average_value      DECIMAL(18,4)                         COMMENT '区间均值',
    data_quality       VARCHAR(16)   NOT NULL DEFAULT 'GOOD'  COMMENT '数据质量：GOOD/SUSPECT/BAD',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (detail_id, stat_time)
COMMENT '能耗明细表 | 数据分级=L2 | 设备级/小时级能耗 | 由 Flink 流作业从设备读数差分计算'
PARTITION BY RANGE (stat_time) ()
DISTRIBUTED BY HASH (device_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-365',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_consumption_detail                    IS '能耗明细表 | 数据分级=L2 | 设备级/小时级能耗';
COMMENT ON COLUMN energy_consumption_detail.measure_medium     IS '计量介质：ELECTRIC/WATER/GAS/STEAM/AIR/COLD/HEAT';
COMMENT ON COLUMN energy_consumption_detail.consumption        IS '消耗量（结束读数-起始读数）';
COMMENT ON COLUMN energy_consumption_detail.unit               IS '计量单位：kWh/m3/t/GJ';

-- -----------------------------------------------------------------------------
-- 2. energy_consumption_summary : 能耗汇总表
--    业务含义：多维度能耗汇总（日/周/月/季/年），含同比环比
--    数据分级：L2（内部业务：能耗汇总）
--    分区策略：按 stat_date 日期动态分区
--    外键关系：无（聚合自 energy_consumption_detail）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_consumption_summary (
    summary_id         VARCHAR(64)   NOT NULL                COMMENT '汇总ID（业务主键）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期',
    stat_period        VARCHAR(8)    NOT NULL                COMMENT '统计周期：HOUR-时 / DAY-日 / WEEK-周 / MONTH-月 / QUARTER-季 / YEAR-年',
    measure_medium     VARCHAR(16)   NOT NULL                COMMENT '计量介质',
    dimension_type     VARCHAR(16)   NOT NULL                COMMENT '维度类型：DEVICE-设备 / LOCATION-位置 / DEPARTMENT-部门 / COMPANY-公司 / PROCESS-工序',
    dimension_id       VARCHAR(64)                           COMMENT '维度ID',
    dimension_name     VARCHAR(128)                          COMMENT '维度名称',
    total_consumption  DECIMAL(18,4) NOT NULL                COMMENT '总消耗量',
    unit               VARCHAR(16)   NOT NULL                COMMENT '计量单位',
    standard_coal      DECIMAL(18,4)                         COMMENT '折标煤量（kgce，按标准煤系数折算）',
    same_period_last   DECIMAL(18,4)                         COMMENT '同期值（用于同比）',
    last_period        DECIMAL(18,4)                         COMMENT '上期值（用于环比）',
    yoy_growth_rate    DECIMAL(8,4)                          COMMENT '同比增长率（小数，0.1 表示 10%）',
    mom_growth_rate    DECIMAL(8,4)                          COMMENT '环比增长率（小数）',
    peak_consumption   DECIMAL(18,4)                         COMMENT '峰值能耗',
    valley_consumption DECIMAL(18,4)                         COMMENT '谷值能耗',
    average_consumption DECIMAL(18,4)                        COMMENT '平均能耗',
    record_count       INT                                   COMMENT '明细记录数',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
UNIQUE KEY (summary_id, stat_date)
COMMENT '能耗汇总表 | 数据分级=L2 | 多维度/多周期汇总 | 含同比环比与折标煤'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (summary_id) BUCKETS 10
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_consumption_summary                  IS '能耗汇总表 | 数据分级=L2 | 多维度/多周期汇总';
COMMENT ON COLUMN energy_consumption_summary.stat_period      IS '统计周期：HOUR/DAY/WEEK/MONTH/QUARTER/YEAR';
COMMENT ON COLUMN energy_consumption_summary.dimension_type   IS '维度类型：DEVICE/LOCATION/DEPARTMENT/COMPANY/PROCESS';
COMMENT ON COLUMN energy_consumption_summary.standard_coal    IS '折标煤量（kgce）';
COMMENT ON COLUMN energy_consumption_summary.yoy_growth_rate  IS '同比增长率（小数，0.1 表示 10%）';
COMMENT ON COLUMN energy_consumption_summary.mom_growth_rate  IS '环比增长率（小数）';

-- -----------------------------------------------------------------------------
-- 3. energy_dimension_compare : 多维对比分析表
--    业务含义：跨维度能耗对比（如：不同部门/不同位置/不同介质对比）
--    数据分级：L2（内部业务：对比分析）
--    分区策略：按 stat_date 日期动态分区
--    外键关系：无（聚合自 energy_consumption_summary）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_dimension_compare (
    compare_id         VARCHAR(64)   NOT NULL                COMMENT '对比ID（业务主键）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期',
    stat_period        VARCHAR(8)    NOT NULL                COMMENT '统计周期：DAY/MONTH/QUARTER/YEAR',
    compare_type       VARCHAR(16)   NOT NULL                COMMENT '对比类型：CROSS_DEPARTMENT-跨部门 / CROSS_LOCATION-跨位置 / CROSS_MEDIUM-跨介质 / CROSS_PERIOD-跨周期 / BENCHMARK-对标',
    base_dimension     VARCHAR(128)  NOT NULL                COMMENT '基准维度标识',
    compare_dimension  VARCHAR(128)  NOT NULL                COMMENT '对比维度标识',
    base_value         DECIMAL(18,4) NOT NULL                COMMENT '基准值',
    compare_value      DECIMAL(18,4) NOT NULL                COMMENT '对比值',
    diff_value         DECIMAL(18,4)                         COMMENT '差值（对比值-基准值）',
    diff_rate          DECIMAL(8,4)                          COMMENT '差异率（小数）',
    unit               VARCHAR(16)                           COMMENT '计量单位',
    rank               INT                                   COMMENT '排名（按消耗量降序）',
    percentile         DECIMAL(6,2)                          COMMENT '百分位（在同类维度中的位置）',
    remark             VARCHAR(256)                          COMMENT '备注',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (compare_id, stat_date)
COMMENT '多维对比分析表 | 数据分级=L2 | 跨部门/跨位置/跨介质/跨周期/对标对比'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (compare_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_dimension_compare                IS '多维对比分析表 | 数据分级=L2 | 跨部门/跨位置/跨介质/跨周期/对标对比';
COMMENT ON COLUMN energy_dimension_compare.compare_type  IS '对比类型：CROSS_DEPARTMENT/CROSS_LOCATION/CROSS_MEDIUM/CROSS_PERIOD/BENCHMARK';

-- -----------------------------------------------------------------------------
-- 4. energy_trend_data : 能耗趋势数据表
--    业务含义：能耗趋势聚合数据，供看板时序图表展示
--    数据分级：L2（内部业务：趋势数据）
--    分区策略：按 stat_date 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_trend_data (
    trend_id           VARCHAR(64)   NOT NULL                COMMENT '趋势ID（业务主键）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期',
    stat_time          DATETIME                              COMMENT '统计时间（小时级时填充）',
    granularity        VARCHAR(8)    NOT NULL                COMMENT '粒度：HOUR-时 / DAY-日 / WEEK-周 / MONTH-月',
    measure_medium     VARCHAR(16)   NOT NULL                COMMENT '计量介质',
    dimension_type     VARCHAR(16)                           COMMENT '维度类型',
    dimension_id       VARCHAR(64)                           COMMENT '维度ID',
    dimension_name     VARCHAR(128)                          COMMENT '维度名称',
    consumption        DECIMAL(18,4) NOT NULL                COMMENT '消耗量',
    unit               VARCHAR(16)   NOT NULL                COMMENT '计量单位',
    standard_coal      DECIMAL(18,4)                         COMMENT '折标煤量（kgce）',
    moving_avg_7d      DECIMAL(18,4)                         COMMENT '7 日移动平均',
    moving_avg_30d     DECIMAL(18,4)                         COMMENT '30 日移动平均',
    trend_direction    VARCHAR(8)                            COMMENT '趋势方向：UP-上升 / DOWN-下降 / FLAT-平稳',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (trend_id, stat_date)
COMMENT '能耗趋势数据表 | 数据分级=L2 | 多粒度时序聚合 | 含移动平均与趋势方向'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (trend_id) BUCKETS 10
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_trend_data                  IS '能耗趋势数据表 | 数据分级=L2 | 多粒度时序聚合';
COMMENT ON COLUMN energy_trend_data.granularity      IS '粒度：HOUR/DAY/WEEK/MONTH';
COMMENT ON COLUMN energy_trend_data.trend_direction  IS '趋势方向：UP/DOWN/FLAT';

-- -----------------------------------------------------------------------------
-- 5. energy_quota : 能耗定额表
--    业务含义：能耗定额/指标定义，含基准值/上限/下限/考核周期
--    数据分级：L2（内部业务：定额管理）
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_quota (
    quota_id           VARCHAR(64)   NOT NULL                COMMENT '定额ID（业务主键）',
    quota_name         VARCHAR(128)  NOT NULL                COMMENT '定额名称',
    quota_code         VARCHAR(64)   NOT NULL                COMMENT '定额编码（唯一）',
    measure_medium     VARCHAR(16)   NOT NULL                COMMENT '计量介质',
    dimension_type     VARCHAR(16)   NOT NULL                COMMENT '维度类型：DEPARTMENT/LOCATION/PROCESS/PRODUCT',
    dimension_id       VARCHAR(64)                           COMMENT '维度ID',
    dimension_name     VARCHAR(128)                          COMMENT '维度名称',
    base_value         DECIMAL(18,4) NOT NULL                COMMENT '基准定额值',
    upper_limit        DECIMAL(18,4)                         COMMENT '上限（超过即告警）',
    lower_limit        DECIMAL(18,4)                         COMMENT '下限（低于即告警）',
    unit               VARCHAR(16)   NOT NULL                COMMENT '计量单位',
    assessment_period  VARCHAR(8)    NOT NULL DEFAULT 'MONTH' COMMENT '考核周期：MONTH/QUARTER/YEAR',
    effective_from     DATE          NOT NULL                COMMENT '生效起始日期',
    effective_to       DATE                                  COMMENT '生效结束日期（空表示长期有效）',
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE   COMMENT '是否启用',
    description        VARCHAR(512)                          COMMENT '定额描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人',
    updated_by         VARCHAR(64)   NOT NULL                COMMENT '更新人'
)
ENGINE = OLAP
UNIQUE KEY (quota_id)
COMMENT '能耗定额表 | 数据分级=L2 | 基准值/上下限/考核周期 | 定额管理'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (quota_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_quota                  IS '能耗定额表 | 数据分级=L2 | 基准值/上下限/考核周期';
COMMENT ON COLUMN energy_quota.dimension_type   IS '维度类型：DEPARTMENT/LOCATION/PROCESS/PRODUCT';
COMMENT ON COLUMN energy_quota.assessment_period IS '考核周期：MONTH/QUARTER/YEAR';

-- -----------------------------------------------------------------------------
-- 6. energy_balance : 能源平衡表
--    业务含义：能源平衡分析（输入=输出+损失），用于能效诊断
--    数据分级：L2（内部业务：能源平衡）
--    分区策略：按 stat_date 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_balance (
    balance_id         VARCHAR(64)   NOT NULL                COMMENT '平衡ID（业务主键）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期',
    stat_period        VARCHAR(8)    NOT NULL                COMMENT '统计周期：MONTH/QUARTER/YEAR',
    measure_medium     VARCHAR(16)   NOT NULL                COMMENT '计量介质',
    input_amount       DECIMAL(18,4) NOT NULL                COMMENT '输入量（购入+自产）',
    output_amount      DECIMAL(18,4) NOT NULL                COMMENT '输出量（有效利用+外供）',
    loss_amount        DECIMAL(18,4)                         COMMENT '损失量（输入-输出）',
    loss_rate          DECIMAL(8,4)                          COMMENT '损失率（小数）',
    efficiency         DECIMAL(8,4)                          COMMENT '能效（输出/输入，小数）',
    unit               VARCHAR(16)   NOT NULL                COMMENT '计量单位',
    standard_coal_in   DECIMAL(18,4)                         COMMENT '折标煤输入（kgce）',
    standard_coal_out  DECIMAL(18,4)                         COMMENT '折标煤输出（kgce）',
    remark             VARCHAR(512)                          COMMENT '备注（损失分析说明）',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (balance_id, stat_date)
COMMENT '能源平衡表 | 数据分级=L2 | 输入=输出+损失 | 能效诊断'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (balance_id) BUCKETS 6
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_balance              IS '能源平衡表 | 数据分级=L2 | 输入=输出+损失';
COMMENT ON COLUMN energy_balance.efficiency   IS '能效（输出/输入，小数）';

-- -----------------------------------------------------------------------------
-- 7. energy_cost_analysis : 能源成本分析表
--    业务含义：能源成本分析，含单价/总成本/单位产品成本
--    数据分级：L3（敏感运营：成本数据）
--    分区策略：按 stat_date 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_cost_analysis (
    cost_id            VARCHAR(64)   NOT NULL                COMMENT '成本ID（业务主键）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期',
    stat_period        VARCHAR(8)    NOT NULL                COMMENT '统计周期：MONTH/QUARTER/YEAR',
    measure_medium     VARCHAR(16)   NOT NULL                COMMENT '计量介质',
    dimension_type     VARCHAR(16)                           COMMENT '维度类型：DEPARTMENT/PROCESS/PRODUCT/COMPANY',
    dimension_id       VARCHAR(64)                           COMMENT '维度ID',
    dimension_name     VARCHAR(128)                          COMMENT '维度名称',
    consumption        DECIMAL(18,4) NOT NULL                COMMENT '消耗量',
    unit               VARCHAR(16)   NOT NULL                COMMENT '计量单位',
    unit_price         DECIMAL(18,4) NOT NULL                COMMENT '单价（元/单位）',
    total_cost         DECIMAL(18,2) NOT NULL                COMMENT '总成本（元）',
    product_output     DECIMAL(18,4)                         COMMENT '产品产量',
    unit_product_cost  DECIMAL(18,4)                         COMMENT '单位产品能耗成本（元/产品单位）',
    cost_ratio         DECIMAL(8,4)                          COMMENT '成本占比（在总成本中的比例，小数）',
    yoy_cost_growth    DECIMAL(8,4)                          COMMENT '成本同比增长率（小数）',
    remark             VARCHAR(256)                          COMMENT '备注',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (cost_id, stat_date)
COMMENT '能源成本分析表 | 数据分级=L3 | 单价/总成本/单位产品成本 | 成本占比与同比'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (cost_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_cost_analysis                IS '能源成本分析表 | 数据分级=L3 | 单价/总成本/单位产品成本';
COMMENT ON COLUMN energy_cost_analysis.unit_product_cost IS '单位产品能耗成本（元/产品单位）';
COMMENT ON COLUMN energy_cost_analysis.cost_ratio       IS '成本占比（在总成本中的比例，小数）';

-- =============================================================================
-- 用能分析域 DDL 完成：7 张表
--   energy_consumption_detail / energy_consumption_summary /
--   energy_dimension_compare / energy_trend_data / energy_quota /
--   energy_balance / energy_cost_analysis
-- =============================================================================