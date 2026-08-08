# 数擎大数据平台行业模板使用指南

> 版本：V2.0 | 适用对象：行业交付工程师、解决方案架构师 | 更新日期：2026-08-08

## 第1章 概述

### 1.1 模板定位

数擎大数据平台 V2.0 预置三个行业模板，面向外部客户"开箱即用"，无需从零搭建数据模型、作业流与仪表盘。每个模板包含完整的 DDL 表结构、DAG 调度作业、Superset 看板、RBAC 角色权限，打包为 Helm Chart 支持一键部署。

### 1.2 模板清单

| 模板 ID | 行业 | 模板名 | 核心能力 | 表数 | DAG 数 | 看板数 | 角色数 |
|---------|------|--------|----------|------|--------|--------|--------|
| finance-template | 金融 | 金融行业大数据平台模板 | 风控评分卡、监管报表、反洗钱 | 21 | 5 | 3 | 3 |
| energy-template | 能源 | 能源行业模板 | 设备监测、用能分析、碳排放核算 | 31 | 5 | 4 | 4 |
| government-template | 政务 | 政务行业模板 | 人口分析、经济运行、民生服务 | 28 | 8 | 4 | 5 |

### 1.3 适用场景

- **金融模板**：银行/证券/保险/金融科技，信贷风控、反洗钱、监管报送
- **能源模板**：能源密集型制造企业、公共事业机构，设备监测、能耗分析、碳核算
- **政务模板**：各级政府机关，人口分析、经济运行、民生服务、政务合规

## 第2章 金融行业模板

### 2.1 模板内容

金融模板覆盖风控、客户、账户、交易、信贷 5 大业务域，遵循最小权限原则，可一键部署到 Doris + DolphinScheduler + Superset + Keycloak 技术栈。

| 业务域 | 核心能力 |
|--------|----------|
| 风控域 | 风控模型管理、规则集、特征工程、评估决策、告警 |
| 客户域 | 客户基本信息、标签、画像、关系 |
| 账户域 | 账户生命周期、余额快照、交易流水、状态日志 |
| 交易域 | 交易记录、明细、反洗钱告警、监控 |
| 信贷域 | 贷款申请、合同、还款计划、信用评分 |

### 2.2 快速部署

```bash
# 1) 准备 values
cat > finance-values.yaml <<EOF
tenant: tenant-finance
releaseName: finance-prod
doris:
  fe: {replicas: 3}
  be: {replicas: 5}
superset:
  replicas: 2
EOF

# 2) 部署金融模板
helm install finance-template ./charts/finance-template \
  -n ns-finance --create-namespace \
  -f finance-values.yaml

# 3) 等待部署完成
kubectl wait --for=condition=Ready pod -n ns-finance --timeout=600s

# 4) 验证
kubectl get all -n ns-finance
```

### 2.3 数据表说明（DDL）

金融模板共 21 张表，按业务域分文件管理：

| 文件 | 业务域 | 表数 | 表清单 |
|------|--------|------|--------|
| 01_risk_control_ddl.sql | 风控域 | 5 | risk_model / risk_rule / risk_feature / risk_evaluation / risk_alert |
| 02_customer_ddl.sql | 客户域 | 4 | customer / customer_tag / customer_profile / customer_relation |
| 03_account_ddl.sql | 账户域 | 4 | account / account_balance / account_transaction / account_status_log |
| 04_transaction_ddl.sql | 交易域 | 4 | transaction / transaction_detail / aml_alert / transaction_monitor |
| 05_credit_ddl.sql | 信贷域 | 4 | loan_application / loan_contract / repayment_plan / credit_score |

DDL 面向 Apache Doris 语法（`DUPLICATE KEY` + `DISTRIBUTED BY HASH` + `PROPERTIES` 动态分区），注释中给出 Apache Iceberg 兼容写法。每张表标注数据分级（L1~L4），含统一审计字段（created_at/updated_at/created_by/updated_by）。

示例表结构（风控模型表）：

```sql
CREATE TABLE risk_model (
  model_id BIGINT NOT NULL COMMENT '模型ID',
  model_name VARCHAR(128) NOT NULL COMMENT '模型名称',
  model_type VARCHAR(32) NOT NULL COMMENT '类型: SCORECARD/ML/RULE_SET',
  version VARCHAR(16) NOT NULL COMMENT '版本',
  status VARCHAR(16) NOT NULL COMMENT '状态: DRAFT/ACTIVE/DEPRECATED',
  risk_level_thresholds JSON COMMENT '风险等级阈值',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  created_by VARCHAR(64), updated_by VARCHAR(64)
) DUPLICATE KEY(model_id)
DISTRIBUTED BY HASH(model_id) BUCKETS 8
PROPERTIES("dynamic_partition.enable"="true", "dynamic_partition.time_unit"="DAY")
COMMENT '风控模型主表 数据分级=L2';
```

### 2.4 DAG 作业说明

5 个调度作业，DolphinScheduler JSON 格式：

| 作业文件 | 业务域 | 调度周期 | 说明 |
|----------|--------|----------|------|
| risk_feature_daily.json | 风控 | 每日 02:00 | 风控特征日度计算 |
| customer_tag_update.json | 客户 | 每日 03:00 | 客户标签更新 |
| account_eod_settlement.json | 账户 | 每日 23:00 | 日终结算 |
| transaction_aml_check.json | 交易 | 实时 | 反洗钱规则检查 |
| credit_score_monthly.json | 信贷 | 每月 1 日 | 信用评分月度更新 |

### 2.5 看板说明

3 个 Superset Dashboard：

| 看板 | 业务视角 | 核心图表 |
|------|----------|----------|
| 风控看板 | 风险分布 | 风险等级分布、告警趋势、模型命中、规则触发 |
| 客户看板 | 客户画像 | 客户分群、标签云、画像雷达、生命周期 |
| 交易看板 | 交易分析 | 交易趋势、金额分布、渠道占比、反洗钱告警 |

### 2.6 自定义扩展

1. **新增表**：在 `ddl/` 目录新增 SQL 文件，遵循命名规范与数据分级标注
2. **新增 DAG**：在 `dag/` 目录新增 DolphinScheduler JSON，配置上下游依赖
3. **新增看板**：在 `dashboard/` 目录新增 Superset JSON，绑定数据集
4. **调整 RBAC**：修改 `rbac/roles.yaml` 与 `rbac/role-permissions.yaml`，遵循最小权限原则

## 第3章 能源行业模板

### 3.1 模板内容

能源模板面向能源密集型制造企业与公共事业机构，提供设备监测、用能分析、碳排放核算、趋势预测四大业务域，接入 IoTDB 时序数据。

| 业务域 | 核心能力 |
|--------|----------|
| 设备监测 | 实时监测、告警、健康度评分、维护管理 |
| 用能分析 | 多维度能耗聚合、同比环比、对比分析、定额管理 |
| 碳排放核算 | ISO 14064 / GHG Protocol 排放核算、Scope1/2/3、减排目标 |
| 趋势预测 | ARIMA/Prophet/LSTM 多模型预测、置信区间、模型评估 |

### 3.2 快速部署

```bash
# 部署能源模板
helm install energy-template ./charts/energy-template \
  -n ns-energy --create-namespace \
  -f energy-values.yaml

# 验证
kubectl get all -n ns-energy
```

### 3.3 31 张数据表说明

| 文件 | 业务域 | 表数 | 表清单 |
|------|--------|------|--------|
| 01_device_monitoring_ddl.sql | 设备监测 | 8 | energy_device / device_realtime_status / device_alarm_record / device_health_score / device_metric_history / device_alarm_rule / device_maintenance_log / device_status_change |
| 02_energy_consumption_ddl.sql | 用能分析 | 7 | energy_consumption_detail / energy_consumption_summary / energy_dimension_compare / energy_trend_data / energy_quota / energy_balance / energy_cost_analysis |
| 03_carbon_emission_ddl.sql | 碳排放核算 | 7 | emission_factor_library / emission_source / emission_calculation_result / emission_calculation_model / emission_scope_classification / emission_reduction_target / emission_report |
| 04_energy_forecast_ddl.sql | 趋势预测 | 5 | forecast_parameter / forecast_result / forecast_model_evaluation / forecast_model_registry / forecast_confidence_interval |
| rbac_ddl.sql | RBAC | 4 | energy_role / energy_permission / energy_role_permission / energy_user_role |

### 3.4 IoTDB 时序数据接入

设备监测数据来自 IoTDB 时序数据库，经 Flink 流作业实时更新到 Doris：

```yaml
# IoTDB Source 配置
source:
  IoTDB:
    host: iotdb-server
    port: 6667
    username: root
    password: ${IOTDB_PASSWORD}
    storageGroup: root.energy.device
    timeseries: [status, temperature, pressure, flow_rate]

# Flink 作业实时同步 IoTDB → Doris
sink:
  Doris:
    host: doris-fe:9030
    database: energy_db
    table: device_realtime_status
    write.mode: stream_load
```

### 3.5 DAG 作业说明（5 个 Python DAG）

| 作业文件 | 业务域 | 调度周期 | 引擎 | 说明 |
|----------|--------|----------|------|------|
| device_status_monitoring.py | 设备监测 | 每 5 分钟 | Flink | 设备状态实时监控+健康度计算+告警触发 |
| energy_consumption_statistics.py | 用能分析 | 每日 01:00 | Spark | 多维度能耗聚合+同比环比+对比分析 |
| carbon_emission_calculation.py | 碳排放核算 | 每月 1 日 03:00 | Spark | 排放因子匹配+排放量计算+报告生成 |
| energy_trend_forecast.py | 趋势预测 | 每日 04:00 | Spark+MLlib | 模型训练+预测+评估+最优选择 |
| device_maintenance_schedule.py | 设备监测 | 每周 06:00 | Python | 维护计划生成+工单派发 |

### 3.6 4 个看板说明

| 看板 | 业务域 | 图表数 | 说明 |
|------|--------|--------|------|
| device_dashboard.json | 设备监测 | 8 | 设备状态地图/健康度分布/告警趋势/维护工单 |
| energy_dashboard.json | 用能分析 | 8 | 能耗趋势/多维对比/定额达成/能源平衡/成本分析 |
| carbon_dashboard.json | 碳排放核算 | 8 | 排放总量/Scope 分布/减排进度/碳强度/对标 |
| forecast_dashboard.json | 趋势预测 | 6 | 预测曲线/置信区间/模型对比/评估指标 |

### 3.7 RBAC 角色权限

| 角色 | 权限范围 |
|------|----------|
| energy_admin | 全部资源管理 |
| energy_engineer | 设备监测+用能分析读写 |
| carbon_analyst | 碳排放核算读写+报告导出 |
| energy_viewer | 全部看板只读 |

## 第4章 政务行业模板

### 4.1 模板内容

政务模板面向各级政府机关，提供人口分析、经济运行、民生服务三大业务域，**重点特色为政务合规预置**（数据分级/脱敏规则/审计策略/访问控制）。

| 业务域 | 核心能力 |
|--------|----------|
| 人口分析 | 人口结构、人口流动、人口预测、多维分布 |
| 经济运行 | GDP 核算（三法）、产业结构、投资消费、财政收支 |
| 民生服务 | 政务服务统计、满意度分析、热点事项 |
| 政务合规 | 数据分级、脱敏、审计、ABAC 访问控制 |

### 4.2 快速部署

```bash
# 部署政务模板
helm install government-template ./charts/government-template \
  -n ns-government --create-namespace \
  -f government-values.yaml

# 验证
kubectl get all -n ns-government
```

### 4.3 28 张数据表说明

| 文件 | 业务域 | 表数 | 表清单 |
|------|--------|------|--------|
| 01_population_analysis_ddl.sql | 人口分析 | 8 | population_base / population_structure / population_flow / population_forecast / population_age_distribution / population_gender_distribution / population_education_distribution / population_employment_distribution |
| 02_economic_operation_ddl.sql | 经济运行 | 8 | gdp / industry_structure / fixed_asset_investment / social_retail_consumption / foreign_trade / fiscal_revenue / fiscal_expenditure / economic_indicator |
| 03_livelihood_service_ddl.sql | 民生服务 | 8 | government_service / service_transaction / service_satisfaction / service_hot_topic / service_statistics / service_evaluation / service_category / service_channel |
| 04_government_compliance_ddl.sql | 政务合规 | 8 | data_classification / desensitize_rule / audit_log / access_control_policy / access_control_record / compliance_risk_alert / compliance_check_record / compliance_policy |
| rbac_ddl.sql | RBAC | 4 | gov_role / gov_permission / gov_role_permission / gov_user_role |

### 4.4 8 个 DAG 作业说明

| 作业文件 | 业务域 | 调度周期 | 引擎 | 说明 |
|----------|--------|----------|------|------|
| population_structure_analysis.py | 人口分析 | 每日 02:00 | Spark | 人口结构/年龄/性别/学历/就业多维分析 |
| population_flow_tracking.py | 人口分析 | 每日 03:00 | Spark | 迁入/迁出/净流动量追踪 |
| population_forecast.py | 人口分析 | 每月 1 日 04:00 | Spark+ML | 线性/ARIMA/队列要素法预测 |
| gdp_calculation.py | 经济运行 | 每季度 15 日 05:00 | Spark | 生产法/支出法/收入法三法核算 |
| industry_analysis.py | 经济运行 | 每季度 16 日 05:00 | Spark | 三次产业占比/行业贡献度 |
| investment_consumption_analysis.py | 经济运行 | 每月 1 日 06:00 | Spark | 固定资产投资/社会消费品零售 |
| government_service_statistics.py | 民生服务 | 每日 07:00 | Spark | 办理量/办结率/平均时长/网办率 |
| satisfaction_analysis.py | 民生服务 | 每日 08:00 | Spark | 评价收集/满意度计算/热点识别 |

### 4.5 4 个看板说明

| 看板 | 业务域 | 图表数 | 说明 |
|------|--------|--------|------|
| population_dashboard.json | 人口分析 | 6 | 人口结构金字塔/流动地图/预测趋势/城镇化老龄化/学历分布/就业分布 |
| economic_dashboard.json | 经济运行 | 6 | GDP 趋势/三次产业结构/行业贡献度/固定资产投资/社会消费品零售/财政收支 |
| livelihood_dashboard.json | 民生服务 | 6 | 办理量趋势/办结率网办率/满意度分布/热点排行/平均时长/部门对比 |
| compliance_dashboard.json | 政务合规 | 8 | 审计日志/操作类型分布/风险预警/风险级别/访问控制/脱敏规则/检查趋势/数据分级 |

### 4.6 政务合规预置

政务合规是本模板的重点特色，完整实现数据分级/脱敏/审计/访问控制：

#### 4.6.1 数据分级

对标 GB/T 31075-2017，4 级分类：

| 级别 | 名称 | 说明 |
|------|------|------|
| L1 | 公开 | 可公开发布的数据 |
| L2 | 内部 | 政府内部共享数据 |
| L3 | 秘密 | 涉及敏感信息，需授权访问 |
| L4 | 机密 | 涉及国家秘密/个人核心信息 |

#### 4.6.2 脱敏规则

| 脱敏对象 | 规则 | 示例 |
|----------|------|------|
| 身份证号 | 保留前 4 后 4 | `110101********1234` |
| 姓名 | 保留姓 | `张**` |
| 手机号 | 保留前 3 后 4 | `138****5678` |
| 地址 | 保留省市 | `北京市朝阳区***` |
| 金额 | 千位分隔+脱敏 | `***,***.**` |
| 收入 | 区间映射 | `10-20万` |
| 审计参数 | 哈希 | `a3f5...` |

#### 4.6.3 审计策略

- 全操作审计：登录/查询/导出/修改/删除/授权/访问
- 不可篡改：审计日志写入 WORM 存储，保留 ≥ 5 年
- 实时监控：异常行为实时告警（如批量导出、非工作时间访问）

#### 4.6.4 访问控制

ABAC（基于属性的访问控制）模型，基于角色/数据分级/时间/IP 的访问控制策略：

```yaml
# access-control-policy.yaml
- name: "工作时间访问L3数据"
  effect: ALLOW
  conditions:
    role: [data_analyst, dept_user]
    dataLevel: [L1, L2, L3]
    time: "09:00-18:00"
    ip: "10.0.0.0/8"
```

### 4.7 RBAC 角色权限

| 角色 | 权限范围 | 数据分级 |
|------|----------|----------|
| gov_admin | 全部资源管理 | L1~L4 |
| data_analyst | 数据分析读写+看板 | L1~L3 |
| dept_user | 本部门数据只读+看板 | L1~L2 |
| auditor | 审计日志只读 | L1~L4（仅审计表） |
| public_user | 公开看板只读 | L1 |

权限资源覆盖 28 表 + 8 DAG + 4 Dashboard = 40 资源，遵循最小权限原则。

## 第5章 模板定制化

### 5.1 二次开发流程

基于现有行业模板进行二次开发的推荐流程：

1. **Fork 模板仓库**：`git clone https://git.shuqing.com/templates/finance-template.git`
2. **修改模板元数据**：更新 `template-metadata.yaml` 中的版本号与描述
3. **DDL 调整**：在 `ddl/` 目录增删表，保持数据分级标注规范
4. **DAG 调整**：在 `dag/` 目录增删作业，配置上下游依赖
5. **看板调整**：在 `dashboard/` 目录增删看板，绑定数据集
6. **RBAC 调整**：修改 `rbac/` 目录角色权限，遵循最小权限原则
7. **测试验证**：在测试环境部署自定义模板，验证全部功能
8. **打包发布**：`helm package ./charts/finance-template --version 1.1.0`

### 5.2 自定义示例

以金融模板新增"信用卡风控"为例：

```bash
# 1) 新增 DDL
cat > ddl/06_credit_card_risk_ddl.sql <<EOF
CREATE TABLE credit_card_risk_score (
  card_id VARCHAR(32) NOT NULL,
  customer_id VARCHAR(32) NOT NULL,
  risk_score DECIMAL(5,2),
  risk_level VARCHAR(16),
  scored_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL
) DUPLICATE KEY(card_id) DISTRIBUTED BY HASH(card_id) BUCKETS 4
COMMENT '信用卡风控评分 数据分级=L2';
EOF

# 2) 新增 DAG
cat > dag/credit_card_risk_daily.json <<EOF
{"name":"credit_card_risk_daily","schedule":"0 5 * * * ?","tasks":[...]}
EOF

# 3) 打包
helm package ./charts/finance-template --version 1.1.0
```

### 5.3 模板继承

支持基于现有模板派生新模板，仅覆盖差异部分：

```yaml
# derived-template.yaml
base: finance-template@1.0.0
overrides:
  ddl:
    add: [06_credit_card_risk_ddl.sql]
  dag:
    add: [credit_card_risk_daily.json]
  rbac:
    roles:
      add: [{name: credit_risk_officer, permissions: [credit_card_risk_score:read]}]
```

## 第6章 模板版本管理

### 6.1 版本号规范

模板版本遵循语义化版本（SemVer）：`MAJOR.MINOR.PATCH`

- **MAJOR**：不兼容变更（如 DDL 表结构破坏性变更）
- **MINOR**：兼容新增（如新增表、DAG、看板）
- **PATCH**：缺陷修复

### 6.2 模板兼容性

每个模板在 `template-metadata.yaml` 中声明平台版本兼容性：

```yaml
version_info:
  template_version: 1.0.0
  schema_version: 1.0.0
  minimum_platform_version: 5.3.0
  recommended_platform_version: 5.3.0
```

部署前平台会检查兼容性，不兼容则拒绝部署。

### 6.3 模板升级

```bash
# 查看当前部署版本
helm list -n ns-finance

# 升级模板
helm upgrade finance-template ./charts/finance-template \
  -n ns-finance -f finance-values.yaml --version 1.1.0

# 回滚
helm rollback finance-template 0 -n ns-finance
```

### 6.4 模板仓库管理

```bash
# 启动本地 Chart 仓库
helm repo add local http://localhost:8080

# 上传模板
helm push finance-template-1.0.0.tgz local

# 搜索可用模板
helm search repo finance
```

## 第7章 附录

### 7.1 模板依赖

| 模板 | 依赖组件 | 最低版本 |
|------|----------|----------|
| 金融模板 | Apache Doris / DolphinScheduler / Superset / Keycloak | 2.1.x / 3.2.x / 4.0.x / 24.0.x |
| 能源模板 | Apache Doris / DolphinScheduler / Superset / IoTDB / Flink | 2.1.x / 3.2.x / 4.0.x / 2.0.x / 1.18.x |
| 政务模板 | Apache Doris / DolphinScheduler / Superset / Keycloak | 2.1.x / 3.2.x / 4.0.x / 24.0.x |

### 7.2 模板 API

模板管理通过行业模板服务 API（前缀 `/api/v1/templates`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/templates | 列出所有模板 |
| GET | /api/v1/templates/{id} | 模板详情 |
| POST | /api/v1/templates/{id}/deploy | 部署模板 |
| GET | /api/v1/templates/{id}/preview | 预览模板架构 |
| GET | /api/v1/templates/categories | 模板分类 |
| GET | /api/v1/templates/{id}/deployments | 列出部署记录 |

详见《API 参考文档》第8章。

### 7.3 相关文档

- 《用户手册》（user-manual.md）
- 《API 参考文档》（api-reference.md）
- 《运维手册》（ops-manual.md）
- 《升级指南》（upgrade-guide.md）
- 金融模板详细文档：`platform/industry-templates/templates/finance/README.md`
- 能源模板详细文档：`platform/industry-templates/templates/energy/README.md`
- 政务模板详细文档：`platform/industry-templates/templates/government/README.md`