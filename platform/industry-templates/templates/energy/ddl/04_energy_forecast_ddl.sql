-- =============================================================================
-- File   : 04_energy_forecast_ddl.sql
-- Domain : 能耗趋势预测域（Energy Forecast）
-- Engine : Apache Doris（主）/ Apache Iceberg（备，注释中给出兼容写法）
-- Charset: UTF-8
-- Source : 能源行业模板 趋势预测业务模型
-- Class  : 数据分级 L2（内部业务）/ L3（敏感运营）
-- Tables : forecast_parameter / forecast_result / forecast_model_evaluation /
--          forecast_model_registry / forecast_confidence_interval（5 张）
-- Notice : Doris 不强制外键，关联关系以注释说明，血缘由 L3.5 资产目录登记
-- 业务说明：基于历史能耗时序数据，使用 ARIMA/Prophet/LSTM 等模型预测未来能耗，
--           输出预测值与置信区间，供能源计划与定额管理参考
-- =============================================================================
-- 评估指标：
--   MAPE = mean(|actual - forecast| / |actual|) × 100%
--   RMSE = sqrt(mean((actual - forecast)^2))
--   MAE  = mean(|actual - forecast|)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. forecast_parameter : 预测参数表
--    业务含义：预测任务参数配置，含模型类型/历史窗口/预测步长/超参
--    数据分级：L2（内部业务：预测配置）
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS forecast_parameter (
    param_id           VARCHAR(64)   NOT NULL                COMMENT '参数ID（业务主键）',
    param_name         VARCHAR(128)  NOT NULL                COMMENT '预测任务名称',
    param_code         VARCHAR(64)   NOT NULL                COMMENT '任务编码（唯一）',
    target_metric      VARCHAR(64)   NOT NULL                COMMENT '预测目标指标：CONSUMPTION-能耗 / EMISSION-碳排放 / COST-成本 / PEAK_LOAD-峰值负荷',
    measure_medium     VARCHAR(16)                           COMMENT '计量介质（可空，空表示全部介质）',
    dimension_type     VARCHAR(16)                           COMMENT '维度类型：DEPARTMENT/LOCATION/PROCESS/COMPANY',
    dimension_id       VARCHAR(64)                           COMMENT '维度ID',
    dimension_name     VARCHAR(128)                          COMMENT '维度名称',
    granularity        VARCHAR(8)    NOT NULL DEFAULT 'DAY'  COMMENT '数据粒度：HOUR/DAY/WEEK/MONTH',
    history_window     INT           NOT NULL                COMMENT '历史窗口长度（取过去 N 个粒度单位作为训练集）',
    forecast_horizon   INT           NOT NULL                COMMENT '预测步长（预测未来 N 个粒度单位）',
    model_type         VARCHAR(32)   NOT NULL                COMMENT '模型类型：ARIMA-自回归积分滑动平均 / PROPHET-Facebook Prophet / LSTM-长短期记忆 / EXPONENTIAL_SMOOTHING-指数平滑 / LINEAR_REGRESSION-线性回归 / ENSEMBLE-集成',
    hyperparameters    VARCHAR(2048)                         COMMENT '模型超参（JSON 字符串，如 {"order":[1,1,1],"seasonal_order":[0,1,1,7]}）',
    feature_columns    VARCHAR(512)                          COMMENT '特征列（JSON 数组，如 ["temperature","production_volume","is_holiday"]）',
    retrain_interval   INT                                   COMMENT '重训练间隔（天，0 表示不自动重训练）',
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE   COMMENT '是否启用',
    description        VARCHAR(512)                          COMMENT '任务描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人',
    updated_by         VARCHAR(64)   NOT NULL                COMMENT '更新人'
)
ENGINE = OLAP
UNIQUE KEY (param_id)
COMMENT '预测参数表 | 数据分级=L2 | 模型类型/历史窗口/预测步长/超参'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (param_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  forecast_parameter                IS '预测参数表 | 数据分级=L2 | 模型类型/历史窗口/预测步长/超参';
COMMENT ON COLUMN forecast_parameter.target_metric  IS '预测目标指标：CONSUMPTION/EMISSION/COST/PEAK_LOAD';
COMMENT ON COLUMN forecast_parameter.model_type     IS '模型类型：ARIMA/PROPHET/LSTM/EXPONENTIAL_SMOOTHING/LINEAR_REGRESSION/ENSEMBLE';
COMMENT ON COLUMN forecast_parameter.hyperparameters IS '模型超参（JSON 字符串）';

-- -----------------------------------------------------------------------------
-- 2. forecast_result : 预测结果表
--    业务含义：预测结果，含预测值/置信区间下限/上限/实际值（回填）
--    数据分级：L2（内部业务：预测结果）
--    分区策略：按 forecast_date 日期动态分区
--    外键关系：param_id -> forecast_parameter.param_id
--             model_version_id -> forecast_model_registry.model_version_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS forecast_result (
    result_id          VARCHAR(64)   NOT NULL                COMMENT '结果ID（业务主键）',
    param_id           VARCHAR(64)   NOT NULL                COMMENT '预测参数ID（外键 -> forecast_parameter.param_id）',
    model_version_id   VARCHAR(64)                           COMMENT '模型版本ID（外键 -> forecast_model_registry.model_version_id）',
    target_metric      VARCHAR(64)   NOT NULL                COMMENT '预测目标指标',
    measure_medium     VARCHAR(16)                           COMMENT '计量介质',
    dimension_type     VARCHAR(16)                           COMMENT '维度类型',
    dimension_id       VARCHAR(64)                           COMMENT '维度ID',
    dimension_name     VARCHAR(128)                          COMMENT '维度名称',
    forecast_date      DATE          NOT NULL                COMMENT '预测日期',
    forecast_time      DATETIME                             COMMENT '预测时间（小时级时填充）',
    granularity        VARCHAR(8)    NOT NULL                COMMENT '粒度：HOUR/DAY/WEEK/MONTH',
    forecast_value     DECIMAL(18,4) NOT NULL                COMMENT '预测值',
    lower_bound        DECIMAL(18,4)                         COMMENT '置信区间下限（95% 置信度）',
    upper_bound        DECIMAL(18,4)                         COMMENT '置信区间上限（95% 置信度）',
    confidence_level   DECIMAL(4,2)           DEFAULT 0.95   COMMENT '置信水平（默认 0.95）',
    actual_value       DECIMAL(18,4)                         COMMENT '实际值（事后回填，用于评估）',
    error_value        DECIMAL(18,4)                         COMMENT '预测误差（actual - forecast）',
    error_pct          DECIMAL(8,4)                          COMMENT '误差百分比（小数）',
    unit               VARCHAR(16)                           COMMENT '计量单位',
    is_out_of_bounds   BOOLEAN                               COMMENT '实际值是否超出置信区间',
    generated_at       DATETIME      NOT NULL                COMMENT '预测生成时间',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (result_id, forecast_date)
COMMENT '预测结果表 | 数据分级=L2 | 预测值/置信区间/实际值回填 | 多粒度'
PARTITION BY RANGE (forecast_date) ()
DISTRIBUTED BY HASH (result_id) BUCKETS 10
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-365',
    'dynamic_partition.end' = '365',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  forecast_result                  IS '预测结果表 | 数据分级=L2 | 预测值/置信区间/实际值回填';
COMMENT ON COLUMN forecast_result.lower_bound      IS '置信区间下限（95% 置信度）';
COMMENT ON COLUMN forecast_result.upper_bound      IS '置信区间上限（95% 置信度）';
COMMENT ON COLUMN forecast_result.actual_value     IS '实际值（事后回填，用于评估）';

-- -----------------------------------------------------------------------------
-- 3. forecast_model_evaluation : 模型评估表
--    业务含义：预测模型评估指标，含 MAPE/RMSE/MAE/R²
--    数据分级：L2（内部业务：模型评估）
--    分区策略：by eval_date 日期动态分区
--    外键关系：param_id -> forecast_parameter.param_id
--             model_version_id -> forecast_model_registry.model_version_id
-- 评估指标：
--   MAPE = mean(|actual - forecast| / |actual|) × 100%
--   RMSE = sqrt(mean((actual - forecast)^2))
--   MAE  = mean(|actual - forecast|)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS forecast_model_evaluation (
    eval_id            VARCHAR(64)   NOT NULL                COMMENT '评估ID（业务主键）',
    param_id           VARCHAR(64)   NOT NULL                COMMENT '预测参数ID（外键）',
    model_version_id   VARCHAR(64)                           COMMENT '模型版本ID（外键）',
    model_type         VARCHAR(32)   NOT NULL                COMMENT '模型类型',
    eval_date          DATE          NOT NULL                COMMENT '评估日期',
    eval_period        VARCHAR(16)   NOT NULL                COMMENT '评估周期：ROLLING_7D-滚动7天 / ROLLING_30D-滚动30天 / FIXED_TEST_SET-固定测试集',
    sample_count       INT           NOT NULL                COMMENT '评估样本数',
    mape               DECIMAL(8,4)                           COMMENT 'MAPE 平均绝对百分比误差（小数，0.1 表示 10%）',
    rmse               DECIMAL(18,4)                         COMMENT 'RMSE 均方根误差',
    mae                DECIMAL(18,4)                         COMMENT 'MAE 平均绝对误差',
    r_squared          DECIMAL(8,4)                          COMMENT 'R² 决定系数',
    bias               DECIMAL(18,4)                         COMMENT '偏差（mean(actual - forecast)）',
    tracking_signal    DECIMAL(8,4)                          COMMENT '跟踪信号（用于检测预测偏移）',
    grade              VARCHAR(8)                            COMMENT '评级：EXCELLENT-优秀(MAPE<5%) / GOOD-良好(5-10%) / FAIR-一般(10-20%) / POOR-差(>20%)',
    is_selected        BOOLEAN                               COMMENT '是否被选为当前生产模型',
    remark             VARCHAR(256)                          COMMENT '备注',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (eval_id, eval_date)
COMMENT '模型评估表 | 数据分级=L2 | MAPE/RMSE/MAE/R²/偏差/跟踪信号'
PARTITION BY RANGE (eval_date) ()
DISTRIBUTED BY HASH (eval_id) BUCKETS 6
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-365',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  forecast_model_evaluation              IS '模型评估表 | 数据分级=L2 | MAPE/RMSE/MAE/R²';
COMMENT ON COLUMN forecast_model_evaluation.mape         IS 'MAPE 平均绝对百分比误差（小数）';
COMMENT ON COLUMN forecast_model_evaluation.rmse         IS 'RMSE 均方根误差';
COMMENT ON COLUMN forecast_model_evaluation.tracking_signal IS '跟踪信号（用于检测预测偏移）';

-- -----------------------------------------------------------------------------
-- 4. forecast_model_registry : 模型注册表
--    业务含义：模型版本注册，含训练参数/状态/工件路径
--    数据分级：L2（内部业务：模型注册）
--    分区策略：按 created_at 日期动态分区
--    外键关系：param_id -> forecast_parameter.param_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS forecast_model_registry (
    model_version_id   VARCHAR(64)   NOT NULL                COMMENT '模型版本ID（业务主键）',
    param_id           VARCHAR(64)   NOT NULL                COMMENT '预测参数ID（外键 -> forecast_parameter.param_id）',
    model_type         VARCHAR(32)   NOT NULL                COMMENT '模型类型',
    version            VARCHAR(16)   NOT NULL                COMMENT '版本号（如 1.0.0）',
    training_start     DATETIME      NOT NULL                COMMENT '训练开始时间',
    training_end       DATETIME                             COMMENT '训练结束时间',
    training_duration_sec INT                                 COMMENT '训练时长（秒）',
    training_sample_count INT                                COMMENT '训练样本数',
    training_params    VARCHAR(2048)                         COMMENT '训练参数（JSON 字符串）',
    artifact_path      VARCHAR(512)                          COMMENT '模型工件存储路径（HDFS/S3/MLflow）',
    artifact_size_bytes BIGINT                                COMMENT '模型工件大小（字节）',
    status             VARCHAR(16)   NOT NULL DEFAULT 'TRAINING' COMMENT '状态：TRAINING-训练中 / READY-就绪 / DEPLOYED-已部署 / FAILED-失败 / ARCHIVED-已归档',
    is_production      BOOLEAN       NOT NULL DEFAULT FALSE  COMMENT '是否当前生产模型',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
UNIQUE KEY (model_version_id)
COMMENT '模型注册表 | 数据分级=L2 | 版本/训练参数/工件路径/状态 | MLflow 集成'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (model_version_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  forecast_model_registry                  IS '模型注册表 | 数据分级=L2 | 版本/训练参数/工件路径/状态';
COMMENT ON COLUMN forecast_model_registry.status           IS '状态：TRAINING/READY/DEPLOYED/FAILED/ARCHIVED';
COMMENT ON COLUMN forecast_model_registry.is_production    IS '是否当前生产模型';

-- -----------------------------------------------------------------------------
-- 5. forecast_confidence_interval : 置信区间表
--    业务含义：不同置信水平下的预测区间，供风险分析
--    数据分级：L2（内部业务：预测区间）
--    分区策略：按 forecast_date 日期动态分区
--    外键关系：result_id -> forecast_result.result_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS forecast_confidence_interval (
    interval_id        VARCHAR(64)   NOT NULL                COMMENT '区间ID（业务主键）',
    result_id          VARCHAR(64)   NOT NULL                COMMENT '预测结果ID（外键 -> forecast_result.result_id）',
    forecast_date      DATE          NOT NULL                COMMENT '预测日期',
    confidence_level   DECIMAL(4,2)  NOT NULL                COMMENT '置信水平（如 0.80 / 0.90 / 0.95 / 0.99）',
    lower_bound        DECIMAL(18,4) NOT NULL                COMMENT '下限',
    upper_bound        DECIMAL(18,4) NOT NULL                COMMENT '上限',
    interval_width     DECIMAL(18,4)                         COMMENT '区间宽度（upper - lower）',
    method             VARCHAR(32)                           COMMENT '区间估计方法：ANALYTICAL-解析 / BOOTSTRAP-自助法 / BAYESIAN-贝叶斯 / QUANTILE-分位数',
    created_at         DATETIME      NOT NULL                COMMENT '记录创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (interval_id, forecast_date)
COMMENT '置信区间表 | 数据分级=L2 | 多置信水平预测区间 | 风险分析'
PARTITION BY RANGE (forecast_date) ()
DISTRIBUTED BY HASH (interval_id) BUCKETS 6
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-365',
    'dynamic_partition.end' = '365',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  forecast_confidence_interval              IS '置信区间表 | 数据分级=L2 | 多置信水平预测区间';
COMMENT ON COLUMN forecast_confidence_interval.confidence_level IS '置信水平（如 0.80/0.90/0.95/0.99）';
COMMENT ON COLUMN forecast_confidence_interval.method        IS '区间估计方法：ANALYTICAL/BOOTSTRAP/BAYESIAN/QUANTILE';

-- =============================================================================
-- 趋势预测域 DDL 完成：5 张表
--   forecast_parameter / forecast_result / forecast_model_evaluation /
--   forecast_model_registry / forecast_confidence_interval
-- 评估指标：MAPE / RMSE / MAE / R² / 偏差 / 跟踪信号
-- =============================================================================