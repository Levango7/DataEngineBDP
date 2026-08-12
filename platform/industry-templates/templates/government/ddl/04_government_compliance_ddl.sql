-- =============================================================================
-- File   : 04_government_compliance_ddl.sql
-- Domain : 政务合规域（Government Compliance）
-- Engine : Apache Doris（主）/ Apache Iceberg（备）
-- Charset: UTF-8
-- Source : 政务行业模板 政务合规预置业务模型
-- Class  : 数据分级 L2(内部) / L3(敏感) / L4(秘密)
-- Tables : data_classification / desensitize_rule / audit_log /
--          access_control_policy / access_control_record / compliance_risk_alert /
--          compliance_check_record / compliance_policy (8 张)
-- Notice : 政务合规是本模板的重点特色，完整实现数据分级/脱敏/审计/访问控制
-- 合规   : 对标 GB/T 31075-2017 政务数据分级分类、等保2.0、关基保护要求
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. data_classification : 数据分级定义表
--    业务含义：数据分级字典，含分级编码/名称/描述/处理要求
--    数据分级：L2（内部业务：分级定义）
--    分级标准：L1-公开 / L2-内部 / L3-秘密 / L4-机密
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_classification (
    classification_id  VARCHAR(64)   NOT NULL                COMMENT '分级ID（业务主键）',
    level_code         VARCHAR(8)    NOT NULL                COMMENT '分级编码：L1/L2/L3/L4',
    level_name         VARCHAR(32)   NOT NULL                COMMENT '分级名称：公开/内部/秘密/机密',
    level_rank         INT           NOT NULL                COMMENT '级别序号（1-4，越大越敏感）',
    description        VARCHAR(512)                          COMMENT '分级描述',
    handling_requirements VARCHAR(1024)                      COMMENT '处理要求（JSON：存储/传输/访问/销毁）',
    allowed_operations VARCHAR(512)                          COMMENT '允许操作（JSON：read/write/export/share）',
    encryption_required BOOLEAN                               COMMENT '是否要求加密存储',
    audit_required     BOOLEAN                               COMMENT '是否要求审计',
    retention_days     INT                                   COMMENT '保留天数（NULL 表示永久）',
    is_active          BOOLEAN                               COMMENT '是否启用：true-是 / false-否',
    standard_reference VARCHAR(128)                          COMMENT '标准引用（如 GB/T 31075-2017）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (classification_id, updated_at)
COMMENT '数据分级定义表 | 数据分级=L2 | L1公开/L2内部/L3秘密/L4机密'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (classification_id) BUCKETS 2
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  data_classification                       IS '数据分级定义表 | 数据分级=L2';
COMMENT ON COLUMN data_classification.level_code            IS '分级编码：L1/L2/L3/L4';
COMMENT ON COLUMN data_classification.level_name            IS '分级名称：公开/内部/秘密/机密';

-- -----------------------------------------------------------------------------
-- 2. desensitize_rule : 脱敏规则表
--    业务含义：字段级脱敏规则，含算法/参数/适用字段/生效条件
--    数据分级：L2（内部业务：规则定义）
--    脱敏算法：MASK-掩码 / KEEP_FIRST-保留首字符 / KEEP_PREFIX-保留前缀 / HASH-哈希 / TOKENIZE-假名化 / ROUND-精度降级
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS desensitize_rule (
    rule_id            VARCHAR(64)   NOT NULL                COMMENT '规则ID（业务主键）',
    rule_name          VARCHAR(128)  NOT NULL                COMMENT '规则名称',
    rule_code          VARCHAR(64)   NOT NULL                COMMENT '规则编码',
    algorithm          VARCHAR(32)   NOT NULL                COMMENT '脱敏算法：MASK/KEEP_FIRST/KEEP_PREFIX/HASH/TOKENIZE/ROUND',
    algorithm_params   VARCHAR(1024)                         COMMENT '算法参数（JSON：pattern/replacement/mask_char/keep_length 等）',
    applicable_fields  VARCHAR(1024) NOT NULL                COMMENT '适用字段（JSON 数组：table.column）',
    applicable_levels  VARCHAR(256)                          COMMENT '适用数据分级（JSON 数组：L1/L2/L3/L4）',
    condition          VARCHAR(512)                          COMMENT '生效条件（如 role != "auditor"）',
    scope              VARCHAR(256)                          COMMENT '生效范围（JSON 数组：query/export/api）',
    priority           INT           NOT NULL DEFAULT 100    COMMENT '优先级（数字越小优先级越高）',
    is_active          BOOLEAN                               COMMENT '是否启用：true-是 / false-否',
    description        VARCHAR(512)                          COMMENT '规则描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (rule_id, updated_at)
COMMENT '脱敏规则表 | 数据分级=L2 | 字段级脱敏/算法/参数/适用字段/生效条件'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (rule_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  desensitize_rule                       IS '脱敏规则表 | 数据分级=L2';
COMMENT ON COLUMN desensitize_rule.algorithm             IS '脱敏算法：MASK/KEEP_FIRST/KEEP_PREFIX/HASH/TOKENIZE/ROUND';
COMMENT ON COLUMN desensitize_rule.applicable_fields     IS '适用字段（JSON 数组：table.column）';

-- -----------------------------------------------------------------------------
-- 3. audit_log : 审计日志表
--    业务含义：所有敏感操作的审计日志，含操作人/操作类型/对象/时间/结果
--    数据分级：L4（机密：审计日志，最高保护）
--    分区策略：按 operate_time 日期动态分区
--    合规要求：审计日志不可篡改、不可删除，保留 ≥ 180 天
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    log_id             VARCHAR(64)   NOT NULL                COMMENT '日志ID（业务主键）',
    operate_time       DATETIME      NOT NULL                COMMENT '操作时间',
    operator_id        VARCHAR(64)   NOT NULL                COMMENT '操作人ID（用户ID/工号）',
    operator_name      VARCHAR(128)                          COMMENT '操作人姓名',
    operator_role      VARCHAR(64)                           COMMENT '操作人角色',
    operate_type       VARCHAR(32)   NOT NULL                COMMENT '操作类型：LOGIN-登录 / LOGOUT-登出 / QUERY-查询 / EXPORT-导出 / MODIFY-修改 / DELETE-删除 / GRANT-授权 / REVOKE-收回 / ACCESS-访问',
    target_type        VARCHAR(32)                           COMMENT '操作对象类型：TABLE/DAG/DASHBOARD/RECORD/USER/ROLE/POLICY',
    target_id          VARCHAR(128)                          COMMENT '操作对象ID',
    target_name        VARCHAR(256)                          COMMENT '操作对象名称',
    operate_result     VARCHAR(16)   NOT NULL                COMMENT '操作结果：SUCCESS-成功 / FAILURE-失败 / DENIED-拒绝',
    failure_reason     VARCHAR(512)                          COMMENT '失败原因',
    source_ip          VARCHAR(64)                           COMMENT '来源 IP',
    source_app         VARCHAR(128)                          COMMENT '来源应用',
    request_params     VARCHAR(2048)                         COMMENT '请求参数（JSON，脱敏后存储）',
    response_summary   VARCHAR(1024)                         COMMENT '响应摘要（如 影响行数/返回记录数）',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L4'    COMMENT '数据分级：L4（机密）',
    retention_days     INT           NOT NULL DEFAULT 180    COMMENT '保留天数（≥180）',
    created_at         DATETIME      NOT NULL                COMMENT '日志创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (log_id, operate_time)
COMMENT '审计日志表 | 数据分级=L4 | 操作人/类型/对象/结果/IP | 不可篡改保留≥180天'
PARTITION BY RANGE (operate_time) ()
DISTRIBUTED BY HASH (log_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-730',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  audit_log                       IS '审计日志表 | 数据分级=L4 | 机密';
COMMENT ON COLUMN audit_log.operate_type         IS '操作类型：LOGIN/LOGOUT/QUERY/EXPORT/MODIFY/DELETE/GRANT/REVOKE/ACCESS';
COMMENT ON COLUMN audit_log.operate_result       IS '操作结果：SUCCESS/FAILURE/DENIED';
COMMENT ON COLUMN audit_log.data_classification  IS '数据分级：L4（机密）';

-- -----------------------------------------------------------------------------
-- 4. access_control_policy : 访问控制策略表
--    业务含义：基于数据分级的访问控制策略，含角色/数据分级/操作/生效条件
--    数据分级：L2（内部业务：策略定义）
--    策略模型：ABAC（基于属性的访问控制），属性含角色/数据分级/时间/IP
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS access_control_policy (
    policy_id          VARCHAR(64)   NOT NULL                COMMENT '策略ID（业务主键）',
    policy_name        VARCHAR(128)  NOT NULL                COMMENT '策略名称',
    policy_code        VARCHAR(64)   NOT NULL                COMMENT '策略编码',
    policy_type        VARCHAR(32)   NOT NULL                COMMENT '策略类型：ALLOW-允许 / DENY-拒绝',
    subject_role       VARCHAR(64)                           COMMENT '主体角色（NULL 表示任意角色）',
    subject_department VARCHAR(128)                          COMMENT '主体部门',
    object_table       VARCHAR(64)                           COMMENT '客体表（NULL 表示任意表）',
    object_column      VARCHAR(64)                           COMMENT '客体列（NULL 表示任意列）',
    object_level       VARCHAR(8)                            COMMENT '客体数据分级：L1/L2/L3/L4',
    action             VARCHAR(32)   NOT NULL                COMMENT '操作：READ/WRITE/EXPORT/SHARE/DELETE',
    condition          VARCHAR(1024)                         COMMENT '生效条件（如 time BETWEEN 9:00 AND 18:00）',
    priority           INT           NOT NULL DEFAULT 100    COMMENT '优先级（数字越小优先级越高，DENY 优先于 ALLOW）',
    is_active          BOOLEAN                               COMMENT '是否启用：true-是 / false-否',
    description        VARCHAR(512)                          COMMENT '策略描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (policy_id, updated_at)
COMMENT '访问控制策略表 | 数据分级=L2 | ABAC模型/角色/数据分级/操作/条件'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (policy_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  access_control_policy                       IS '访问控制策略表 | 数据分级=L2 | ABAC模型';
COMMENT ON COLUMN access_control_policy.policy_type           IS '策略类型：ALLOW/DENY';
COMMENT ON COLUMN access_control_policy.object_level          IS '客体数据分级：L1/L2/L3/L4';

-- -----------------------------------------------------------------------------
-- 5. access_control_record : 访问控制决策记录表
--    业务含义：每次访问的访问控制决策记录，含策略匹配/决策结果/原因
--    数据分级：L3（秘密：访问决策记录）
--    分区策略：按 decision_time 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS access_control_record (
    record_id          VARCHAR(64)   NOT NULL                COMMENT '记录ID（业务主键）',
    decision_time      DATETIME      NOT NULL                COMMENT '决策时间',
    requester_id       VARCHAR(64)   NOT NULL                COMMENT '请求者ID',
    requester_role     VARCHAR(64)                           COMMENT '请求者角色',
    target_table       VARCHAR(64)                           COMMENT '目标表',
    target_column      VARCHAR(64)                           COMMENT '目标列',
    target_level       VARCHAR(8)                            COMMENT '目标数据分级',
    action             VARCHAR(32)   NOT NULL                COMMENT '请求操作',
    decision           VARCHAR(16)   NOT NULL                COMMENT '决策结果：ALLOW-允许 / DENY-拒绝',
    matched_policy_id  VARCHAR(64)                           COMMENT '匹配的策略ID',
    decision_reason    VARCHAR(512)                          COMMENT '决策原因',
    source_ip          VARCHAR(64)                           COMMENT '来源 IP',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L3'    COMMENT '数据分级：L3（秘密）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (record_id, decision_time)
COMMENT '访问控制决策记录表 | 数据分级=L3 | 策略匹配/决策结果/原因'
PARTITION BY RANGE (decision_time) ()
DISTRIBUTED BY HASH (record_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  access_control_record                       IS '访问控制决策记录表 | 数据分级=L3';
COMMENT ON COLUMN access_control_record.decision              IS '决策结果：ALLOW/DENY';

-- -----------------------------------------------------------------------------
-- 6. compliance_risk_alert : 合规风险预警表
--    业务含义：合规风险预警事件，含风险类型/级别/对象/处置状态
--    数据分级：L3（秘密：风险预警）
--    分区策略：按 alert_time 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS compliance_risk_alert (
    alert_id           VARCHAR(64)   NOT NULL                COMMENT '预警ID（业务主键）',
    alert_time         DATETIME      NOT NULL                COMMENT '预警时间',
    risk_type          VARCHAR(32)   NOT NULL                COMMENT '风险类型：UNAUTHORIZED_ACCESS-越权访问 / SENSITIVE_LEAK-敏感泄露 / AUDIT_ANOMALY-审计异常 / POLICY_VIOLATION-策略违规 / DATA_TAMPER-数据篡改',
    risk_level         VARCHAR(16)   NOT NULL                COMMENT '风险级别：LOW-低 / MEDIUM-中 / HIGH-高 / CRITICAL-严重',
    risk_score         DECIMAL(6,2)                          COMMENT '风险评分（0-100）',
    target_object      VARCHAR(256)                          COMMENT '风险对象（表/记录/用户/IP）',
    target_user        VARCHAR(64)                           COMMENT '涉及用户',
    description        VARCHAR(1024)                         COMMENT '风险描述',
    evidence           VARCHAR(2048)                         COMMENT '证据（JSON：日志片段/操作记录）',
    handling_status    VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT '处置状态：OPEN-待处理 / PROCESSING-处理中 / RESOLVED-已解决 / IGNORED-已忽略',
    handled_by         VARCHAR(64)                           COMMENT '处理人',
    handled_time       DATETIME                              COMMENT '处理时间',
    handling_remark    VARCHAR(512)                          COMMENT '处理备注',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L3'    COMMENT '数据分级：L3（秘密）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (alert_id, alert_time)
COMMENT '合规风险预警表 | 数据分级=L3 | 风险类型/级别/对象/处置状态'
PARTITION BY RANGE (alert_time) ()
DISTRIBUTED BY HASH (alert_id) BUCKETS 8
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  compliance_risk_alert                       IS '合规风险预警表 | 数据分级=L3';
COMMENT ON COLUMN compliance_risk_alert.risk_type            IS '风险类型：UNAUTHORIZED_ACCESS/SENSITIVE_LEAK/AUDIT_ANOMALY/POLICY_VIOLATION/DATA_TAMPER';
COMMENT ON COLUMN compliance_risk_alert.risk_level           IS '风险级别：LOW/MEDIUM/HIGH/CRITICAL';

-- -----------------------------------------------------------------------------
-- 7. compliance_check_record : 合规检查记录表
--    业务含义：定期合规检查的执行记录，含检查项/结果/不符合项
--    数据分级：L2（内部业务：检查记录）
--    分区策略：按 check_time 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS compliance_check_record (
    check_id           VARCHAR(64)   NOT NULL                COMMENT '检查ID（业务主键）',
    check_time         DATETIME      NOT NULL                COMMENT '检查时间',
    check_type         VARCHAR(32)   NOT NULL                COMMENT '检查类型：DATA_CLASSIFICATION-数据分级 / DESENSITIZE-脱敏 / AUDIT-审计 / ACCESS_CONTROL-访问控制 / RETENTION-保留期',
    check_item         VARCHAR(128)  NOT NULL                COMMENT '检查项',
    check_scope        VARCHAR(256)                          COMMENT '检查范围（表/字段/用户）',
    check_result       VARCHAR(16)   NOT NULL                COMMENT '检查结果：PASS-通过 / FAIL-不通过 / WARNING-警告',
    non_compliant_count INT                                  COMMENT '不符合项数',
    non_compliant_items VARCHAR(4096)                         COMMENT '不符合项明细（JSON 数组）',
    check_summary      VARCHAR(1024)                         COMMENT '检查摘要',
    checker            VARCHAR(64)                           COMMENT '检查人（或自动检查标识）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (check_id, check_time)
COMMENT '合规检查记录表 | 数据分级=L2 | 检查项/结果/不符合项'
PARTITION BY RANGE (check_time) ()
DISTRIBUTED BY HASH (check_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  compliance_check_record                       IS '合规检查记录表 | 数据分级=L2';
COMMENT ON COLUMN compliance_check_record.check_type            IS '检查类型：DATA_CLASSIFICATION/DESENSITIZE/AUDIT/ACCESS_CONTROL/RETENTION';
COMMENT ON COLUMN compliance_check_record.check_result          IS '检查结果：PASS/FAIL/WARNING';

-- -----------------------------------------------------------------------------
-- 8. compliance_policy : 合规策略定义表
--    业务含义：合规策略定义，含策略编码/名称/检查规则/生效范围
--    数据分级：L2（内部业务：策略定义）
--    分区策略：按 updated_at 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS compliance_policy (
    policy_id          VARCHAR(64)   NOT NULL                COMMENT '策略ID（业务主键）',
    policy_code        VARCHAR(64)   NOT NULL                COMMENT '策略编码',
    policy_name        VARCHAR(128)  NOT NULL                COMMENT '策略名称',
    policy_category    VARCHAR(32)   NOT NULL                COMMENT '策略类别：DATA_CLASSIFICATION-数据分级 / DESENSITIZE-脱敏 / AUDIT-审计 / ACCESS_CONTROL-访问控制 / RETENTION-保留期',
    check_rule         VARCHAR(2048)                         COMMENT '检查规则（JSON/SQL 表达式）',
    check_frequency    VARCHAR(32)                           COMMENT '检查频率：REALTIME-实时 / DAILY-每日 / WEEKLY-每周 / MONTHLY-每月',
    severity           VARCHAR(16)                           COMMENT '违规严重级别：LOW/MEDIUM/HIGH/CRITICAL',
    applicable_scope   VARCHAR(1024)                         COMMENT '适用范围（JSON：表/字段/角色）',
    standard_reference VARCHAR(128)                          COMMENT '标准引用（如 GB/T 31075-2017/等保2.0）',
    is_active          BOOLEAN                               COMMENT '是否启用：true-是 / false-否',
    description        VARCHAR(512)                          COMMENT '策略描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (policy_id, updated_at)
COMMENT '合规策略定义表 | 数据分级=L2 | 策略编码/检查规则/频率/严重级别'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (policy_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  compliance_policy                       IS '合规策略定义表 | 数据分级=L2';
COMMENT ON COLUMN compliance_policy.policy_category       IS '策略类别：DATA_CLASSIFICATION/DESENSITIZE/AUDIT/ACCESS_CONTROL/RETENTION';

-- =============================================================================
-- End of 04_government_compliance_ddl.sql
-- =============================================================================