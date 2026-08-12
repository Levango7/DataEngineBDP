-- =============================================================================
-- File   : rbac_ddl.sql
-- Domain : 能源行业 RBAC 域（Role-Based Access Control）
-- Engine : Apache Doris
-- Charset: UTF-8
-- Source : 能源行业模板 RBAC 业务模型
-- Class  : 数据分级 L3（敏感运营：权限配置）
-- Tables : energy_role / energy_permission / energy_role_permission /
--          energy_user_role（4 张）
-- Notice : Doris 不强制外键，关联关系以注释说明
-- 角色：4 个（能源管理员 / 能源分析师 / 设备运维员 / 碳排放核算员）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. energy_role : 角色表
--    业务含义：能源行业角色定义，对应 Keycloak realm role
--    数据分级：L3（敏感运营：权限配置）
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_role (
    role_id            VARCHAR(64)   NOT NULL                COMMENT '角色ID（业务主键）',
    role_code          VARCHAR(64)   NOT NULL                COMMENT '角色编码（唯一，如 energy_admin）',
    role_name          VARCHAR(128)  NOT NULL                COMMENT '角色名称',
    description        VARCHAR(512)                          COMMENT '角色描述',
    department         VARCHAR(64)                           COMMENT '所属部门',
    data_classification VARCHAR(32)                          COMMENT '数据分级：L2-内部业务 / L3-敏感运营 / L4-合规披露',
    is_composite       BOOLEAN       NOT NULL DEFAULT FALSE  COMMENT '是否组合角色',
    parent_role_ids    VARCHAR(256)                          COMMENT '父角色ID列表（逗号分隔，组合角色时使用）',
    max_session_count  INT                                   COMMENT '最大会话数',
    session_timeout_sec INT                                  COMMENT '会话超时（秒）',
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE   COMMENT '是否启用',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人',
    updated_by         VARCHAR(64)   NOT NULL                COMMENT '更新人'
)
ENGINE = OLAP
UNIQUE KEY (role_id)
COMMENT '角色表 | 数据分级=L3 | 能源行业角色定义 | 对应 Keycloak realm role'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (role_id) BUCKETS 2
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_role                  IS '角色表 | 数据分级=L3 | 能源行业角色定义';
COMMENT ON COLUMN energy_role.role_code        IS '角色编码（如 energy_admin/energy_analyst/device_operator/carbon_accountant）';
COMMENT ON COLUMN energy_role.data_classification IS '数据分级：L2/L3/L4';

-- -----------------------------------------------------------------------------
-- 2. energy_permission : 权限表
--    业务含义：权限定义，含资源类型/资源名/操作
--    数据分级：L3（敏感运营：权限配置）
--    分区策略：按 updated_at 日期动态分区
--    外键关系：无
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_permission (
    permission_id      VARCHAR(64)   NOT NULL                COMMENT '权限ID（业务主键）',
    permission_code    VARCHAR(128)  NOT NULL                COMMENT '权限编码（唯一，如 energy_device:read）',
    permission_name    VARCHAR(128)  NOT NULL                COMMENT '权限名称',
    resource_type      VARCHAR(16)   NOT NULL                COMMENT '资源类型：TABLE-表 / DAG-调度作业 / DASHBOARD-仪表盘 / API-接口 / MENU-菜单',
    resource_name      VARCHAR(128)  NOT NULL                COMMENT '资源名（表名/DAG ID/Dashboard ID/API 路径/菜单编码）',
    operation          VARCHAR(8)    NOT NULL                COMMENT '操作：READ-读 / WRITE-写 / EXEC-执行 / ADMIN-管理',
    description        VARCHAR(512)                          COMMENT '权限描述',
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE   COMMENT '是否启用',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
UNIQUE KEY (permission_id)
COMMENT '权限表 | 数据分级=L3 | 资源类型/资源名/操作 | 表/DAG/Dashboard/API/菜单'
PARTITION BY RANGE (updated_at) ()
DISTRIBUTED BY HASH (permission_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_permission                  IS '权限表 | 数据分级=L3 | 资源类型/资源名/操作';
COMMENT ON COLUMN energy_permission.resource_type     IS '资源类型：TABLE/DAG/DASHBOARD/API/MENU';
COMMENT ON COLUMN energy_permission.operation         IS '操作：READ/WRITE/EXEC/ADMIN';

-- -----------------------------------------------------------------------------
-- 3. energy_role_permission : 角色-权限关联表
--    业务含义：角色与权限的多对多关联
--    数据分级：L3（敏感运营：权限配置）
--    分区策略：按 created_at 日期动态分区
--    外键关系：role_id -> energy_role.role_id
--             permission_id -> energy_permission.permission_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_role_permission (
    id                 VARCHAR(64)   NOT NULL                COMMENT '关联ID（业务主键）',
    role_id            VARCHAR(64)   NOT NULL                COMMENT '角色ID（外键 -> energy_role.role_id）',
    permission_id      VARCHAR(64)   NOT NULL                COMMENT '权限ID（外键 -> energy_permission.permission_id）',
    is_denied          BOOLEAN       NOT NULL DEFAULT FALSE  COMMENT '是否拒绝权限（FALSE 表示授予，TRUE 表示显式拒绝）',
    description        VARCHAR(256)                          COMMENT '关联描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人'
)
ENGINE = OLAP
UNIQUE KEY (id)
COMMENT '角色-权限关联表 | 数据分级=L3 | 多对多关联 | 支持显式拒绝'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (role_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_role_permission            IS '角色-权限关联表 | 数据分级=L3 | 多对多关联';
COMMENT ON COLUMN energy_role_permission.is_denied  IS '是否拒绝权限（FALSE 表示授予，TRUE 表示显式拒绝）';

-- -----------------------------------------------------------------------------
-- 4. energy_user_role : 用户-角色关联表
--    业务含义：用户与角色的多对多关联
--    数据分级：L3（敏感运营：权限配置）
--    分区策略：按 created_at 日期动态分区
--    外键关系：role_id -> energy_role.role_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS energy_user_role (
    id                 VARCHAR(64)   NOT NULL                COMMENT '关联ID（业务主键）',
    user_id            VARCHAR(64)   NOT NULL                COMMENT '用户ID（Keycloak sub）',
    username           VARCHAR(128)                          COMMENT '用户名（冗余）',
    role_id            VARCHAR(64)   NOT NULL                COMMENT '角色ID（外键 -> energy_role.role_id）',
    role_code          VARCHAR(64)                           COMMENT '角色编码（冗余）',
    tenant_id          VARCHAR(64)                           COMMENT '租户ID',
    effective_from     DATETIME                             COMMENT '生效起始时间',
    effective_to       DATETIME                             COMMENT '生效结束时间（空表示长期有效）',
    assigned_by        VARCHAR(64)                           COMMENT '授权人',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
UNIQUE KEY (id)
COMMENT '用户-角色关联表 | 数据分级=L3 | 多对多关联 | 支持租户与有效期'
PARTITION BY RANGE (created_at) ()
DISTRIBUTED BY HASH (user_id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  energy_user_role          IS '用户-角色关联表 | 数据分级=L3 | 多对多关联';
COMMENT ON COLUMN energy_user_role.tenant_id IS '租户ID';

-- =============================================================================
-- RBAC 域 DDL 完成：4 张表
--   energy_role / energy_permission / energy_role_permission / energy_user_role
-- 角色：4 个（能源管理员 / 能源分析师 / 设备运维员 / 碳排放核算员）
-- =============================================================================