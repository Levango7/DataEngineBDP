-- =============================================================================
-- File   : 03_livelihood_service_ddl.sql
-- Domain : 民生服务域（Livelihood Service）
-- Engine : Apache Doris（主）/ Apache Iceberg（备）
-- Charset: UTF-8
-- Source : 政务行业模板 民生服务业务模型
-- Class  : 数据分级 L1(公开) / L2(内部业务) / L3(敏感个人)
-- Tables : government_service / service_transaction / service_satisfaction /
--          service_hot_topic / service_statistics / service_evaluation /
--          service_category / service_channel (8 张)
-- Notice : Doris 不强制外键，关联关系以注释说明
-- 合规   : 申请人信息脱敏，评价内容不含个人敏感信息
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. government_service : 政务服务事项主表
--    业务含义：政务服务事项定义，含事项编码/名称/类别/办理部门/法定时限
--    数据分级：L1（公开：政务服务事项清单）
--    分区策略：按 updated_at 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS government_service (
    service_id         VARCHAR(64)   NOT NULL                COMMENT '事项ID（业务主键）',
    service_code       VARCHAR(64)   NOT NULL                COMMENT '事项编码（国标政务服务编码）',
    service_name       VARCHAR(256)  NOT NULL                COMMENT '事项名称',
    service_category   VARCHAR(64)   NOT NULL                COMMENT '事项类别（如 行政许可/行政处罚/行政强制/公共服务）',
    parent_category    VARCHAR(64)                           COMMENT '上级类别',
    department         VARCHAR(128)  NOT NULL                COMMENT '实施部门（如 市公安局/市卫健委）',
    department_level   VARCHAR(16)                           COMMENT '部门层级：PROVINCE-省级 / CITY-市级 / DISTRICT-区县级 / TOWN-乡镇级',
    legal_time_limit   INT                                   COMMENT '法定时限（工作日）',
    promised_time_limit INT                                  COMMENT '承诺时限（工作日）',
    is_online          BOOLEAN                               COMMENT '是否支持网办：true-是 / false-否',
    online_url         VARCHAR(512)                          COMMENT '网办地址',
    service_object     VARCHAR(32)                           COMMENT '服务对象：INDIVIDUAL-个人 / ENTERPRISE-企业 / BOTH-个人和企业',
    charge_standard    VARCHAR(256)                          COMMENT '收费标准',
    is_free            BOOLEAN                               COMMENT '是否免费：true-是 / false-否',
    required_materials VARCHAR(1024)                         COMMENT '所需材料（JSON 数组）',
    handling_process   VARCHAR(1024)                         COMMENT '办理流程（JSON 数组）',
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-在办 / SUSPENDED-暂停 / CANCELLED-取消',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L1'    COMMENT '数据分级：L1（公开）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (service_id, updated_at)
COMMENT '政务服务事项主表 | 数据分级=L1 | 事项编码/名称/类别/部门/时限/网办'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (service_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  government_service                       IS '政务服务事项主表 | 数据分级=L1 | 公开';
COMMENT ON COLUMN government_service.service_category      IS '事项类别：行政许可/行政处罚/行政强制/公共服务';
COMMENT ON COLUMN government_service.is_online             IS '是否支持网办：true/false';
COMMENT ON COLUMN government_service.data_classification   IS '数据分级：L1（公开）';

-- -----------------------------------------------------------------------------
-- 2. service_transaction : 政务服务办理记录表
--    业务含义：每笔政务服务的办理流水，含申请人（脱敏）/受理/办结/状态
--    数据分级：L3（敏感个人：含申请人身份证号/姓名脱敏）
--    分区策略：按 accept_time 日期动态分区
--    外键关系：service_id -> government_service.service_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_transaction (
    transaction_id     VARCHAR(64)   NOT NULL                COMMENT '办理流水号（业务主键）',
    service_id         VARCHAR(64)   NOT NULL                COMMENT '事项ID（外键 -> government_service.service_id）',
    service_code       VARCHAR(64)   NOT NULL                COMMENT '事项编码（冗余，便于查询）',
    applicant_id_masked VARCHAR(32)                          COMMENT '申请人身份证号（脱敏，保留前6后4）',
    applicant_name_masked VARCHAR(64)                        COMMENT '申请人姓名（脱敏，保留姓）',
    applicant_type     VARCHAR(16)   NOT NULL                COMMENT '申请人类型：INDIVIDUAL-个人 / ENTERPRISE-企业 / ORGANIZATION-组织',
    applicant_phone_masked VARCHAR(32)                       COMMENT '申请人手机号（脱敏，保留前3后4）',
    accept_time        DATETIME      NOT NULL                COMMENT '受理时间',
    complete_time      DATETIME                              COMMENT '办结时间',
    processing_duration DECIMAL(10,2)                        COMMENT '办理时长（工作日，由 complete_time-accept_time 计算）',
    status             VARCHAR(16)   NOT NULL                COMMENT '办理状态：PENDING-待办 / PROCESSING-办理中 / COMPLETED-已办结 / REJECTED-已驳回 / CANCELLED-已撤销',
    channel            VARCHAR(32)   NOT NULL                COMMENT '办理渠道：ONLINE-网办 / WINDOW-窗口 / SELF_SERVICE-自助机 / MOBILE-移动端',
    department         VARCHAR(128)  NOT NULL                COMMENT '办理部门',
    handler            VARCHAR(64)                           COMMENT '经办人（工号）',
    result             VARCHAR(16)                           COMMENT '办理结果：APPROVED-批准 / DENIED-不批准 / PARTIAL-部分批准',
    remark             VARCHAR(512)                          COMMENT '备注',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L3'    COMMENT '数据分级：L3（敏感个人）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (transaction_id, accept_time)
COMMENT '政务服务办理记录表 | 数据分级=L3 | 申请人脱敏/受理/办结/状态/渠道'
PARTITION BY RANGE (accept_time) ()
DISTRIBUTED BY HASH (transaction_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  service_transaction                       IS '政务服务办理记录表 | 数据分级=L3 | 含申请人脱敏';
COMMENT ON COLUMN service_transaction.applicant_id_masked   IS '申请人身份证号（脱敏，保留前6后4）';
COMMENT ON COLUMN service_transaction.status                IS '办理状态：PENDING/PROCESSING/COMPLETED/REJECTED/CANCELLED';
COMMENT ON COLUMN service_transaction.channel               IS '办理渠道：ONLINE/WINDOW/SELF_SERVICE/MOBILE';

-- -----------------------------------------------------------------------------
-- 3. service_satisfaction : 满意度评价表
--    业务含义：每笔办理的评价记录，含满意度评分/评价内容/评价渠道
--    数据分级：L2（内部业务：评价数据，不含个人敏感信息）
--    分区策略：按 evaluate_time 日期动态分区
--    外键关系：transaction_id -> service_transaction.transaction_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_satisfaction (
    evaluation_id      VARCHAR(64)   NOT NULL                COMMENT '评价ID（业务主键）',
    transaction_id     VARCHAR(64)   NOT NULL                COMMENT '办理流水号（外键 -> service_transaction.transaction_id）',
    service_id         VARCHAR(64)   NOT NULL                COMMENT '事项ID',
    satisfaction_score INT           NOT NULL                COMMENT '满意度评分：1-5（5-非常满意 / 4-满意 / 3-基本满意 / 2-不满意 / 1-非常不满意）',
    evaluation_content VARCHAR(1024)                         COMMENT '评价内容（不含个人敏感信息）',
    evaluation_tags    VARCHAR(256)                          COMMENT '评价标签（JSON 数组，如 态度好/效率高/流程复杂）',
    evaluate_time      DATETIME      NOT NULL                COMMENT '评价时间',
    evaluate_channel   VARCHAR(32)                           COMMENT '评价渠道：SMS-短信 / ONLINE-网办 / WINDOW-窗口 / APP-APP',
    is_anonymous       BOOLEAN                               COMMENT '是否匿名评价：true-是 / false-否',
    respondent_type    VARCHAR(16)                           COMMENT '评价人类型：INDIVIDUAL-个人 / ENTERPRISE-企业',
    is_verified        BOOLEAN                               COMMENT '是否已核实（人工审核）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (evaluation_id, evaluate_time)
COMMENT '满意度评价表 | 数据分级=L2 | 评分1-5/评价内容/标签/渠道'
PARTITION BY RANGE (evaluate_time) ()
DISTRIBUTED BY HASH (evaluation_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  service_satisfaction                       IS '满意度评价表 | 数据分级=L2';
COMMENT ON COLUMN service_satisfaction.satisfaction_score    IS '满意度评分：1-5';
COMMENT ON COLUMN service_satisfaction.evaluate_channel      IS '评价渠道：SMS/ONLINE/WINDOW/APP';

-- -----------------------------------------------------------------------------
-- 4. service_hot_topic : 热点事项表
--    业务含义：按周期汇总热点事项排行，含办理量/搜索量/投诉量
--    数据分级：L1（公开：热点事项排行）
--    分区策略：按 stat_date 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_hot_topic (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期',
    stat_period        VARCHAR(16)   NOT NULL                COMMENT '统计周期：DAILY-日 / WEEKLY-周 / MONTHLY-月 / QUARTERLY-季 / YEARLY-年',
    service_id         VARCHAR(64)   NOT NULL                COMMENT '事项ID',
    service_name       VARCHAR(256)  NOT NULL                COMMENT '事项名称（冗余）',
    service_category   VARCHAR(64)                           COMMENT '事项类别',
    department         VARCHAR(128)                          COMMENT '实施部门',
    transaction_count  BIGINT        NOT NULL                COMMENT '办理量',
    search_count       BIGINT                                COMMENT '搜索量',
    complaint_count    BIGINT                                COMMENT '投诉量',
    hot_rank           INT                                   COMMENT '热度排名',
    hot_score          DECIMAL(10,2)                         COMMENT '热度评分（加权计算）',
    online_ratio       DECIMAL(6,4)                          COMMENT '网办率',
    completion_rate    DECIMAL(6,4)                          COMMENT '办结率',
    avg_satisfaction   DECIMAL(4,2)                          COMMENT '平均满意度',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_date)
COMMENT '热点事项表 | 数据分级=L1 | 办理量/搜索量/投诉量/热度排名'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  service_hot_topic                       IS '热点事项表 | 数据分级=L1 | 公开';
COMMENT ON COLUMN service_hot_topic.stat_period           IS '统计周期：DAILY/WEEKLY/MONTHLY/QUARTERLY/YEARLY';

-- -----------------------------------------------------------------------------
-- 5. service_statistics : 政务服务统计表
--    业务含义：按部门/周期汇总办理量/办结率/平均时长/网办率
--    数据分级：L2（内部业务：统计汇总）
--    分区策略：按 stat_date 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_statistics (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期',
    stat_period        VARCHAR(16)   NOT NULL                COMMENT '统计周期：DAILY/WEEKLY/MONTHLY/QUARTERLY/YEARLY',
    province           VARCHAR(32)   NOT NULL                COMMENT '省',
    city               VARCHAR(32)                           COMMENT '市',
    district           VARCHAR(64)                           COMMENT '区县',
    department         VARCHAR(128)                          COMMENT '部门（NULL 表示全域汇总）',
    service_category   VARCHAR(64)                           COMMENT '事项类别（NULL 表示全类别汇总）',
    total_count        BIGINT        NOT NULL                COMMENT '办理总量',
    completed_count    BIGINT                                COMMENT '已办结量',
    pending_count      BIGINT                                COMMENT '待办量',
    rejected_count     BIGINT                                COMMENT '驳回量',
    completion_rate    DECIMAL(6,4)                          COMMENT '办结率（已办结/办理总量）',
    rejection_rate     DECIMAL(6,4)                          COMMENT '驳回率',
    avg_processing_days DECIMAL(10,2)                        COMMENT '平均办理时长（工作日）',
    online_count       BIGINT                                COMMENT '网办量',
    window_count       BIGINT                                COMMENT '窗口办理量',
    online_rate        DECIMAL(6,4)                          COMMENT '网办率',
    avg_satisfaction   DECIMAL(4,2)                          COMMENT '平均满意度',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_date)
COMMENT '政务服务统计表 | 数据分级=L2 | 办理量/办结率/平均时长/网办率/满意度'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  service_statistics                       IS '政务服务统计表 | 数据分级=L2';
COMMENT ON COLUMN service_statistics.completion_rate       IS '办结率（已办结/办理总量）';
COMMENT ON COLUMN service_statistics.online_rate           IS '网办率';

-- -----------------------------------------------------------------------------
-- 6. service_evaluation : 评价汇总表
--    业务含义：按事项/周期汇总评价分布，含各评分人数/平均分/满意度率
--    数据分级：L2（内部业务：评价汇总）
--    分区策略：按 stat_date 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_evaluation (
    stat_id            VARCHAR(64)   NOT NULL                COMMENT '统计ID（业务主键）',
    stat_date          DATE          NOT NULL                COMMENT '统计日期',
    stat_period        VARCHAR(16)   NOT NULL                COMMENT '统计周期',
    service_id         VARCHAR(64)                           COMMENT '事项ID（NULL 表示全事项汇总）',
    department         VARCHAR(128)                          COMMENT '部门',
    total_evaluations  BIGINT        NOT NULL                COMMENT '评价总数',
    score_5_count      BIGINT                                COMMENT '5 分（非常满意）人数',
    score_4_count      BIGINT                                COMMENT '4 分（满意）人数',
    score_3_count      BIGINT                                COMMENT '3 分（基本满意）人数',
    score_2_count      BIGINT                                COMMENT '2 分（不满意）人数',
    score_1_count      BIGINT                                COMMENT '1 分（非常不满意）人数',
    avg_score          DECIMAL(4,2)                          COMMENT '平均分',
    satisfaction_rate  DECIMAL(6,4)                          COMMENT '满意度率（4+5 分占比）',
    dissatisfaction_rate DECIMAL(6,4)                        COMMENT '不满意度率（1+2 分占比）',
    top_tags          VARCHAR(512)                           COMMENT '高频评价标签（JSON 数组）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (stat_id, stat_date)
COMMENT '评价汇总表 | 数据分级=L2 | 各评分人数/平均分/满意度率/高频标签'
PARTITION BY RANGE (stat_date) ()
DISTRIBUTED BY HASH (stat_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  service_evaluation                       IS '评价汇总表 | 数据分级=L2';
COMMENT ON COLUMN service_evaluation.satisfaction_rate     IS '满意度率（4+5 分占比）';

-- -----------------------------------------------------------------------------
-- 7. service_category : 服务事项类别表
--    业务含义：政务服务事项类别字典，含类别编码/名称/层级
--    数据分级：L1（公开：类别字典）
--    分区策略：按 updated_at 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_category (
    category_id        VARCHAR(64)   NOT NULL                COMMENT '类别ID（业务主键）',
    category_code      VARCHAR(64)   NOT NULL                COMMENT '类别编码（国标编码）',
    category_name      VARCHAR(128)  NOT NULL                COMMENT '类别名称',
    parent_id          VARCHAR(64)                           COMMENT '上级类别ID（NULL 表示顶级）',
    level              INT           NOT NULL                COMMENT '层级（1-顶级 / 2-二级 / 3-三级）',
    sort_order         INT                                   COMMENT '排序序号',
    description        VARCHAR(512)                          COMMENT '类别描述',
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-启用 / INACTIVE-停用',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (category_id, updated_at)
COMMENT '服务事项类别表 | 数据分级=L1 | 类别字典/层级'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (category_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  service_category                IS '服务事项类别表 | 数据分级=L1 | 公开';

-- -----------------------------------------------------------------------------
-- 8. service_channel : 服务渠道表
--    业务含义：政务服务办理渠道字典，含渠道编码/名称/类型
--    数据分级：L1（公开：渠道字典）
--    分区策略：按 updated_at 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_channel (
    channel_id         VARCHAR(64)   NOT NULL                COMMENT '渠道ID（业务主键）',
    channel_code       VARCHAR(64)   NOT NULL                COMMENT '渠道编码：ONLINE/WINDOW/SELF_SERVICE/MOBILE/PHONE/MAIL',
    channel_name       VARCHAR(128)  NOT NULL                COMMENT '渠道名称',
    channel_type       VARCHAR(32)   NOT NULL                COMMENT '渠道类型：DIGITAL-数字化 / PHYSICAL-实体 / HYBRID-混合',
    description        VARCHAR(512)                          COMMENT '渠道描述',
    is_available       BOOLEAN                               COMMENT '是否可用：true-是 / false-否',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (channel_id, updated_at)
COMMENT '服务渠道表 | 数据分级=L1 | 渠道字典'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (channel_id) BUCKETS 2
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  service_channel                IS '服务渠道表 | 数据分级=L1 | 公开';

-- =============================================================================
-- End of 03_livelihood_service_ddl.sql
-- =============================================================================