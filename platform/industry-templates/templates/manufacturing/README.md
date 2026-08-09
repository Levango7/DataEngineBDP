# 制造行业模板（Manufacturing Industry Template）

> 数据引擎大数据平台 L5.3 行业应用模板 - 制造行业完整模板（T037）
>
> Phase 2 Batch 1b | 版本 1.0.0 | 2026-08-08

## 概述

制造行业模板面向离散/流程制造企业，提供设备 OEE 分析、质量追溯、供应链协同三大业务域的完整数据模型、调度作业、可视化仪表盘与 RBAC 权限定义，接入 IoTDB 时序数据，打包为 Helm Chart 支持一键部署。

## 业务域

### 1. 设备 OEE 分析

OEE（Overall Equipment Effectiveness，设备综合效率）是制造业核心 KPI：

```
OEE = 可用率(Availability) × 性能率(Performance) × 质量率(Quality)
```

- **可用率** = 实际运行时间 / 计划生产时间
- **性能率** = 实际产量 / 理论产量
- **质量率** = 合格产量 / 实际产量

数据来源：IoTDB 时序数据（设备状态变更/传感器采集）经 Flink/Spark 聚合计算。

### 2. 质量追溯

支持正反向全链路追溯：

- **正向追溯**：批次 → 工序 → 参数 → 缺陷（从原料到成品）
- **反向追溯**：缺陷 → 参数 → 工序 → 批次 → 原料 → 供应商（从缺陷定位根因）

含工序能力指数 Cpk 计算：`Cpk = min(USL - mean, mean - LSL) / (3 × sigma)`

### 3. 供应链协同

订单/库存/物流全链路协同：

- 库存预警（低于安全库存/高于最大库存/触发再订货点）
- 交期预警（采购订单逾期/销售订单交期临近）
- 订单-库存协同（销售订单触发生产工单/采购需求）
- 物流状态同步（发货单物流轨迹更新）

## 交付物清单

### DDL（25 张表）

| 文件 | 业务域 | 表数 | 表清单 |
|------|--------|------|--------|
| `ddl/oee_ddl.sql` | OEE 分析 | 7 | equipment / production_line / shift / equipment_status_log / equipment_oee_daily / equipment_oee_shift / equipment_sensor_metric |
| `ddl/quality_trace_ddl.sql` | 质量追溯 | 7 | product_batch / work_order / process_route / process_record / quality_parameter / defect_record / quality_trace_link |
| `ddl/supply_chain_ddl.sql` | 供应链协同 | 7 | supplier / purchase_order / inventory / inventory_movement / sales_order / logistics_shipment / supply_chain_event |
| `ddl/rbac_ddl.sql` | RBAC | 4 | mfg_role / mfg_permission / mfg_role_permission / mfg_user_role |

### DAG（4 个调度作业）

| 文件 | 业务域 | 调度周期 | 引擎 | 说明 |
|------|--------|----------|------|------|
| `dag/oee_calculation.py` | OEE 分析 | 每日 02:00 | Spark | OEE 日/班次级计算 |
| `dag/quality_trace.py` | 质量追溯 | 每日 03:00 | Spark | 正反向追溯链路构建 + Cpk 计算 |
| `dag/supply_chain_sync.py` | 供应链协同 | 每日 04:00 | Spark | 库存/交期预警 + 订单协同 + 物流同步 |
| `dag/iotdb_ingestion.py` | IoTDB 接入 | 每 15 分钟 | Flink | 设备传感器时序数据实时接入 |

### Dashboard（3 个 Superset 仪表盘）

| 文件 | 业务域 | 图表数 | 说明 |
|------|--------|--------|------|
| `dashboards/oee_dashboard.json` | OEE 分析 | 6 | OEE 趋势/产线对比/设备排名/可用率性能率质量率/停机分析/OEE 达成率 |
| `dashboards/quality_dashboard.json` | 质量追溯 | 6 | 批次合格率/缺陷分布/缺陷类别/Cpk 分析/缺陷趋势/追溯链路统计 |
| `dashboards/supply_chain_dashboard.json` | 供应链协同 | 6 | 库存状态/库存预警/订单状态/供应商绩效/物流跟踪/供应链事件 |

### IoTDB 接入配置

| 文件 | 说明 |
|------|------|
| `iotdb/iotdb-jdbc-config.yaml` | IoTDB JDBC 连接配置（查询设备传感器时序数据） |
| `iotdb/flink-iotdb-connector.yaml` | Flink IoTDB Source Connector 配置（实时接入） |

### RBAC（4 个角色）

| 文件 | 角色数 | 角色清单 |
|------|--------|----------|
| `rbac/roles.yaml` | 4 | workshop_director / quality_engineer / supply_chain_manager / equipment_engineer |
| `rbac/permissions.yaml` | 32 资源 | 25 表 + 4 DAG + 3 Dashboard |
| `rbac/role-permissions.yaml` | 4 角色权限映射 | 最小权限原则 |

### Helm Chart

| 路径 | 说明 |
|------|------|
| `charts/manufacturing-template/Chart.yaml` | Helm Chart 定义 |
| `charts/manufacturing-template/values.yaml` | 可配置参数 |
| `charts/manufacturing-template/templates/` | K8s 部署模板（ConfigMap + Job） |

## RBAC 角色权限矩阵

| 角色 | OEE 域 | 质量追溯域 | 供应链域 | Dashboard |
|------|--------|-----------|---------|-----------|
| 车间主任 | 读写 | 工单/工序/批次读写，其余只读 | 拒绝 | OEE |
| 质量员 | 只读 | 读写 | 拒绝 | 质量 + OEE |
| 供应链经理 | 拒绝 | 批次只读 | 读写 | 供应链 |
| 设备工程师 | 读写 | 拒绝 | 拒绝 | OEE |

## 部署方式

### Helm 一键部署

```bash
# 部署制造行业模板
helm install manufacturing-template ./platform/industry-templates/charts/manufacturing-template \
  --namespace manufacturing \
  --create-namespace

# 查看部署状态
kubectl get all -n manufacturing

# 卸载
helm uninstall manufacturing-template -n manufacturing
```

### 前置依赖

制造模板部署需要以下平台组件已就绪：

- **Apache Doris**：DDL 执行与数据存储
- **Apache DolphinScheduler**：DAG 调度
- **Apache Superset**：Dashboard 可视化
- **Apache IoTDB**：设备传感器时序数据
- **Apache Flink**：流式数据接入
- **Apache Spark**：批计算
- **Keycloak**：RBAC 权限管理

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Apache Doris | 2.1.x | OLAP 存储（DDL 执行） |
| Apache Flink | 1.18.x | 流计算（IoTDB 实时接入） |
| Apache Spark | 3.5.x | 批计算（OEE/质量追溯/供应链协同） |
| Apache IoTDB | 1.3.x | 时序数据库（设备传感器数据） |
| Apache Superset | 4.x | BI 可视化（Dashboard） |
| Apache DolphinScheduler | 3.2.x | 调度编排（DAG） |
| Keycloak | 24.x | RBAC 权限管理 |
| Helm | 3.x | K8s 包管理 |

## 测试

集成测试位于 `tests/integration/docker/test_manufacturing_template.py`，覆盖：

- DDL 场景（25 张表 DDL 语法正确）
- DAG 场景（4 个 DAG 可解析）
- Dashboard 场景（3 个 Dashboard JSON 格式正确）
- IoTDB 场景（JDBC 配置 + Flink Connector 配置正确）
- Helm 部署场景（helm install 可部署）
- RBAC 场景（角色权限矩阵一致性）

```bash
pytest tests/integration/docker/test_manufacturing_template.py -v
```

## 文档结构

```
manufacturing/
├── ddl/                          # DDL（25 张表）
│   ├── oee_ddl.sql
│   ├── quality_trace_ddl.sql
│   ├── supply_chain_ddl.sql
│   └── rbac_ddl.sql
├── dag/                          # DAG（4 个调度作业）
│   ├── oee_calculation.py
│   ├── quality_trace.py
│   ├── supply_chain_sync.py
│   └── iotdb_ingestion.py
├── dashboards/                   # Dashboard（3 个仪表盘）
│   ├── oee_dashboard.json
│   ├── quality_dashboard.json
│   └── supply_chain_dashboard.json
├── iotdb/                        # IoTDB 接入配置
│   ├── iotdb-jdbc-config.yaml
│   └── flink-iotdb-connector.yaml
├── rbac/                         # RBAC（4 角色）
│   ├── roles.yaml
│   ├── permissions.yaml
│   └── role-permissions.yaml
└── README.md                     # 本文件
```

## 验收标准

- [x] OEE 分析数据模型完整（7 张表，OEE = 可用率 × 性能率 × 质量率）
- [x] 质量追溯数据模型完整（7 张表，正反向追溯）
- [x] 供应链协同数据模型完整（7 张表，订单/库存/物流协同）
- [x] RBAC 数据模型完整（4 张表，4 角色）
- [x] DDL ≥ 15 张表（实际 25 张）
- [x] DAG ≥ 4 个（实际 4 个）
- [x] Dashboard ≥ 3 个（实际 3 个）
- [x] IoTDB 时序数据接入（JDBC + Flink Connector）
- [x] Helm Chart 打包，helm install manufacturing-template 一键部署
- [x] 集成测试 ≥ 15 个测试用例