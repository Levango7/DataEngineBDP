-- =============================================================================
-- File   : rbac_ddl.sql
-- Domain : 零售行业 RBAC 域 (Role-Based Access Control)
-- Engine : Apache Doris
-- Charset: UTF-8
-- Source : T038 零售行业模板 - RBAC（店长/运营/数据分析师）
-- Tables : retail_role / retail_permission / retail_role_permission /
--          retail_user_role / retail_role_dashboard / retail_audit_log (6 张)
-- Notice : Doris 不强制外键，关联关系以注释说明
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. retail_role : 零售行业角色表
--    业务含义：店长 / 运营 / 数据分析师 三大角色
--    数据分级：L2 (内部业务)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS retail_role (
    role_id         VARCHAR(64)   NOT NULL                COMMENT '角色ID（业务主键）',
    role_code       VARCHAR(64)   NOT NULL                COMMENT '角色编码（唯一，如 store_manager / operations / data_analyst）',
    role_name       VARCHAR(128)  NOT NULL                COMMENT '角色名称',
    description     VARCHAR(512)                          COMMENT '角色描述',
    is_composite    BOOLEAN       NOT NULL DEFAULT FALSE  COMMENT '是否复合角色',
    parent_role_id  VARCHAR(64)                           COMMENT '父角色ID（继承关系）',
    data_classification VARCHAR(16)                       COMMENT '数据分级：PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '角色状态：ACTIVE-启用 / INACTIVE-停用',
    created_at      DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                COMMENT '创建人',
    updated_by      VARCHAR(64)   NOT NULL                COMMENT '更新人'
)
ENGINE = OLAP
DUPLICATE KEY (role_id)
COMMENT '零售行业角色表 | 数据分级=L2 | 店长/运营/数据分析师'
DISTRIBUTED BY HASH (role_id) BUCKETS 4
PROPERTIES ('replication_num' = '3');
COMMENT ON TABLE  retail_role              IS '零售行业角色表 | 店长/运营/数据分析师';
COMMENT ON COLUMN retail_role.role_code    IS '角色编码（store_manager/operations/data_analyst）';
COMMENT ON COLUMN retail_role.is_composite IS '是否复合角色';

-- -----------------------------------------------------------------------------
-- 2. retail_permission : 零售行业权限表
--    业务含义：表级读写/DAG 管理/Dashboard 查看等权限定义
--    数据分级：L2 (内部业务)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS retail_permission (
    permission_id       VARCHAR(64)   NOT NULL            COMMENT '权限ID（业务主键）',
    permission_code     VARCHAR(128)  NOT NULL            COMMENT '权限编码（如 product:read / member_rfm:write / dag:manage）',
    permission_name     VARCHAR(128)  NOT NULL            COMMENT '权限名称',
    permission_type     VARCHAR(16)   NOT NULL            COMMENT '权限类型：TABLE-表级 / DAG-调度 / DASHBOARD-仪表盘 / API-接口',
    resource_type       VARCHAR(32)                       COMMENT '资源类型：TABLE / DAG / DASHBOARD / API_ENDPOINT',
    resource_code       VARCHAR(64)                       COMMENT '资源编码（表名/DAG名/Dashboard slug）',
    action              VARCHAR(16)   NOT NULL            COMMENT '操作：READ-读 / WRITE-写 / MANAGE-管理 / EXECUTE-执行',
    description         VARCHAR(256)                      COMMENT '权限描述',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间',
    updated_at          DATETIME      NOT NULL            COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (permission_id)
COMMENT '零售行业权限表 | 数据分级=L2 | 表级读写/DAG管理/Dashboard查看'
DISTRIBUTED BY HASH (permission_id) BUCKETS 4
PROPERTIES ('replication_num' = '3');
COMMENT ON TABLE  retail_permission                  IS '零售行业权限表';
COMMENT ON COLUMN retail_permission.permission_type  IS '权限类型：TABLE/DAG/DASHBOARD/API';
COMMENT ON COLUMN retail_permission.action           IS '操作：READ/WRITE/MANAGE/EXECUTE';

-- -----------------------------------------------------------------------------
-- 3. retail_role_permission : 角色-权限映射表
--    业务含义：角色与权限的多对多关系
--    数据分级：L2 (内部业务)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS retail_role_permission (
    mapping_id          VARCHAR(64)   NOT NULL            COMMENT '映射ID（业务主键）',
    role_id             VARCHAR(64)   NOT NULL            COMMENT '角色ID（外键 -> retail_role.role_id）',
    permission_id       VARCHAR(64)   NOT NULL            COMMENT '权限ID（外键 -> retail_permission.permission_id）',
    is_granted          BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '是否授予权限（TRUE-授予 / FALSE-拒绝）',
    grant_reason        VARCHAR(256)                      COMMENT '授权原因',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间',
    created_by          VARCHAR(64)   NOT NULL            COMMENT '创建人'
)
ENGINE = OLAP
DUPLICATE KEY (mapping_id)
COMMENT '角色-权限映射表 | 数据分级=L2 | 角色与权限多对多关系'
DISTRIBUTED BY HASH (role_id) BUCKETS 4
PROPERTIES ('replication_num' = '3');
COMMENT ON TABLE  retail_role_permission              IS '角色-权限映射表';
COMMENT ON COLUMN retail_role_permission.is_granted   IS '是否授予权限（TRUE/FALSE）';

-- -----------------------------------------------------------------------------
-- 4. retail_user_role : 用户-角色映射表
--    业务含义：用户与角色的多对多关系
--    数据分级：L2 (内部业务)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS retail_user_role (
    user_role_id        VARCHAR(64)   NOT NULL            COMMENT '用户角色ID（业务主键）',
    user_id             VARCHAR(64)   NOT NULL            COMMENT '用户ID（Keycloak 用户ID）',
    role_id             VARCHAR(64)   NOT NULL            COMMENT '角色ID（外键 -> retail_role.role_id）',
    store_id            VARCHAR(64)                       COMMENT '门店ID（店长角色适用，限定数据范围）',
    valid_from          DATETIME      NOT NULL            COMMENT '生效开始时间',
    valid_to            DATETIME                          COMMENT '生效结束时间（NULL 表示永久）',
    is_active           BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '是否有效',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间',
    created_by          VARCHAR(64)   NOT NULL            COMMENT '创建人'
)
ENGINE = OLAP
DUPLICATE KEY (user_role_id)
COMMENT '用户-角色映射表 | 数据分级=L2 | 用户与角色多对多关系'
DISTRIBUTED BY HASH (user_id) BUCKETS 8
PROPERTIES ('replication_num' = '3');
COMMENT ON TABLE  retail_user_role           IS '用户-角色映射表';
COMMENT ON COLUMN retail_user_role.store_id  IS '门店ID（店长角色适用，限定数据范围）';

-- -----------------------------------------------------------------------------
-- 5. retail_role_dashboard : 角色-Dashboard 映射表
--    业务含义：角色可访问的 Dashboard 列表
--    数据分级：L2 (内部业务)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS retail_role_dashboard (
    mapping_id          VARCHAR(64)   NOT NULL            COMMENT '映射ID（业务主键）',
    role_id             VARCHAR(64)   NOT NULL            COMMENT '角色ID（外键 -> retail_role.role_id）',
    dashboard_slug      VARCHAR(64)   NOT NULL            COMMENT 'Dashboard slug（如 product-profile-dashboard / member-dashboard / marketing-dashboard）',
    access_level        VARCHAR(16)   NOT NULL            COMMENT '访问级别：VIEW-查看 / EDIT-编辑 / ADMIN-管理',
    is_default          BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '是否默认 Dashboard（登录后首页）',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (mapping_id)
COMMENT '角色-Dashboard 映射表 | 数据分级=L2 | 角色可访问的 Dashboard'
DISTRIBUTED BY HASH (role_id) BUCKETS 4
PROPERTIES ('replication_num' = '3');
COMMENT ON TABLE  retail_role_dashboard                IS '角色-Dashboard 映射表';
COMMENT ON COLUMN retail_role_dashboard.dashboard_slug IS 'Dashboard slug';
COMMENT ON COLUMN retail_role_dashboard.access_level   IS '访问级别：VIEW/EDIT/ADMIN';

-- -----------------------------------------------------------------------------
-- 6. retail_audit_log : 零售行业审计日志表
--    业务含义：用户操作审计日志（数据访问/修改/导出等）
--    数据分级：L2 (内部业务)
--    分区策略：按 operated_at 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS retail_audit_log (
    log_id              VARCHAR(64)   NOT NULL            COMMENT '日志ID（业务主键）',
    user_id             VARCHAR(64)   NOT NULL            COMMENT '用户ID',
    role_id             VARCHAR(64)                       COMMENT '角色ID',
    operation_type      VARCHAR(32)   NOT NULL            COMMENT '操作类型：LOGIN / QUERY / INSERT / UPDATE / DELETE / EXPORT / DAG_RUN / DASHBOARD_VIEW',
    resource_type       VARCHAR(32)                       COMMENT '资源类型：TABLE / DAG / DASHBOARD / API',
    resource_code       VARCHAR(64)                       COMMENT '资源编码',
    operation_detail    STRING                            COMMENT '操作详情 JSON',
    operation_status    VARCHAR(16)                       COMMENT '操作状态：SUCCESS / FAILED / DENIED',
    ip_address          VARCHAR(64)                       COMMENT 'IP 地址',
    user_agent          VARCHAR(256)                      COMMENT 'User-Agent',
    operated_at         DATETIME      NOT NULL            COMMENT '操作时间',
    duration_ms         INT                               COMMENT '操作耗时（毫秒）',
    created_at          DATETIME      NOT NULL            COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (log_id, operated_at)
COMMENT '零售行业审计日志表 | 数据分级=L2 | 用户操作审计'
PARTITION BY RANGE (operated_at) ()
DISTRIBUTED BY HASH (user_id) BUCKETS 16
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-365',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  retail_audit_log                  IS '零售行业审计日志表';
COMMENT ON COLUMN retail_audit_log.operation_type   IS '操作类型：LOGIN/QUERY/INSERT/UPDATE/DELETE/EXPORT/DAG_RUN/DASHBOARD_VIEW';
COMMENT ON COLUMN retail_audit_log.operation_status IS '操作状态：SUCCESS/FAILED/DENIED';

-- =============================================================================
-- 零售行业 RBAC DDL 完成：6 张表
-- retail_role / retail_permission / retail_role_permission /
-- retail_user_role / retail_role_dashboard / retail_audit_log
-- =============================================================================