# 大型 toB 企业场景端到端验证

> 场景文档：`design/场景模拟/大型toB企业场景.md`
> 行业模板：manufacturing（制造行业模板）

## 1. 场景概述

某大型离散制造业集团（汽车零部件+整机装配），3 万员工、5 个工厂、18 条产线、1200+ 设备、年营收 50 亿元。按"事业部 + 业务线"两级隔离，共享数据湖底座，支撑生产/供应链/质量/能源/财务 5 大事业部数仓。

## 2. 业务流程

```
MES/ERP/SCADA/WMS/TMS → SeaTunnel 多源接入 → Kafka/IoTDB
→ Flink 实时入湖 → Iceberg ODS 共享层
→ Spark 治理 → DWD 共享明细
→ 各事业部独立 Spark 作业 → Doris DWS/ADS
→ 数据资产目录 + API 服务目录 → BI 看板 + 数据 API + ML 预测
```

## 3. 验证步骤

| # | 步骤 | 需集群 | 说明 |
|---|------|--------|------|
| 1 | 登录与租户上下文 | 否 | 切换到 mfg-group 租户 |
| 2 | 3 万员工组织架构 - 项目空间 | 否 | 集团总部+5 工厂项目空间 |
| 3 | 多工厂数据集成 | 否 | MES/ERP 多源集成任务+连接器验证 |
| 4 | 多事业部租户隔离 | 否 | 事业部+业务线两级隔离 |
| 5 | OEE 计算 | 部分 | 公式本地验证；实际需 Spark+IoTDB |
| 6 | 供应链数据治理 | 否 | 库存周转/供应商评估资产注册 |
| 7 | 质量追溯 | 是 | 需 Spark+Iceberg 构建追溯链 |
| 8 | BI 报表 | 否 | 5 事业部门户看板数据集 |
| 9 | 数据 API 服务目录 | 否 | OEE/追溯/库存 API 发布 |
| 10 | ML 模型注册 | 否 | XGBoost 故障+LightGBM 能耗 |
| 11 | ML 推理服务部署 | 否 | 在线推理+扩缩容 |
| 12 | 数据产品交付 - 订阅审批 | 否 | 跨事业部订阅审批流 |

## 4. 运行方式

```bash
cd F:\nexus\DataEngineBDP
node tests/scenarios/enterprise/scenario-enterprise.test.js
```

## 5. 需完整集群环境的项目

- OEE 实际计算（需 Spark + IoTDB + Iceberg）
- 质量追溯链路构建（需 Spark + Iceberg）
- ML 模型训练（需 MLflow + Spark MLlib）
- 设备时序数据采集（需 IoTDB + OPC UA）