-- =============================================================================
-- File   : rbac_ddl.sql
-- Domain : 制造行业模板 RBAC 域 (Role-Based Access Control)
-- Engine : Apache Doris (元数据存储) / Keycloak (权限决策)
-- Charset: UTF-8
-- Tables : mfg_role / mfg_permission / mfg_role_permission / mfg_user_role (4 张)
-- Notice : RBAC 角色定义与权限矩阵，同步至 Keycloak realm
--          角色：workshop_director-车间主任 / quality_engineer-质量员 /
--                supply_chain_manager-供应链经理 / equipment_engineer-设备工程师
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. mfg_role : 角色定义表
--    业务含义：制造模板角色定义，含角色编码/名称/描述/数据分级
--    数据分级：L2 (内部业务：RBAC 元数据)
--    分区策略：按 updated_at 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mfg_role (
    role_id         VARCHAR(64)   NOT NULL                COMMENT '角色ID（业务主键）',
    role_code       VARCHAR(64)   NOT NULL                COMMENT '角色编码（唯一业务编码）',
    role_name       VARCHAR(128)  NOT NULL                COMMENT '角色名称',
    description     VARCHAR(512)                           COMMENT '角色描述',
    department      VARCHAR(64)                            COMMENT '所属部门',
    data_classification VARCHAR(16)                       COMMENT '数据分级：internal-内部 / confidential-机密 / restricted-受限',
    is_composite    BOOLEAN                DEFAULT FALSE   COMMENT '是否复合角色',
    max_session_count INT                                  COMMENT '最大会话数',
    session_timeout_sec INT                                COMMENT '会话超时（秒）',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-启用 / INACTIVE-停用',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人',
    updated_by      VARCHAR(64)   NOT NULL                 COMMENT '更新人'
)
ENGINE = OLAP
DUPLICATE KEY (role_id, updated_at)
COMMENT '制造模板角色定义表 | 数据分级=L2 | RBAC 角色元数据 | 同步至 Keycloak realm'
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
COMMENT ON TABLE  mfg_role             IS '制造模板角色定义表 | 数据分级=L2 | RBAC 角色元数据';
COMMENT ON COLUMN mfg_role.role_code    IS '角色编码（唯一业务编码）';
COMMENT ON COLUMN mfg_role.data_classification IS '数据分级：internal/confidential/restricted';

-- -----------------------------------------------------------------------------
-- 2. mfg_permission : 权限定义表
--    业务含义：制造模板权限定义，含权限编码/名称/资源类型/操作
--    数据分级：L2 (内部业务：RBAC 元数据)
--    分区策略：按 updated_at 日期动态分区
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mfg_permission (
    permission_id   VARCHAR(64)   NOT NULL                COMMENT '权限ID（业务主键）',
    permission_code VARCHAR(128)  NOT NULL                COMMENT '权限编码（唯一，如 table:equipment:read）',
    permission_name VARCHAR(128)  NOT NULL                COMMENT '权限名称',
    resource_type   VARCHAR(32)   NOT NULL                COMMENT '资源类型：table-表 / dag-调度作业 / dashboard-仪表盘',
    resource_name   VARCHAR(128)  NOT NULL                COMMENT '资源名称（表名/DAG名/Dashboard名）',
    action          VARCHAR(16)   NOT NULL                COMMENT '操作：read-只读 / write-读写 / manage-管理 / view-查看',
    scope           VARCHAR(64)   NOT NULL DEFAULT 'manufacturing_template' COMMENT '权限范围',
    sql_equivalent  VARCHAR(256)                           COMMENT 'SQL 等价表达（如 SELECT ON equipment）',
    description     VARCHAR(512)                           COMMENT '权限描述',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL                 COMMENT '更新时间'
)
ENGINE = OLAP
DUPLICATE KEY (permission_id, updated_at)
COMMENT '制造模板权限定义表 | 数据分级=L2 | RBAC 权限元数据 | 基于资源的权限模型'
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
COMMENT ON TABLE  mfg_permission                IS '制造模板权限定义表 | 数据分级=L2 | RBAC 权限元数据';
COMMENT ON COLUMN mfg_permission.resource_type   IS '资源类型：table/dag/dashboard';
COMMENT ON COLUMN mfg_permission.action          IS '操作：read/write/manage/view';

-- -----------------------------------------------------------------------------
-- 3. mfg_role_permission : 角色权限关联表
--    业务含义：角色与权限的多对多关联
--    数据分级：L2 (内部业务：RBAC 授权关系)
--    分区策略：按 created_at 日期动态分区
--    外键关系：role_id -> mfg_role.role_id；permission_id -> mfg_permission.permission_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mfg_role_permission (
    rp_id           VARCHAR(64)   NOT NULL                COMMENT '关联ID（业务主键）',
    role_id         VARCHAR(64)   NOT NULL                COMMENT '角色ID（外键 -> mfg_role.role_id）',
    role_code       VARCHAR(64)   NOT NULL                COMMENT '角色编码（冗余）',
    permission_id   VARCHAR(64)   NOT NULL                COMMENT '权限ID（外键 -> mfg_permission.permission_id）',
    permission_code VARCHAR(128)  NOT NULL                COMMENT '权限编码（冗余）',
    is_granted      BOOLEAN       NOT NULL DEFAULT TRUE   COMMENT '是否授予（TRUE=授权 / FALSE=拒绝）',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人'
)
ENGINE = OLAP
DUPLICATE KEY (rp_id, created_at)
COMMENT '角色权限关联表 | 数据分级=L2 | RBAC 授权关系 | 外键：role_id -> mfg_role；permission_id -> mfg_permission'
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
COMMENT ON TABLE  mfg_role_permission               IS '角色权限关联表 | 数据分级=L2 | RBAC 授权关系';
COMMENT ON COLUMN mfg_role_permission.role_id       IS '角色ID（外键 -> mfg_role.role_id）';
COMMENT ON COLUMN mfg_role_permission.permission_id  IS '权限ID（外键 -> mfg_permission.permission_id）';
COMMENT ON COLUMN mfg_role_permission.is_granted     IS '是否授予（TRUE=授权 / FALSE=拒绝）';

-- -----------------------------------------------------------------------------
-- 4. mfg_user_role : 用户角色关联表
--    业务含义：用户与角色的多对多关联
--    数据分级：L2 (内部业务：RBAC 用户授权）
--    分区策略：按 created_at 日期动态分区
--    外键关系：role_id -> mfg_role.role_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mfg_user_role (
    ur_id           VARCHAR(64)   NOT NULL                COMMENT '关联ID（业务主键）',
    user_id         VARCHAR(64)   NOT NULL                COMMENT '用户ID（工号）',
    user_name       VARCHAR(64)                            COMMENT '用户姓名（冗余）',
    role_id         VARCHAR(64)   NOT NULL                COMMENT '角色ID（外键 -> mfg_role.role_id）',
    role_code       VARCHAR(64)   NOT NULL                COMMENT '角色编码（冗余）',
    dept_code       VARCHAR(64)                            COMMENT '所属部门编码',
    valid_from      DATETIME                               COMMENT '授权生效时间',
    valid_to        DATETIME                               COMMENT '授权失效时间',
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-有效 / INACTIVE-失效',
    created_at      DATETIME      NOT NULL                 COMMENT '创建时间',
    created_by      VARCHAR(64)   NOT NULL                 COMMENT '创建人'
)
ENGINE = OLAP
DUPLICATE KEY (ur_id, created_at)
COMMENT '用户角色关联表 | 数据分级=L2 | RBAC 用户授权 | 外键：role_id -> mfg_role.role_id'
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
COMMENT ON TABLE  mfg_user_role         IS '用户角色关联表 | 数据分级=L2 | RBAC 用户授权';
COMMENT ON COLUMN mfg_user_role.role_id  IS '角色ID（外键 -> mfg_role.role_id）';

-- =============================================================================
-- RBAC DDL 完成：共 4 张表
-- mfg_role / mfg_permission / mfg_role_permission / mfg_user_role
-- 角色：workshop_director-车间主任 / quality_engineer-质量员 /
--       supply_chain_manager-供应链经理 / equipment_engineer-设备工程师
-- 同步至 Keycloak realm: manufacturing-template-realm
-- =============================================================================

-- =============================================================================
-- 制造行业模板 DDL 汇总（共 25 张表，≥ 15 张表要求满足）
-- =============================================================================
-- OEE 域 (7 张): equipment / production_line / shift / equipment_status_log /
--                equipment_oee_daily / equipment_oee_shift / equipment_sensor_metric
-- 质量追溯域 (7 张): product_batch / work_order / process_route / process_record /
--                    quality_parameter / defect_record / quality_trace_link
-- 供应链协同域 (7 张): supplier / purchase_order / inventory / inventory_movement /
--                      sales_order / logistics_shipment / supply_chain_event
-- RBAC 域 (4 张): mfg_role / mfg_permission / mfg_role_permission / mfg_user_role
-- =============================================================================