# 能源行业模板（Energy Industry Template）

> 数擎大数据平台 L5.3 行业应用模板 - 能源行业完整模板（T043）
>
> Phase 2 Batch 1c | 版本 1.0.0 | 2026-08-08

## 概述

能源行业模板面向能源密集型制造企业与公共事业机构，提供设备监测、用能分析、碳排放核算、趋势预测四大业务域的完整数据模型、调度作业、可视化仪表盘与 RBAC 权限定义，接入 IoTDB 时序数据，打包为 Helm Chart 支持一键部署。

## 业务域

### 1. 设备监测

覆盖电力/水/天然气/蒸汽/压缩空气/冷量等能源计量设备的实时监测、告警、健康度评分与维护管理全生命周期。

**健康度评分公式**：

```
health_score = w1 × availability_score + w2 × performance_score + w3 × alarm_score
默认权重 w1=0.4, w2=0.4, w3=0.2，取值范围 [0, 100]
```

- **可用率评分** = 在线时长 / 总时长 × 100
- **性能评分** = 100 - 偏离额定参数的扣分
- **告警评分** = 100 - 告警扣分（CRITICAL=20, WARNING=5, INFO=1）

**告警分级**：INFO / WARNING / CRITICAL / EMERGENCY，按级别路由到不同通知渠道（邮件/短信/电话/钉钉/飞书/看板），EMERGENCY 自动派单。

数据来源：IoTDB 时序数据（设备状态/传感器采集）经 Flink 流作业实时更新。

### 2. 用能分析

多维度能耗聚合与对比分析：

- **多维度汇总**：设备/位置/部门/公司/工序 × 时/日/周/月/季/年
- **同比环比**：同比增长率 = (本期 - 同期) / 同期；环比增长率 = (本期 - 上期) / 上期
- **多维对比**：跨部门/跨位置/跨介质/跨周期/对标
- **趋势数据**：含 7 日/30 日移动平均与趋势方向（UP/DOWN/FLAT）
- **能源平衡**：输入 = 输出 + 损失，计算能效
- **成本分析**：单价 × 消耗量，单位产品能耗成本
- **定额管理**：基准值/上下限/考核周期，超限告警

### 3. 碳排放核算

依据 ISO 14064 / GHG Protocol 进行温室气体排放核算：

**排放量计算公式**：

```
E = AD × EF × GWP
E   : 排放量（tCO2e）
AD  : 活动数据（Activity Data，如燃料消耗量、用电量）
EF  : 排放因子（Emission Factor，tCO2/单位活动数据）
GWP : 全球变暖潜势（Global Warming Potential，CO2=1, CH4=28, N2O=265）
```

- **排放范围**：Scope1（直接排放）/ Scope2（外购电力间接排放）/ Scope3（其他间接排放）
- **排放因子库**：IPCC/国家发改委默认因子 + 企业自定义因子
- **核算模型**：合并方法（运营控制/股权比例/财务控制）+ 组织边界 + 基准年
- **减排目标**：基准年/目标年/减排比例/减排路径（能效提升/可再生能源/燃料替代/工艺优化/CCUS/抵消）
- **核算报告**：含碳强度计算与审批流程

### 4. 趋势预测

基于历史能耗时序数据，使用多种模型预测未来能耗：

- **模型类型**：ARIMA / Prophet / LSTM / 指数平滑 / 线性回归 / 集成
- **预测目标**：能耗 / 碳排放 / 成本 / 峰值负荷
- **置信区间**：95% 置信度，支持多种区间估计方法（解析/自助法/贝叶斯/分位数）
- **模型评估**：MAPE / RMSE / MAE / R² / 偏差 / 跟踪信号

**评估指标**：

```
MAPE = mean(|actual - forecast| / |actual|) × 100%
RMSE = sqrt(mean((actual - forecast)^2))
MAE  = mean(|actual - forecast|)
```

## 交付物清单

### DDL（31 张表）

| 文件 | 业务域 | 表数 | 表清单 |
|------|--------|------|--------|
| `ddl/01_device_monitoring_ddl.sql` | 设备监测 | 8 | energy_device / device_realtime_status / device_alarm_record / device_health_score / device_metric_history / device_alarm_rule / device_maintenance_log / device_status_change |
| `ddl/02_energy_consumption_ddl.sql` | 用能分析 | 7 | energy_consumption_detail / energy_consumption_summary / energy_dimension_compare / energy_trend_data / energy_quota / energy_balance / energy_cost_analysis |
| `ddl/03_carbon_emission_ddl.sql` | 碳排放核算 | 7 | emission_factor_library / emission_source / emission_calculation_result / emission_calculation_model / emission_scope_classification / emission_reduction_target / emission_report |
| `ddl/04_energy_forecast_ddl.sql` | 趋势预测 | 5 | forecast_parameter / forecast_result / forecast_model_evaluation / forecast_model_registry / forecast_confidence_interval |
| `ddl/rbac_ddl.sql` | RBAC | 4 | energy_role / energy_permission / energy_role_permission / energy_user_role |

### DAG（5 个调度作业）

| 文件 | 业务域 | 调度周期 | 引擎 | 说明 |
|------|--------|----------|------|------|
| `dag/device_status_monitoring.py` | 设备监测 | 每 5 分钟 | Flink | 设备状态实时监控+健康度计算+告警触发 |
| `dag/energy_consumption_statistics.py` | 用能分析 | 每日 01:00 | Spark | 多维度能耗聚合+同比环比+对比分析 |
| `dag/carbon_emission_calculation.py` | 碳排放核算 | 每月 1 日 03:00 | Spark | 排放因子匹配+排放量计算+报告生成 |
| `dag/energy_trend_forecast.py` | 趋势预测 | 每日 04:00 | Spark+MLlib | 模型训练+预测+评估+最优选择 |
| `dag/device_alert_routing.py` | 设备监测 | 每 1 分钟 | Python | 告警分级+通知分发+自动派单+升级 |

### Dashboard（4 个 Superset 仪表盘）

| 文件 | 业务域 | 图表数 | 说明 |
|------|--------|--------|------|
| `dashboards/device_monitoring_dashboard.json` | 设备监测 | 6 | 设备地图/实时状态/告警列表/健康度分布/健康度趋势/告警分级统计 |
| `dashboards/energy_consumption_dashboard.json` | 用能分析 | 7 | 能耗趋势/介质对比/部门对比/TopN/同比环比/成本构成/单位产品成本 |
| `dashboards/carbon_emission_dashboard.json` | 碳排放核算 | 6 | 排放总量趋势/排放结构/类别明细/因子库/减排目标进度/基准年对比 |
| `dashboards/energy_forecast_dashboard.json` | 趋势预测 | 6 | 预测曲线/置信区间/预测误差/模型评估对比/模型注册表/超界预测数 |

### IoTDB 接入配置

| 文件 | 说明 |
|------|------|
| `iotdb/iotdb-jdbc-config.yaml` | IoTDB JDBC 连接配置（查询设备传感器时序数据） |
| `iotdb/flink-iotdb-connector.yaml` | Flink IoTDB Source Connector 配置（实时接入） |
| `iotdb/device-data-model.yaml` | 设备数据时序模型定义（7 种设备类型测点） |

### RBAC（4 个角色）

| 文件 | 角色数 | 角色清单 |
|------|--------|----------|
| `rbac/roles.yaml` | 4 | energy_admin / energy_analyst / device_operator / carbon_accountant |
| `rbac/permissions.yaml` | 40 资源 | 31 表 + 5 DAG + 4 Dashboard |
| `rbac/role-permissions.yaml` | 4 角色权限映射 | 最小权限原则 |

### Helm Chart

| 路径 | 说明 |
|------|------|
| `charts/energy-template/Chart.yaml` | Helm Chart 定义 |
| `charts/energy-template/values.yaml` | 可配置参数 |
| `charts/energy-template/templates/` | K8s 部署模板（ConfigMap + Job） |

## RBAC 角色权限矩阵

| 角色 | 设备监测域 | 用能分析域 | 碳排放核算域 | 趋势预测域 | Dashboard |
|------|-----------|-----------|-------------|-----------|-----------|
| 能源管理员 | 读写 | 读写 | 读写 | 读写 | 全部 |
| 能源分析师 | 只读 | 读写 | 只读结果 | 读写 | 全部 |
| 设备运维员 | 读写告警/维护 | 只读明细 | 拒绝 | 拒绝 | 设备监测 |
| 碳排放核算员 | 拒绝 | 只读汇总 | 读写 | 拒绝 | 碳排放+用能 |

## 数据模型

### 设备监测域（8 张表）

```
energy_device（设备主表）
  ├── device_realtime_status（实时状态，Flink 更新）
  ├── device_alarm_record（告警记录）
  ├── device_health_score（健康度评分，日级）
  ├── device_metric_history（指标历史，IoTDB 同步）
  ├── device_alarm_rule（告警规则）
  ├── device_maintenance_log（维护记录）
  └── device_status_change（状态变更日志）
```

### 用能分析域（7 张表）

```
energy_consumption_detail（明细，Flink 差分计算）
  └── energy_consumption_summary（多维度汇总，含同比环比）
      ├── energy_dimension_compare（多维对比）
      ├── energy_trend_data（趋势，含移动平均）
      ├── energy_quota（定额管理）
      ├── energy_balance（能源平衡）
      └── energy_cost_analysis（成本分析）
```

### 碳排放核算域（7 张表）

```
emission_factor_library（排放因子库）
  └── emission_source（排放源，关联因子与设备）
      └── emission_calculation_result（核算结果，E=AD×EF×GWP）
          ├── emission_calculation_model（核算模型）
          ├── emission_scope_classification（Scope 分类汇总）
          ├── emission_reduction_target（减排目标）
          └── emission_report（核算报告）
```

### 趋势预测域（5 张表）

```
forecast_parameter（预测参数）
  └── forecast_result（预测结果，含置信区间）
      ├── forecast_model_evaluation（模型评估，MAPE/RMSE/MAE/R²）
      ├── forecast_model_registry（模型注册，MLflow 集成）
      └── forecast_confidence_interval（多置信水平区间）
```

## 使用方法

### 1. Helm 一键部署

```bash
# 部署能源行业模板
helm install energy-template ./charts/energy-template \
  --namespace energy \
  --create-namespace \
  --set target.doris.feHost=doris-fe.doris.svc.cluster.local \
  --set target.iotdb.host=iotdb.iotdb.svc.cluster.local
```

### 2. 单独导入资产

```bash
# 导入 DDL 到 Doris
mysql -h doris-fe -P 9030 -u root -p db_energy < ddl/01_device_monitoring_ddl.sql
mysql -h doris-fe -P 9030 -u root -p db_energy < ddl/02_energy_consumption_ddl.sql
mysql -h doris-fe -P 9030 -u root -p db_energy < ddl/03_carbon_emission_ddl.sql
mysql -h doris-fe -P 9030 -u root -p db_energy < ddl/04_energy_forecast_ddl.sql
mysql -h doris-fe -P 9030 -u root -p db_energy < ddl/rbac_ddl.sql

# 导入 DAG 到 DolphinScheduler
for dag in dag/*.py; do
  curl -X POST "http://ds-api:12345/dolphinscheduler/projects/import" -F "file=@${dag}"
done

# 导入 Dashboard 到 Superset
for dash in dashboards/*.json; do
  curl -X POST "http://superset:8088/api/v1/dashboard/import/" -F "formData=@${dash}"
done
```

### 3. 启动 IoTDB 数据接入

```bash
# 提交 Flink IoTDB 接入作业
flink run -m flink-jobmanager:8081 \
  /opt/flink/jobs/energy_iotdb_ingestion.py \
  --config iotdb/flink-iotdb-connector.yaml
```

## 验证

```bash
# Python DAG 语法检查
python -m py_compile dag/*.py

# SQL DDL 基本格式验证
# Dashboard JSON 格式验证
python -c "import json; json.load(open('dashboards/device_monitoring_dashboard.json'))"

# Helm Chart 验证（如 helm 可用）
helm lint charts/energy-template/

# pytest 集成测试
cd tests/integration && python -m pytest docker/test_energy_template.py -v
```

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Apache Doris | 2.0.x | OLAP 存储（DDL 31 张表） |
| Apache IoTDB | 1.3.x | 时序引擎（设备传感器数据） |
| Apache Flink | 1.18.x | 流计算（实时状态更新/告警触发） |
| Apache Spark | 3.5.x | 批计算（能耗聚合/碳排放核算/预测训练） |
| DolphinScheduler | 3.2.x | 调度编排（5 个 DAG） |
| Superset | 4.0.x | BI 可视化（4 个 Dashboard） |
| Keycloak | 24.x | RBAC（4 个角色） |
| MLflow | 2.9.x | 模型注册（预测模型版本管理） |

## 作者

T043 能源行业模板工程师 | hw029373469@shuqing.com