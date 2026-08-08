# 政务行业模板（Government Industry Template）

> 数擎大数据平台 L5.3 行业应用模板 - 政务行业完整模板（T044）
>
> Phase 2 Batch 1c | 版本 1.0.0 | 2026-08-08

## 概述

政务行业模板面向各级政府机关，提供人口分析、经济运行、民生服务三大业务域的完整数据模型、调度作业、可视化仪表盘与 RBAC 权限定义，**重点特色为政务合规预置**（数据分级/脱敏规则/审计策略/访问控制），打包为 Helm Chart 支持一键部署。

## 业务域

### 1. 人口分析

覆盖人口结构、人口流动、人口预测三大分析场景：

- **人口结构**：总人口/性别比/城镇化率/老龄化率/抚养比/户Cpk 户均人口
- **人口流动**：迁入/迁出/净流动量/流动原因/跨省流动/市内流动
- **人口预测**：基于历史数据，使用线性/ARIMA/队列要素法预测未来 5 年人口趋势
- **多维分布**：年龄分布（人口金字塔）/性别分布/学历分布/就业分布

数据来源：人口普查/户籍数据/抽样调查，经 Spark 聚合计算。

### 2. 经济运行

覆盖 GDP 核算、产业结构、投资消费、财政收支四大分析场景：

- **GDP 核算**：生产法/支出法/收入法三法核算，交叉校验
  - 生产法 = Σ各行业增加值
  - 支出法 = 最终消费 + 资本形成 + 净出口
  - 收入法 = 劳动报酬 + 生产税净额 + 固定资产折旧 + 营业盈余
- **产业结构**：三次产业占比/行业贡献度/产业结构升级趋势
- **投资消费**：固定资产投资/社会消费品零售/外贸进出口
- **财政收支**：财政收入（税收/非税/基金）/财政支出（功能分类）

### 3. 民生服务

覆盖政务服务统计、满意度分析两大场景：

- **政务服务统计**：办理量/办结率/平均时长/网办率/驳回率
- **满意度分析**：评价收集/满意度计算/高频标签/热点排行
- **热点事项**：按办理量/搜索量/投诉量加权计算热度排行

### 4. 政务合规（重点特色）

**政务合规是本模板的重点特色**，完整实现数据分级/脱敏/审计/访问控制：

- **数据分级**：L1 公开 / L2 内部 / L3 秘密 / L4 机密（对标 GB/T 31075-2017）
- **脱敏规则**：身份证号/姓名/手机号/地址/金额/收入/审计参数脱敏
- **审计策略**：登录/查询/导出/修改/删除/授权/访问全操作审计，不可篡改保留 ≥ 5 年
- **访问控制**：ABAC 模型，基于角色/数据分级/时间/IP 的访问控制策略

## 交付物清单

### DDL（28 张表）

| 文件 | 业务域 | 表数 | 表清单 |
|------|--------|------|--------|
| `ddl/01_population_analysis_ddl.sql` | 人口分析 | 8 | population_base / population_structure / population_flow / population_forecast / population_age_distribution / population_gender_distribution / population_education_distribution / population_employment_distribution |
| `ddl/02_economic_operation_ddl.sql` | 经济运行 | 8 | gdp / industry_structure / fixed_asset_investment / social_retail_consumption / foreign_trade / fiscal_revenue / fiscal_expenditure / economic_indicator |
| `ddl/03_livelihood_service_ddl.sql` | 民生服务 | 8 | government_service / service_transaction / service_satisfaction / service_hot_topic / service_statistics / service_evaluation / service_category / service_channel |
| `ddl/04_government_compliance_ddl.sql` | 政务合规 | 8 | data_classification / desensitize_rule / audit_log / access_control_policy / access_control_record / compliance_risk_alert / compliance_check_record / compliance_policy |
| `ddl/rbac_ddl.sql` | RBAC | 4 | gov_role / gov_permission / gov_role_permission / gov_user_role |

### DAG（8 个调度作业）

| 文件 | 业务域 | 调度周期 | 引擎 | 说明 |
|------|--------|----------|------|------|
| `dag/population_structure_analysis.py` | 人口分析 | 每日 02:00 | Spark | 人口结构/年龄/性别/学历/就业多维分析 |
| `dag/population_flow_tracking.py` | 人口分析 | 每日 03:00 | Spark | 迁入/迁出/净流动量追踪 |
| `dag/population_forecast.py` | 人口分析 | 每月 1 日 04:00 | Spark+ML | 线性/ARIMA/队列要素法预测 |
| `dag/gdp_calculation.py` | 经济运行 | 每季度 15 日 05:00 | Spark | 生产法/支出法/收入法三法核算 |
| `dag/industry_analysis.py` | 经济运行 | 每季度 16 日 05:00 | Spark | 三次产业占比/行业贡献度 |
| `dag/investment_consumption_analysis.py` | 经济运行 | 每月 1 日 06:00 | Spark | 固定资产投资/社会消费品零售 |
| `dag/government_service_statistics.py` | 民生服务 | 每日 07:00 | Spark | 办理量/办结率/平均时长/网办率 |
| `dag/satisfaction_analysis.py` | 民生服务 | 每日 08:00 | Spark | 评价收集/满意度计算/热点识别 |

### Dashboard（4 个 Superset 仪表盘）

| 文件 | 业务域 | 图表数 | 说明 |
|------|--------|--------|------|
| `dashboards/population_dashboard.json` | 人口分析 | 6 | 人口结构金字塔/流动地图/预测趋势/城镇化老龄化/学历分布/就业分布 |
| `dashboards/economic_dashboard.json` | 经济运行 | 6 | GDP 趋势/三次产业结构/行业贡献度/固定资产投资/社会消费品零售/财政收支 |
| `dashboards/livelihood_dashboard.json` | 民生服务 | 6 | 办理量趋势/办结率网办率/满意度分布/热点排行/平均时长/部门对比 |
| `dashboards/compliance_dashboard.json` | 政务合规 | 8 | 审计日志/操作类型分布/风险预警/风险级别/访问控制/脱敏规则/检查趋势/数据分级 |

### RBAC（5 个角色）

| 文件 | 角色数 | 角色清单 |
|------|--------|----------|
| `rbac/roles.yaml` | 5 | gov_admin / data_analyst / dept_user / auditor / public_user |
| `rbac/permissions.yaml` | 40 资源 | 28 表 + 8 DAG + 4 Dashboard |
| `rbac/role-permissions.yaml` | 5 角色权限映射 | 最小权限原则 + 数据分级访问控制 |

### 政务合规配置（4 个配置文件）

| 文件 | 说明 |
|------|------|
| `compliance/data-classification.yaml` | 数据分级配置（L1 公开/L2 内部/L3 秘密/L4 机密，对标 GB/T 31075-2017） |
| `compliance/desensitize-rules.yaml` | 脱敏规则（身份证号/姓名/手机号/地址/金额/收入/审计参数，9 条规则） |
| `compliance/audit-policy.yaml` | 审计策略（9 类审计事件，不可篡改保留 5 年，4 类异常检测） |
| `compliance/access-control.yaml` | 访问控制策略（ABAC 模型，6 条 DENY + 7 条 ALLOW） |

### Helm Chart

| 路径 | 说明 |
|------|------|
| `charts/government-template/Chart.yaml` | Helm Chart 定义 |
| `charts/government-template/values.yaml` | 可配置参数 |
| `charts/government-template/templates/` | K8s 部署模板（ConfigMap + Job） |

## RBAC 角色权限矩阵

| 角色 | 人口域 | 经济域 | 民生域 | 合规域 | Dashboard |
|------|--------|--------|--------|--------|-----------|
| 政务管理员 | 读写 L1/L2/L3 | 读写 L1/L2/L3 | 读写 L1/L2/L3 | 读写 L2 策略，禁 L4 | 人口+经济+民生 |
| 数据分析师 | 只读 L1/L2 | 只读 L1/L2 | 只读 L1/L2 | 拒绝 | 人口+经济+民生 |
| 部门用户 | 拒绝 | 拒绝 | 读写本部门 L1/L2 | 拒绝 | 民生 |
| 审计员 | 只读 | 只读 | 只读 | 读写 L3/L4 合规 | 合规 |
| 公众用户 | 拒绝 | 拒绝 | 只读 L1 公开 | 拒绝 | 无 |

## 政务合规设计

### 数据分级（对标 GB/T 31075-2017）

| 分级 | 名称 | 说明 | 加密 | 审计 | 保留期 |
|------|------|------|------|------|--------|
| L1 | 公开 | 可向社会公开发布 | 否 | 否 | 无限制 |
| L2 | 内部 | 政务内部业务数据 | 是 | 是 | 10 年 |
| L3 | 秘密 | 含个人敏感信息/敏感运营 | 是 | 是 | 10 年 |
| L4 | 机密 | 审计日志/权限定义，不可篡改 | 是 | 是 | 5 年 |

### 脱敏规则（9 条）

| 规则 | 算法 | 适用字段 | 说明 |
|------|------|----------|------|
| id_card_mask | mask | 身份证号 | 保留前6后4 |
| name_mask | keep_first_char | 姓名 | 保留姓 |
| phone_number_mask | mask | 手机号 | 保留前3后4 |
| address_mask | keep_prefix_2 | 地址 | 保留省市两级 |
| amount_round | round_to_n | 金额 | 精度降至千元 |
| income_round | round_to_n | 收入 | 精度降至万元 |
| audit_params_hash | hash | 审计参数 | SHA-256 哈希 |
| permission_sql_tokenize | tokenize | 权限 SQL | 假名化 |
| access_control_condition_hash | hash | 策略条件 | SHA-256 哈希 |

### 审计策略（9 类事件）

- 身份认证：LOGIN / LOGOUT
- 数据访问：QUERY / EXPORT
- 数据修改：MODIFY / DELETE
- 权限变更：GRANT / REVOKE
- 访问控制：ACCESS（仅 DENY 记录）

审计日志不可篡改、不可删除，保留期 5 年，AES-256 加密存储。

### 访问控制（ABAC 模型）

- DENY 策略（6 条）：公众/分析师/部门用户/管理员分级限制 + 非工作时间 + 非内网 IP
- ALLOW 策略（7 条）：各角色按数据分级允许的操作
- 决策规则：DENY 优先于 ALLOW，无匹配默认 DENY

## 部署方式

### Helm 一键部署

```bash
# 部署政务行业模板
helm install government-template ./platform/industry-templates/charts/government-template \
  --namespace government \
  --create-namespace

# 查看部署状态
kubectl get all -n government

# 卸载
helm uninstall government-template -n government
```

### 前置依赖

政务模板部署需要以下平台组件已就绪：

- **Apache Doris**：DDL 执行与数据存储
- **Apache DolphinScheduler**：DAG 调度
- **Apache Superset**：Dashboard 可视化
- **Apache Spark**：批计算
- **Keycloak**：RBAC 权限管理
- **L2.7 统一 SQL 网关**：脱敏/访问控制策略下推

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Apache Doris | 2.1.x | OLAP 存储（DDL 执行） |
| Apache Spark | 3.5.x | 批计算（人口/经济/民生分析） |
| Apache Superset | 4.x | BI 可视化（Dashboard） |
| Apache DolphinScheduler | 3.2.x | 调度编排（DAG） |
| Keycloak | 24.x | RBAC 权限管理 |
| Helm | 3.x | K8s 包管理 |

## 测试

集成测试位于 `tests/integration/docker/test_government_template.py`，覆盖 19 个测试用例：

- 模板文件结构验证
- DDL 语法验证（28 张表）
- 人口/经济/民生/合规表结构验证
- DAG 脚本可导入验证（8 个 DAG）
- 人口结构/GDP/政务服务/满意度 DAG 验证
- Dashboard JSON 格式验证（4 个 Dashboard）
- 人口/经济/民生看板验证
- Helm Chart 结构验证
- RBAC 配置验证（5 角色）
- 数据分级配置验证
- 脱敏规则验证

```bash
pytest tests/integration/docker/test_government_template.py -v
```

## 文档结构

```
government/
├── ddl/                          # DDL（28 张表）
│   ├── 01_population_analysis_ddl.sql
│   ├── 02_economic_operation_ddl.sql
│   ├── 03_livelihood_service_ddl.sql
│   ├── 04_government_compliance_ddl.sql
│   └── rbac_ddl.sql
├── dag/                          # DAG（8 个调度作业）
│   ├── population_structure_analysis.py
│   ├── population_flow_tracking.py
│   ├── population_forecast.py
│   ├── gdp_calculation.py
│   ├── industry_analysis.py
│   ├── investment_consumption_analysis.py
│   ├── government_service_statistics.py
│   └── satisfaction_analysis.py
├── dashboards/                   # Dashboard（4 个仪表盘）
│   ├── population_dashboard.json
│   ├── economic_dashboard.json
│   ├── livelihood_dashboard.json
│   └── compliance_dashboard.json
├── rbac/                         # RBAC（5 角色）
│   ├── roles.yaml
│   ├── permissions.yaml
│   └── role-permissions.yaml
├── compliance/                   # 政务合规配置（重点特色）
│   ├── data-classification.yaml
│   ├── desensitize-rules.yaml
│   ├── audit-policy.yaml
│   └── access-control.yaml
└── README.md                     # 本文件
```

## 验收标准

- [x] 人口分析数据模型完整（8 张表，人口结构/流动/预测/多维分布）
- [x] 经济运行数据模型完整（8 张表，GDP 三法核算/产业/投资/消费/财政）
- [x] 民生服务数据模型完整（8 张表，政务服务/满意度/热点/统计）
- [x] 政务合规数据模型完整（8 张表，数据分级/脱敏/审计/访问控制）
- [x] RBAC 数据模型完整（4 张表，5 角色）
- [x] DDL ≥ 15 张表（实际 28 张）
- [x] DAG ≥ 4 个（实际 8 个）
- [x] Dashboard ≥ 3 个（实际 4 个）
- [x] 政务合规预置完整（数据分级/脱敏/审计/访问控制 4 个配置文件）
- [x] Helm Chart 打包，helm install government-template 一键部署
- [x] 集成测试 ≥ 15 个测试用例（实际 19 个）