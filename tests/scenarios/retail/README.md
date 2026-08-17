# 零售营销场景端到端验证

> 场景文档：`design/场景模拟/零售营销场景.md`
> 行业模板：retail（零售行业模板）

## 1. 场景概述

某全国连锁零售集团（500 门店、2800 万会员、12 万 SKU、年 GMV 180 亿元），建设"会员画像 → 营销推荐 → 效果分析"闭环，实现"千人千面"精准营销。

## 2. 业务流程

```
POS/CRM/行为 → SeaTunnel CDC → Kafka → Flink 实时入湖 → Iceberg ODS
→ Spark 治理 → DWD 订单/会员/商品明细
→ Spark 画像 → Doris DWS 会员画像（RFM/流失/LTV/标签）+ 商品画像
→ 推荐引擎（多路召回+精排+重排） → APP/小程序
→ 营销活动引擎 + A/B 实验平台
→ Spark 效果分析 → ROI/漏斗/归因 → BI 看板
```

## 3. 验证步骤

| # | 步骤 | 需集群 | 说明 |
|---|------|--------|------|
| 1 | 登录与租户上下文 | 否 | 切换到 retail-group 租户 |
| 2 | 500 门店 POS/CRM 实时接入 | 否 | 5 类集成任务 |
| 3 | 500 门店 RFM 画像 - 资产注册 | 否 | 5 类会员画像资产 |
| 4 | RFM 分群计算 | 部分 | 公式本地验证；实际需 Spark+Doris |
| 5 | 会员标签体系 | 否 | 5 类 14 标签 |
| 6 | 营销活动效果分析 | 否 | ROI/漏斗/A/B 实验资产 |
| 7 | 商品关联分析 | 否 | 商品画像+关联规则资产 |
| 8 | A/B 实验配置 | 否 | 显著性检验本地验证 |
| 9 | 推荐引擎 | 部分 | 5 路召回本地验证；实际需 LightGBM+实时特征 |
| 10 | 实时库存监控 | 是 | 需 Flink+Kafka+Doris |
| 11 | 流失预测+LTV 预测 ML 模型 | 否 | 3 个模型注册 |
| 12 | 推荐 API+营销 API 发布 | 否 | 6 个 API 发布 |
| 13 | ML 推理服务部署 | 否 | 流失预测+推荐精排 |

## 4. 运行方式

```bash
cd F:\nexus\DataEngineBDP
node tests/scenarios/retail/scenario-retail.test.js
```

## 5. 需完整集群环境的项目

- RFM 分群实际计算（需 Spark + Doris）
- 推荐引擎实际推理（需 LightGBM + 实时特征 + Doris 画像）
- 实时库存监控（需 Flink + Kafka + Doris）
- LTV 预测（需 BG/NBD + Gamma-Gamma 模型训练）
- 营销活动实时触达（需 Flink + 推送通道）