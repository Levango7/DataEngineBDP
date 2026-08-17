# 金融风控场景端到端验证

> 场景文档：`design/场景模拟/金融风控场景.md`
> 行业模板：finance（金融行业模板 - 风控数据集市）

## 1. 场景概述

某城商行（资产 4500 亿元、个人客户 1200 万、日均交易 380 万笔）建设"事前+事中+事后"全链路实时风控体系，覆盖交易反欺诈、反洗钱（AML）、信贷风控、客户风险评级、风控看板 5 大场景。

## 2. 业务流程

```
核心业务系统 → SeaTunnel CDC → Kafka → Flink 实时入湖 → Iceberg ODS
→ Spark 治理 → DWD 客户/账户/交易明细
→ Spark 特征工程 → 风控特征层 → ML 模型训练
→ Flink 实时风控（Drools 规则引擎 + CEP + ML 在线推理）
→ 决策结果（PASS/REJECT/MANUAL/ALERT） → 业务系统实时拦截
→ AML 告警 → 反洗钱监测中心上报
→ Doris ADS 风控看板
```

## 3. 验证步骤

| # | 步骤 | 需集群 | 说明 |
|---|------|--------|------|
| 1 | 登录与租户上下文 | 否 | 切换到 bank-finance 租户 |
| 2 | 交易数据实时接入 | 否 | 4 类业务表 CDC 任务 |
| 3 | 风控规则引擎 - 规则配置 | 否 | 3 类规则（ALERT/REJECT/MANUAL） |
| 4 | 风控规则热更新 | 是 | Flink+Drools+MySQL-CDC |
| 5 | 实时风控决策 | 部分 | 决策逻辑本地验证；50ms 决策需集群 |
| 6 | 反欺诈检测 - ML 模型注册 | 否 | XGBoost+LR 模型+效果指标 |
| 7 | 实时画像计算 | 否 | 客户画像/特征/关系资产注册 |
| 8 | AML 场景识别 | 部分 | CEP 逻辑本地验证；实际需 Flink CEP |
| 9 | 信贷风控 | 部分 | 决策逻辑本地验证；实际需特征工程+推理服务 |
| 10 | 风控看板 | 否 | 看板数据集+决策 API 发布 |
| 11 | 数据分级与脱敏 | 否 | 身份证/账户号 SM4 加密策略 |
| 12 | ML 推理服务部署 | 否 | 反欺诈+信用评分在线推理 |

## 4. 运行方式

```bash
cd F:\nexus\DataEngineBDP
node tests/scenarios/finance/scenario-finance.test.js
```

## 5. 需完整集群环境的项目

- 风控规则热更新（Flink CDC + Drools KieSession 热重载）
- 实时风控 50ms 决策（Flink + Drools + Doris 维表广播）
- AML CEP 场景识别（Flink CEP 库）
- 信贷风控特征工程（Spark + 客户画像 + 关联人）
- 模型效果监控（PSI/KS/AUC 持续监控作业）