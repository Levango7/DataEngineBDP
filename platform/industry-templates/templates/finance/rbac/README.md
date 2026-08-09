# 金融行业模板 - RBAC 角色权限定义说明

> 归属：数据引擎大数据平台 · L5.3 行业应用模板 · 金融模板（finance）
> 任务：T018-5 RBAC 角色权限定义与模板打包
> 上游：T018-2 DDL（21 张表）、T018-3 DAG（5 个调度作业）、T018-4 Dashboard（3 个仪表盘）
> 格式：Keycloak realm role + Resource-Based Permission
> 字符集：UTF-8

## 第1章 概述

本目录定义金融行业模板的 **RBAC（Role-Based Access Control，基于角色的访问控制）** 角色权限体系，基于 T018-2 完成的 21 张 DDL 表、T018-3 完成的 5 个 DAG 调度作业、T018-4 完成的 3 个 Superset Dashboard，设计 **3 个角色**（风控员 / 合规员 / 客户经理），遵循 **最小权限原则（Least Privilege Principle）**，每个角色仅授予完成其业务职责所需的最小权限集合。

RBAC 定义采用 **Keycloak realm role 格式**，包含 roles（角色定义）、permissions（权限定义）、role-permissions（角色-权限映射）三部分，可直接导入 Keycloak 作为统一身份认证与授权中心。

### 1.1 文件清单

| 序号 | 文件 | 用途 | 行数 |
|---|---|---|---|
| 1 | `roles.yaml` | 角色定义（3 个角色 + Keycloak realm 配置） | ~150 |
| 2 | `permissions.yaml` | 权限定义（55 个权限 + 29 个资源） | ~400 |
| 3 | `role-permissions.yaml` | 角色-权限映射（73 个映射 + 权限矩阵） | ~430 |
| 4 | `README.md` | 本说明文档 | - |

### 1.2 设计原则

- **最小权限原则（Least Privilege Principle）**：每个角色仅授予完成其业务职责所需的最小权限集合，禁止过度授权。
- **职责分离（Separation of Duties, SoD）**：风控员负责风控模型与告警处理，客户经理负责客户关系维护，合规员负责全量审计，三者职责互不重叠。
- **显式拒绝（Explicit Deny）**：对禁止访问的资源在 `denied_permissions` 中显式声明，避免隐式授权风险。
- **数据分级保护**：根据表的数据分类（restricted / confidential / internal）匹配角色属性（data_classification），实现分级访问控制。
- **审计可追溯**：合规员角色强制开启审计日志（`audit_log_required: true`），所有访问行为可追溯。

## 第2章 角色定义

### 2.1 风控员（risk_officer）

**职责**：风控模型管理、风控规则配置、风控特征计算、风控评估与告警处理。

**可访问资源**：

| 资源类型 | 资源 | 权限 |
|---|---|---|
| 表 | risk_model / risk_rule / risk_feature / risk_evaluation / risk_alert | 读写（W） |
| 表 | transaction_detail / transaction_monitor | 只读（R） |
| 表 | aml_alert | 读写（W，可处理 AML 告警） |
| 表 | credit_score | 只读（R，风控决策参考） |
| DAG | risk_feature_daily | 管理（M，上线/下线/触发/编辑） |
| DAG | transaction_aml_check | 查看（V，协同处理 AML） |
| Dashboard | risk-dashboard | 查看（V） |

**不可访问资源（显式拒绝）**：

| 资源类型 | 资源 | 拒绝原因 |
|---|---|---|
| 表 | customer / customer_profile / customer_relation / customer_tag | 客户隐私数据，风控员无需访问 |
| Dashboard | customer-dashboard | 客户视角仪表盘，与风控职责无关 |

**权限数量**：19 个（10 读写 + 4 只读 + 1 读写 + 1 只读 + 2 DAG + 1 Dashboard）

### 2.2 合规员（compliance_officer）

**职责**：合规审计、数据合规检查、监管报表生成、全量数据只读审查。

**可访问资源**：

| 资源类型 | 资源 | 权限 |
|---|---|---|
| 表 | 全部 21 张表 | 只读（R） |
| DAG | 全部 5 个 DAG | 查看（V） |
| Dashboard | 全部 3 个 Dashboard | 查看（V，合规视角覆盖风控/客户/交易） |

**不可访问资源（显式拒绝）**：

| 资源类型 | 资源 | 拒绝原因 |
|---|---|---|
| 表 | 全部 21 张表 | 拒绝 write 权限（不可修改数据） |
| DAG | 全部 5 个 DAG | 拒绝 manage 权限（不可上线/下线/触发执行） |

**特殊属性**：`audit_log_required: true`，所有访问行为强制记录审计日志。

**权限数量**：29 个（21 表只读 + 5 DAG 查看 + 3 Dashboard 查看）

### 2.3 客户经理（account_manager）

**职责**：客户关系维护、账户管理、交易查询、客户标签与画像应用。

**可访问资源**：

| 资源类型 | 资源 | 权限 |
|---|---|---|
| 表 | customer / customer_tag / customer_profile / customer_relation | 读写（W） |
| 表 | account / account_balance / account_transaction / account_status_log | 读写（W） |
| 表 | transaction / transaction_detail | 读写（W） |
| DAG | customer_tag_update | 管理（M） |
| DAG | account_eod_settlement | 查看（V） |
| Dashboard | customer-dashboard / transaction-dashboard | 查看（V） |

**不可访问资源（显式拒绝）**：

| 资源类型 | 资源 | 拒绝原因 |
|---|---|---|
| 表 | risk_model / risk_rule / risk_feature / risk_evaluation / risk_alert | 风控模型表，客户经理不可访问 |
| 表 | credit_score / loan_application / loan_contract / repayment_plan | 信贷评分表，属于信贷/风控职责 |
| 表 | aml_alert / transaction_monitor | AML/监控表，属于风控/合规职责 |
| DAG | risk_feature_daily / transaction_aml_check / credit_score_monthly | 风控/AML/信贷 DAG |
| Dashboard | risk-dashboard | 风控视角仪表盘 |

**权限数量**：25 个（20 表读写 + 3 DAG + 2 Dashboard）

## 第3章 权限模型

### 3.1 资源类型

本 RBAC 定义包含 3 类资源，共 29 个资源实例：

| 资源类型 | 数量 | 来源 | 说明 |
|---|---|---|---|
| table | 21 | T018-2 DDL | 5 业务域 21 张表 |
| dag | 5 | T018-3 DAG | 5 个 DolphinScheduler 调度作业 |
| dashboard | 3 | T018-4 Dashboard | 3 个 Superset 仪表盘 |

### 3.2 操作类型

| 操作 | SQL 语义 | 适用资源 | 说明 |
|---|---|---|---|
| read | SELECT | table | 只读 |
| write | SELECT, INSERT, UPDATE, DELETE | table | 读写 |
| manage | DDL / 上线/下线/触发执行/编辑 | dag | 管理 |
| view | 查看详情 / 渲染 | dag, dashboard | 查看 |

### 3.3 权限总数

| 权限类别 | 数量 | 计算公式 |
|---|---|---|
| 表权限 | 42 | 21 表 × 2 操作（read + write） |
| DAG 权限 | 10 | 5 DAG × 2 操作（manage + view） |
| Dashboard 权限 | 3 | 3 Dashboard × 1 操作（view） |
| **合计** | **55** | - |

### 3.4 数据分级与角色映射

| 数据分级 | 表示例 | 可访问角色 |
|---|---|---|
| restricted（最高敏感） | customer, customer_profile, customer_relation, aml_alert | 合规员只读 / 客户经理读写 |
| confidential（敏感） | risk_model, risk_rule, account, transaction, loan_application 等 | 合规员只读 / 风控员或客户经理读写 |
| internal（内部） | customer_tag, account_status_log | 合规员只读 / 客户经理读写 |

## 第4章 权限矩阵

### 4.1 表权限矩阵

| 角色 | risk_model | risk_rule | risk_feature | risk_evaluation | risk_alert | customer | customer_tag | customer_profile | customer_relation | account | account_balance | account_transaction | account_status_log | transaction | transaction_detail | aml_alert | transaction_monitor | loan_application | loan_contract | repayment_plan | credit_score |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| risk_officer | W | W | W | W | W | N | N | N | N | N | N | N | N | N | R | W | R | N | N | N | R |
| compliance_officer | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R |
| account_manager | N | N | N | N | N | W | W | W | W | W | W | W | W | W | W | N | N | N | N | N | N |

> 图例：W=读写 R=只读 N=无权限

### 4.2 DAG 权限矩阵

| 角色 | risk_feature_daily | customer_tag_update | account_eod_settlement | transaction_aml_check | credit_score_monthly |
|---|---|---|---|---|---|
| risk_officer | M | N | N | V | N |
| compliance_officer | V | V | V | V | V |
| account_manager | N | M | V | N | N |

> 图例：M=管理 V=查看 N=无权限

### 4.3 Dashboard 权限矩阵

| 角色 | risk-dashboard | customer-dashboard | transaction-dashboard |
|---|---|---|---|
| risk_officer | V | N | N |
| compliance_officer | V | V | V |
| account_manager | N | V | V |

> 图例：V=查看 N=无权限

## 第5章 Keycloak 集成

### 5.1 Realm 配置

本 RBAC 定义对应 Keycloak realm `finance-template-realm`，导入步骤：

1. 登录 Keycloak Admin Console（默认 http://keycloak.example.com:8080，admin/admin）
2. 创建 Realm：`Add Realm` → Name: `finance-template-realm` → Create
3. 导入角色：`Roles` → `Import` → 选择 `roles.yaml`
4. 导入权限：`Authorization` → `Resources` → 批量导入 `permissions.yaml` 中的 resources
5. 导入权限策略：`Authorization` → `Policies` → 批量导入 `permissions.yaml` 中的 permissions
6. 导入角色-权限映射：`Authorization` → `Permissions` → 批量导入 `role-permissions.yaml`

### 5.2 用户分配角色

| 用户示例 | 分配角色 | 业务场景 |
|---|---|---|
| risk_user_01 | risk_officer | 风控部门员工，负责风控模型配置与告警处理 |
| compliance_user_01 | compliance_officer | 合规部门员工，负责合规审计与监管报表 |
| account_user_01 | account_manager | 零售银行客户经理，负责客户关系维护 |

### 5.3 与 Doris 集成

Doris 通过 Ranger 插件实现细粒度表权限控制，本 RBAC 定义的 `sql_equivalent` 字段对应 Ranger 策略：

- `read` → Ranger policy: `SELECT`
- `write` → Ranger policy: `SELECT, INSERT, UPDATE, DELETE`
- 角色 → Ranger role: 一一映射

### 5.4 与 DolphinScheduler 集成

DolphinScheduler 通过项目级权限控制 DAG 访问，本 RBAC 定义的 DAG 权限对应：

- `manage` → DS 项目管理员（可上线/下线/触发/编辑工作流）
- `view` → DS 项目只读用户（可查看工作流详情与运行历史）

### 5.5 与 Superset 集成

Superset 通过角色-Dashboard 关联实现访问控制，本 RBAC 定义的 Dashboard 权限对应：

- `view` → Superset role: `Gamma` + Dashboard 关联
- 角色映射：risk_officer → Superset role `risk_dashboard_viewer`，etc.

## 第6章 验收对照

| 验收项 | 要求 | 实际 | 结果 |
|---|---|---|---|
| 角色数量 | 3 个 | risk_officer / compliance_officer / account_manager | ✅ |
| 风控员可访问风控模型表 | risk_model 等 5 张表读写 | 5 张表全部 W 权限 | ✅ |
| 风控员不可访问客户隐私表 | customer 等 4 张表无权限 | 4 张表全部 N 权限 | ✅ |
| 风控员可访问风控 DAG | risk_feature_daily | M 权限 | ✅ |
| 风控员可访问风控 Dashboard | risk-dashboard | V 权限 | ✅ |
| 合规员可访问所有表只读 | 21 张表 R 权限 | 21 张表全部 R 权限 | ✅ |
| 合规员可查看所有 DAG | 5 个 DAG V 权限 | 5 个 DAG 全部 V 权限 | ✅ |
| 合规员可查看所有 Dashboard | 3 个 Dashboard V 权限 | 3 个 Dashboard 全部 V 权限 | ✅ |
| 合规员不可修改数据 | 无 write/manage 权限 | 29 个权限全部 read/view | ✅ |
| 客户经理可访问客户/账户/交易表 | 10 张表 W 权限 | 10 张表全部 W 权限 | ✅ |
| 客户经理不可访问风控模型表 | risk_model 等 5 张表无权限 | 5 张表全部 N 权限 | ✅ |
| 客户经理可访问客户 DAG | customer_tag_update | M 权限 | ✅ |
| 客户经理可访问客户 Dashboard | customer-dashboard | V 权限 | ✅ |
| Keycloak 格式兼容 | realm role 格式 | roles/permissions/role-permissions 三件套 | ✅ |
| 最小权限原则 | 每角色最小权限集 | 显式拒绝 + 权限矩阵验证 | ✅ |

## 第7章 维护说明

- **新增角色**：在 `roles.yaml` 的 `roles` 数组追加角色定义，在 `role-permissions.yaml` 的 `role_permissions` 数组追加权限映射，同步更新权限矩阵。
- **新增表/DAG/Dashboard**：在 `permissions.yaml` 的 `resources` 与 `permissions` 数组追加资源与权限定义，在 `role-permissions.yaml` 中按需授予各角色。
- **权限调整**：修改 `role-permissions.yaml` 中对应角色的 `permissions` 与 `denied_permissions`，同步更新权限矩阵与第 4 章说明。
- **Keycloak 同步**：每次修改后需重新导入 Keycloak，建议通过 Keycloak REST API 自动化同步。
- **审计检查**：定期检查合规员角色审计日志，确认无越权访问行为。

## 第8章 安全注意事项

1. **密码策略**：建议为 3 个角色配置强密码策略（长度 ≥ 12，含大小写字母/数字/特殊字符，90 天强制更换）。
2. **多因素认证（MFA）**：合规员角色强制启用 MFA（TOTP 或硬件密钥），风控员与客户经理建议启用。
3. **会话管理**：合规员会话超时 30 分钟，风控员 60 分钟，客户经理 120 分钟（见 `attributes.session_timeout_seconds`）。
4. **IP 白名单**：建议为合规员角色配置 IP 白名单（仅允许合规部门办公网 IP 访问）。
5. **数据脱敏**：合规员虽可只读访问 customer 表，但敏感字段（姓名/证件号/手机号）应通过 T018-1 脱敏规则（`docs/desensitize-rules.yaml`）脱敏后展示。
6. **审计日志保留**：合规员审计日志保留 ≥ 180 天，满足金融监管要求。