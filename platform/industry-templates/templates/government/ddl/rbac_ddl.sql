-- =============================================================================
-- File   : rbac_ddl.sql
-- Domain : 政务行业 RBAC 域（Role-Based Access Control）
-- Engine : Apache Doris（主）
-- Charset: UTF-8
-- Source : 政务行业模板 RBAC 业务模型
-- Class  : 数据分级 L2(内部) / L4(机密：权限定义)
-- Tables : gov_role / gov_permission / gov_role_permission / gov_user_role (4 张)
-- Notice : Doris 不强制外键，关联关系以注释说明
-- 合规   : 权限定义属敏感配置，变更需审计
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. gov_role : 政务行业角色定义表
--    业务含义：政务行业模板角色定义，含角色编码/名称/描述/数据分级
--    数据分级：L2（内部业务：角色定义）
--    角色：gov_admin-政务管理员 / data_analyst-数据分析师 / dept_user-部门用户 / auditor-审计员 / public_user-公众用户
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gov_role (
    role_id            VARCHAR(64)   NOT NULL                COMMENT '角色ID（业务主键）',
    role_code          VARCHAR(64)   NOT NULL                COMMENT '角色编码（唯一，如 gov_admin/data_analyst）',
    role_name          VARCHAR(128)  NOT NULL                COMMENT '角色名称（如 政务管理员/数据分析师）',
    description        VARCHAR(512)                          COMMENT '角色描述',
    role_type          VARCHAR(32)   NOT NULL                COMMENT '角色类型：ADMIN-管理 / BUSINESS-业务 / AUDIT-审计 / PUBLIC-公众',
    data_classification VARCHAR(8)                          COMMENT '可访问最高数据分级：L1/L2/L3/L4',
    is_active          BOOLEAN       NOT NULL DEFAULT true   COMMENT '是否启用：true-是 / false-否',
    is_built_in        BOOLEAN                               COMMENT '是否内置角色（不可删除）：true-是 / false-否',
    max_session_count  INT                                   COMMENT '最大并发会话数',
    session_timeout    INT                                   COMMENT '会话超时（秒）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间',
    created_by         VARCHAR(64)   NOT NULL                COMMENT '创建人'
)
ENGINE = OLAP
DUPLICATE KEY (role_id, updated_at)
COMMENT '政务行业角色定义表 | 数据分级=L2 | 5角色：政务管理员/数据分析师/部门用户/审计员/公众用户'
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
COMMENT ON TABLE  gov_role                       IS '政务行业角色定义表 | 数据分级=L2';
COMMENT ON COLUMN gov_role.role_code             IS '角色编码：gov_admin/data_analyst/dept_user/auditor/public_user';
COMMENT ON COLUMN gov_role.data_classification   IS '可访问最高数据分级：L1/L2/L3/L4';

-- -----------------------------------------------------------------------------
-- 2. gov_permission : 政务行业权限定义表
--    业务含义：权限定义，含权限编码/名称/资源/操作
--    数据分级：L4（机密：权限定义，最高保护）
--    资源类型：table / dag / dashboard / compliance
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gov_permission (
    permission_id      VARCHAR(64)   NOT NULL                COMMENT '权限ID（业务主键）',
    permission_code    VARCHAR(128)  NOT NULL                COMMENT '权限编码（唯一，如 perm_table_population_base_read）',
    permission_name    VARCHAR(256)  NOT NULL                COMMENT '权限名称',
    resource_type      VARCHAR(32)   NOT NULL                COMMENT '资源类型：table-表 / dag-调度作业 / dashboard-仪表盘 / compliance-合规资源',
    resource_name      VARCHAR(128)  NOT NULL                COMMENT '资源名称（表名/DAG名/Dashboard名）',
    resource_domain    VARCHAR(32)                           COMMENT '资源所属域：population/economic/livelihood/compliance/rbac',
    action             VARCHAR(32)   NOT NULL                COMMENT '操作：read-读 / write-写 / manage-管理 / view-查看 / export-导出',
    sql_expression     VARCHAR(512)                          COMMENT 'SQL 权限表达（如 SELECT ON population_base）',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L4'    COMMENT '数据分级：L4（机密）',
    description        VARCHAR(512)                          COMMENT '权限描述',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间',
    updated_at         DATETIME      NOT NULL                COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (permission_id, updated_at)
COMMENT '政务行业权限定义表 | 数据分级=L4 | 权限编码/资源/操作'
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
COMMENT ON TABLE  gov_permission                       IS '政务行业权限定义表 | 数据分级=L4 | 机密';
COMMENT ON COLUMN gov_permission.resource_type         IS '资源类型：table/dag/dashboard/compliance';
COMMENT ON COLUMN gov_permission.action                IS '操作：read/write/manage/view/export';

-- -----------------------------------------------------------------------------
-- 3. gov_role_permission : 角色-权限关联表
--    业务含义：角色与权限的多对多关联
--    数据分级：L4（机密：权限关联）
--    外键关系：role_id -> gov_role.role_id; permission_id -> gov_permission.permission_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gov_role_permission (
    id                 VARCHAR(64)   NOT NULL                COMMENT '关联ID（业务主键）',
    role_id            VARCHAR(64)   NOT NULL                COMMENT '角色ID（外键 -> gov_role.role_id）',
    role_code          VARCHAR(64)   NOT NULL                COMMENT '角色编码（冗余，便于查询）',
    permission_id      VARCHAR(64)   NOT NULL                COMMENT '权限ID（外键 -> gov_permission.permission_id）',
    permission_code    VARCHAR(128)  NOT NULL                COMMENT '权限编码（冗余，便于查询）',
    grant_type         VARCHAR(16)   NOT NULL DEFAULT 'GRANT' COMMENT '授权类型：GRANT-授予 / REVOKE-收回',
    granted_by         VARCHAR(64)   NOT NULL                COMMENT '授权人',
    granted_at         DATETIME      NOT NULL                COMMENT '授权时间',
    expires_at         DATETIME                              COMMENT '过期时间（NULL 表示永久）',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L4'    COMMENT '数据分级：L4（机密）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (id, role_id)
COMMENT '角色-权限关联表 | 数据分级=L4 | 角色×权限多对多'
PARTITION BY RANGE (granted_at) ()
DISTRIBUTED BY HASH (id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  gov_role_permission                       IS '角色-权限关联表 | 数据分级=L4';
COMMENT ON COLUMN gov_role_permission.grant_type            IS '授权类型：GRANT/REVOKE';

-- -----------------------------------------------------------------------------
-- 4. gov_user_role : 用户-角色关联表
--    业务含义：用户与角色的多对多关联，含用户所属部门
--    数据分级：L3（秘密：用户角色信息）
--    外键关系：role_id -> gov_role.role_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gov_user_role (
    id                 VARCHAR(64)   NOT NULL                COMMENT '关联ID（业务主键）',
    user_id            VARCHAR(64)   NOT NULL                COMMENT '用户ID（工号/统一身份ID）',
    user_name          VARCHAR(128)                          COMMENT '用户姓名',
    user_department    VARCHAR(128)                          COMMENT '用户所属部门',
    role_id            VARCHAR(64)   NOT NULL                COMMENT '角色ID（外键 -> gov_role.role_id）',
    role_code          VARCHAR(64)   NOT NULL                COMMENT '角色编码（冗余）',
    assigned_by        VARCHAR(64)   NOT NULL                COMMENT '分配人',
    assigned_at        DATETIME      NOT NULL                COMMENT '分配时间',
    expires_at         DATETIME                              COMMENT '过期时间（NULL 表示永久）',
    is_active          BOOLEAN       NOT NULL DEFAULT true   COMMENT '是否有效：true-是 / false-否',
    data_classification VARCHAR(8)  NOT NULL DEFAULT 'L3'    COMMENT '数据分级：L3（秘密）',
    created_at         DATETIME      NOT NULL                COMMENT '创建时间'
)
ENGINE = OLAP
DUPLICATE KEY (id, user_id)
COMMENT '用户-角色关联表 | 数据分级=L3 | 用户×角色多对多'
PARTITION BY RANGE (assigned_at) ()
DISTRIBUTED BY HASH (id) BUCKETS 4
PROPERTIES (
    'dynamic_partition.enable' = 'true',
    'dynamic_partition.time_unit' = 'DAY',
    'dynamic_partition.start' = '-3650',
    'dynamic_partition.end' = '3',
    'dynamic_partition.prefix' = 'p',
    'dynamic_partition.replication_allocation' = 'tag.location.default: 3',
    'replication_num' = '3'
);
COMMENT ON TABLE  gov_user_role                       IS '用户-角色关联表 | 数据分级=L3';

-- =============================================================================
-- End of rbac_ddl.sql
-- =============================================================================