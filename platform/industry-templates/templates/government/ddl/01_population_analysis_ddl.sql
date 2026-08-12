-- =============================================================================
-- File   : 01_population_analysis_ddl.sql
-- Domain : 人口分析域（Population Analysis）
-- Engine : Apache Doris（主）/ Apache Iceberg（备，注释中给出兼容写法）
-- Charset: UTF-8
-- Source : 政务行业模板 人口分析业务模型
-- Class  : 数据分级 L2(内部业务) / L3(敏感个人) / L4(秘密)
-- Tables : population_base / population_structure / population_flow /
--          population_forecast / population_age_distribution / population_gender_distribution /
--          population_education_distribution / population_employment_distribution (8 张)
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- 合规   : 涉及人口数据，按 GB/T 31075-2017 政务数据分级分类，身份证号脱敏存储
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. population_base : 人口基础信息表
--    业务含义：常住人口基础信息，含身份证号（脱敏）/姓名（脱敏）/户籍地/常住地
--    数据分级：L3（敏感个人信息，含身份证号/姓名/住址）
--    分区策略：按 updated_at 日期动态分区
--    合规要求：身份证号/姓名/住址字段存储前必须脱敏，访问需审计
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS population_base (
    person_id          VARCHAR(64)   NOT NULL                COMMENT '人员ID（业务主键，雪花ID）',
    id_card_masked     VARCHAR(32)   NOT NULL                COMMENT '身份证号（脱敏，保留前6后4，如 110101********1234）',
    name_masked        VARCHAR(64)   NOT NULL                COMMENT '姓名（脱敏，保留姓，如 张**）',
    gender             VARCHAR(8)    NOT NULL                COMMENT '性别：M-男 / F-女 / U-未知',
    birth_date         DATE                                  COMMENT '出生日期',
    age                INT                                   COMMENT '年龄（由 birth_date 派生）',
    ethnicity          VARCHAR(32)                           COMMENT '民族（56 民族，如 汉族/回族/藏族）',
    marital_status     VARCHAR(16)                           COMMENT '婚姻状况：SINGLE-未婚 / MARRIED-已婚 / DIVORCED-离婚 / WIDOWED-丧偶',
    hukou_type         VARCHAR(16)   NOT NULL                COMMENT '户籍类型：AGRICULTURAL-农业 / NON_AGRICULTURAL-非农业',
    hukou_province     VARCHAR(32)                           COMMENT '户籍省',
    hukou_city         VARCHAR(32)                           COMMENT '户籍市',
    hukou_district     VARCHAR(64)                           COMMENT '户籍区县',
    resident_province  VARCHAR(32)                           COMMENT '常住省',
    resident_city      VARCHAR(32)                           COMMENT '常住市',
    resident_district  VARCHAR(64)                           COMMENT '常住区县',
    resident_address_masked VARCHAR(256)                     COMMENT '常住地址（脱敏，保留到楼号）',
    education_level    VARCHAR(16)                           COMMENT '最高学历：PRIMARY-小学 / JUNIOR-初中 / SENIOR-高中 / COLLEGE-大专 / BACHELOR-本科 / MASTER-硕士 / DOCTOR-博士',
    employment_status  VARCHAR(16)                           COMMENT '就业状态：EMPLOYED-在职 / UNEMPLOYED-失业 / RETIRED-退休 / STUDENT-学生 / OTHER-其他',
    occupation         VARCHAR(64)                           COMMENT '职业（国标职业分类）',
    industry           VARCHAR(64)                           COMMENT '所属行业（国标行业分类）',
    data_source        VARCHAR(32)   NOT NULL                COMMENT '数据来源：CENSUS-人口普查 / HUKOU-户籍 / SURVEY-抽样调查',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L3'    COMMENT '数据分级：L2/L3/L4',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人（工号）',
    updated_by         VARCHAR(64)   NOT NULL                COMMENT '更新人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (person_id, updated_at)
COMMENT '人口基础信息表 | 数据分级=L3 | 含身份证号/姓名脱敏/户籍/常住/学历/就业 | 合规：GB/T 31075'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (person_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  population_base                       IS '人口基础信息表 | 数据分级=L3 | 含身份证号/姓名脱敏/户籍/常住/学历/就业';
COMMENT ON COLUMN population_base.person_id             IS '人员ID（业务主键）';
COMMENT ON COLUMN population_base.id_card_masked        IS '身份证号（脱敏，保留前6后4）';
COMMENT ON COLUMN population_base.name_masked           IS '姓名（脱敏，保留姓）';
COMMENT ON COLUMN population_base.hukou_type            IS '户籍类型：AGRICULTURAL/NON_AGRICULTURAL';
COMMENT ON COLUMN population_base.education_level       IS '最高学历：PRIMARY/JUNIOR/SENIOR/COLLEGE/BACHELOR/MASTER/DOCTOR';
COMMENT ON COLUMN population_base.employment_status     IS '就业状态：EMPLOYED/UNEMPLOYED/RETIRED/STUDENT/OTHER';
COMMENT ON COLUMN population_base.data_classification   IS '数据分级：L2/L3/L4';

-- -----------------------------------------------------------------------------
-- 2. population_structure : 人口结构汇总表
--    业务含义：按区县/年度汇总人口结构指标，含总人口/男/女/城镇化率/老龄化率
--    数据分级：L2（内部业务：汇总统计，无个体信息）
--    分区策略：按 stat_year 年度分区
--    外键关系：无（由 population_base 聚合生成）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS population_structure (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)   NOT NULL                COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县（NULL 表示市级汇总）',
    total_population   BIGINT        NOT NULL                COMMENT '总人口数',
    male_population    BIGINT        NOT NULL                COMMENT '男性人口数',
    female_population  BIGINT        NOT NULL                COMMENT '女性人口数',
    sex_ratio          DECIMAL(8,2)                          COMMENT '性别比（男/女×100）',
    urban_population   BIGINT                                COMMENT '城镇人口数',
    rural_population   BIGINT                                COMMENT '乡村人口数',
    urbanization_rate  DECIMAL(6,4)                          COMMENT '城镇化率（城镇人口/总人口）',
    aged_population    BIGINT                                COMMENT '老年人口数（≥60岁）',
    aging_rate         DECIMAL(6,4)                          COMMENT '老龄化率（老年人口/总人口）',
    child_population   BIGINT                                COMMENT '少儿人口数（0-14岁）',
    child_ratio        DECIMAL(6,4)                          COMMENT '少儿比（少儿人口/总人口）',
    working_age_population BIGINT                            COMMENT '劳动年龄人口数（15-59岁）',
    dependency_ratio   DECIMAL(8,2)                          COMMENT '总抚养比（非劳动人口/劳动人口×100）',
    household_count    BIGINT                                COMMENT '家庭户数',
    avg_household_size DECIMAL(6,2)                          COMMENT '户均人口数',
    data_source        VARCHAR(32)   NOT NULL                COMMENT '数据来源：CENSUS/HUKOU/SURVEY',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '人口结构汇总表 | 数据分级=L2 | 总人口/性别/城乡/年龄结构/抚养比/户均人口'
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
COMMENT ON TABLE  population_structure                       IS '人口结构汇总表 | 数据分级=L2 | 总人口/性别/城乡/年龄结构';
COMMENT ON COLUMN population_structure.sex_ratio             IS '性别比（男/女×100）';
COMMENT ON COLUMN population_structure.urbanization_rate     IS '城镇化率（城镇人口/总人口）';
COMMENT ON COLUMN population_structure.aging_rate            IS '老龄化率（老年人口/总人口）';
COMMENT ON COLUMN population_structure.dependency_ratio      IS '总抚养比（非劳动人口/劳动人口×100）';

-- -----------------------------------------------------------------------------
-- 3. population_flow : 人口流动追踪表
--    业务含义：记录迁入/迁出/净流动量，含流动原因/来源地/目的地
--    数据分级：L2（内部业务：流动统计）
--    分区策略：按 flow_date 日期动态分区
--    外键关系：person_id -> population_base.person_id（弱关联）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS population_flow (
    flow_id            VARCHAR(64)   NOT NULL                COMMENT '流动ID（业务主键）',
    person_id          VARCHAR(64)                           COMMENT '人员ID（外键 -> population_base.person_id，可为空表示汇总）',
    flow_type          VARCHAR(16)   NOT NULL                COMMENT '流动类型：MIGRATE_IN-迁入 / MIGRATE_OUT-迁出 / INTRA_CITY-市内流动 / INTER_PROVINCE-跨省流动',
    flow_date          DATE          NOT NULL                COMMENT '流动日期',
    from_province      VARCHAR(32)                           COMMENT '来源省',
    from_city          VARCHAR(32)                           COMMENT '来源市',
    from_district      VARCHAR(64)                           COMMENT '来源区县',
    to_province        VARCHAR(32)                           COMMENT '目的省',
    to_city            VARCHAR(32)                           COMMENT '目的市',
    to_district        VARCHAR(64)                           COMMENT '目的区县',
    flow_reason        VARCHAR(32)                           COMMENT '流动原因：JOB-工作 / FAMILY-家庭 / EDUCATION-教育 / MEDICAL-医疗 / OTHER-其他',
    age                INT                                   COMMENT '流动时年龄',
    gender             VARCHAR(8)                            COMMENT '性别：M/F',
    education_level    VARCHAR(16)                           COMMENT '学历',
    occupation         VARCHAR(64)                           COMMENT '职业',
    is_hukou_moved     BOOLEAN                               COMMENT '是否随迁户籍：true-是 / false-否',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (flow_id, flow_date)
COMMENT '人口流动追踪表 | 数据分级=L2 | 迁入/迁出/净流动量/流动原因/来源目的'
PARTITION BY RANGE (flow_date) ()
DISTRIBUTED BY HASH (flow_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  population_flow                  IS '人口流动追踪表 | 数据分级=L2 | 迁入/迁出/净流动量';
COMMENT ON COLUMN population_flow.flow_type        IS '流动类型：MIGRATE_IN/MIGRATE_OUT/INTRA_CITY/INTER_PROVINCE';
COMMENT ON COLUMN population_flow.flow_reason      IS '流动原因：JOB/FAMILY/EDUCATION/MEDICAL/OTHER';
COMMENT ON COLUMN population_flow.is_hukou_moved   IS '是否随迁户籍：true/false';

-- -----------------------------------------------------------------------------
-- 4. population_forecast : 人口预测表
--    业务含义：基于历史数据的人口趋势预测，含预测年度/预测人口/预测方法/置信区间
--    数据分级：L2（内部业务：预测结果）
--    分区策略：按 forecast_year 年度分区
--    预测方法：LINEAR-线性 / ARIMA-ARIMA / COHORT-队列要素 / LOGISTIC-逻辑斯蒂
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS population_forecast (
    forecast_id        VARCHAR(64)   NOT NULL                COMMENT '预测ID（业务主键）',
    forecast_year      INT           NOT NULL                COMMENT '预测年度',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)   NOT NULL                COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县（NULL 表示市级预测）',
    forecast_method    VARCHAR(16)   NOT NULL                COMMENT '预测方法：LINEAR/ARIMA/COHORT/LOGISTIC',
    forecast_population BIGINT       NOT NULL                COMMENT '预测总人口',
    forecast_male      BIGINT                                COMMENT '预测男性人口',
    forecast_female    BIGINT                                COMMENT '预测女性人口',
    forecast_lower     BIGINT                                COMMENT '预测下界（95% 置信区间）',
    forecast_upper     BIGINT                                COMMENT '预测上界（95% 置信区间）',
    base_year          INT           NOT NULL                COMMENT '预测基年（历史数据截止年）',
    base_population    BIGINT                                COMMENT '基年实际人口',
    growth_rate        DECIMAL(8,4)                          COMMENT '年均增长率',
    aging_rate_forecast DECIMAL(6,4)                         COMMENT '预测老龄化率',
    urbanization_forecast DECIMAL(6,4)                       COMMENT '预测城镇化率',
    model_params       VARCHAR(512)                          COMMENT '模型参数（JSON 字符串）',
    confidence_level   DECIMAL(4,2)          DEFAULT 0.95    COMMENT '置信水平（默认 0.95）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人（工号）'
)
ENGINE = OLAP
DUPLICATE KEY (forecast_id, forecast_year)
COMMENT '人口预测表 | 数据分级=L2 | 预测人口/性别/置信区间/老龄化/城镇化预测'
PARTITION BY RANGE (forecast_year) ()
DISTRIBUTED BY HASH (forecast_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-10',
    'dynamic_partition.end' = '50',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  population_forecast                       IS '人口预测表 | 数据分级=L2 | 预测人口/置信区间';
COMMENT ON COLUMN population_forecast.forecast_method      IS '预测方法：LINEAR/ARIMA/COHORT/LOGISTIC';
COMMENT ON COLUMN population_forecast.confidence_level     IS '置信水平（默认 0.95）';

-- -----------------------------------------------------------------------------
-- 5. population_age_distribution : 人口年龄分布表
--    业务含义：按区县/年度/年龄分段汇总人口数，用于人口金字塔可视化
--    数据分级：L2（内部业务：汇总统计）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS population_age_distribution (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)   NOT NULL                COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县',
    age_group          VARCHAR(16)   NOT NULL                COMMENT '年龄段：0-4/5-9/10-14/.../95-99/100+',
    age_lower          INT           NOT NULL                COMMENT '年龄段下界',
    age_upper          INT                                   COMMENT '年龄段上界（100+ 段为 NULL）',
    male_count         BIGINT        NOT NULL                COMMENT '男性人口数',
    female_count       BIGINT        NOT NULL                COMMENT '女性人口数',
    total_count        BIGINT        NOT NULL                COMMENT '总人口数（男+女）',
    male_ratio         DECIMAL(6,4)                          COMMENT '男性占比',
    female_ratio       DECIMAL(6,4)                          COMMENT '女性占比',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '人口年龄分布表 | 数据分级=L2 | 按年龄段/性别汇总，用于人口金字塔'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  population_age_distribution        IS '人口年龄分布表 | 数据分级=L2 | 按年龄段/性别汇总';
COMMENT ON COLUMN population_age_distribution.age_group IS '年龄段：0-4/5-9/.../95-99/100+';

-- -----------------------------------------------------------------------------
-- 6. population_gender_distribution : 人口性别分布表
--    业务含义：按区县/年度汇总性别分布，含性别比/出生人口性别比
--    数据分级：L2（内部业务：汇总统计）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS population_gender_distribution (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)   NOT NULL                COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县',
    male_count         BIGINT        NOT NULL                COMMENT '男性人口数',
    female_count       BIGINT        NOT NULL                COMMENT '女性人口数',
    total_count        BIGINT        NOT NULL                COMMENT '总人口数',
    sex_ratio          DECIMAL(8,2)                          COMMENT '性别比（男/女×100）',
    male_ratio         DECIMAL(6,4)                          COMMENT '男性占比',
    female_ratio       DECIMAL(6,4)                          COMMENT '女性占比',
    birth_male_count   BIGINT                                COMMENT '出生男婴数',
    birth_female_count BIGINT                                COMMENT '出生女婴数',
    birth_sex_ratio    DECIMAL(8,2)                          COMMENT '出生人口性别比（男/女×100）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '人口性别分布表 | 数据分级=L2 | 性别比/出生人口性别比'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  population_gender_distribution          IS '人口性别分布表 | 数据分级=L2';
COMMENT ON COLUMN population_gender_distribution.sex_ratio IS '性别比（男/女×100）';
COMMENT ON COLUMN population_gender_distribution.birth_sex_ratio IS '出生人口性别比（男/女×100）';

-- -----------------------------------------------------------------------------
-- 7. population_education_distribution : 人口学历分布表
--    业务含义：按区县/年度/学历层次汇总人口数，用于教育水平分析
--    数据分级：L2（内部业务：汇总统计）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS population_education_distribution (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)   NOT NULL                COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县',
    education_level    VARCHAR(16)   NOT NULL                COMMENT '学历层次：PRIMARY/JUNIOR/SENIOR/COLLEGE/BACHELOR/MASTER/DOCTOR/ILLITERATE',
    population_count   BIGINT        NOT NULL                COMMENT '该学历人口数',
    ratio              DECIMAL(6,4)                          COMMENT '该学历占比',
    male_count         BIGINT                                COMMENT '男性人口数',
    female_count       BIGINT                                COMMENT '女性人口数',
    avg_age            DECIMAL(6,2)                          COMMENT '平均年龄',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '人口学历分布表 | 数据分级=L2 | 按学历层次汇总，含性别/平均年龄'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  population_education_distribution             IS '人口学历分布表 | 数据分级=L2';
COMMENT ON COLUMN population_education_distribution.education_level IS '学历层次：PRIMARY/JUNIOR/SENIOR/COLLEGE/BACHELOR/MASTER/DOCTOR/ILLITERATE';

-- -----------------------------------------------------------------------------
-- 8. population_employment_distribution : 人口就业分布表
--    业务含义：按区县/年度/就业状态/行业汇总人口数，用于就业结构分析
--    数据分级：L2（内部业务：汇总统计）
--    分区策略：按 stat_year 年度分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS population_employment_distribution (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_year          INT           NOT NULL                COMMENT '统计年度',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)   NOT NULL                COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县',
    employment_status  VARCHAR(16)   NOT NULL                COMMENT '就业状态：EMPLOYED/UNEMPLOYED/RETIRED/STUDENT/OTHER',
    industry           VARCHAR(64)                           COMMENT '所属行业（国标行业分类，NULL 表示未就业）',
    occupation         VARCHAR(64)                           COMMENT '职业（国标职业分类）',
    population_count   BIGINT        NOT NULL                COMMENT '该就业状态/行业人口数',
    ratio              DECIMAL(6,4)                          COMMENT '占比',
    male_count         BIGINT                                COMMENT '男性人口数',
    female_count       BIGINT                                COMMENT '女性人口数',
    avg_income         DECIMAL(18,2)                         COMMENT '平均年收入（元）',
    unemployment_rate  DECIMAL(6,4)                          COMMENT '失业率（仅 UNEMPLOYED 状态有效）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_year)
COMMENT '人口就业分布表 | 数据分级=L2 | 按就业状态/行业汇总，含收入/失业率'
PARTITION BY RANGE (stat_year) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'YEAR',
    'dynamic_partition.start' = '-50',
    'dynamic_partition.end' = '5',
    'dynamic_partition.prefix' = 'py',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  population_employment_distribution                IS '人口就业分布表 | 数据分级=L2';
COMMENT ON COLUMN population_employment_distribution.employment_status IS '就业状态：EMPLOYED/UNEMPLOYED/RETIRED/STUDENT/OTHER';
COMMENT ON COLUMN population_employment_distribution.unemployment_rate IS '失业率（仅 UNEMPLOYED 状态有效）';

-- =============================================================================
-- End of 01_population_analysis_ddl.sql
-- =============================================================================