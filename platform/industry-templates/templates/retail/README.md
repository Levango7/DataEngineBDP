# 零售行业大数据平台模板（retail-template）

> 数据引擎大数据平台 V2.0 Phase 2 Batch 1b - T038 零售行业模板
> 覆盖商品画像 + 会员分析 + 营销效果三大业务域，接入标签引擎，支持 RFM 分群/流失预测/LTV/A/B 实验/转化漏斗/ROI 分析，打包为 Helm Chart 一键部署。

## 1. 模板概览

| 维度 | 数量 | 说明 |
|------|------|------|
| DDL 表 | 18 张 | 商品画像 6 + 会员分析 6 + 营销效果 6 |
| DAG 作业 | 6 个 | RFM 分群 / 流失预测 / LTV / A/B 实验 / 转化漏斗 / ROI |
| Dashboard | 3 个 | 商品画像 / 会员分析 / 营销效果 |
| RBAC 角色 | 3 个 | 店长 / 运营 / 数据分析师 |
| 标签引擎 | 1 套 | 会员标签 + 商品标签计算配置 |

## 2. 目录结构

```
retail/
├── ddl/                                # DDL 表定义
│   ├── product_profile_ddl.sql         # 商品画像表（6 张）
│   ├── member_analysis_ddl.sql         # 会员分析表（6 张）
│   ├── marketing_effect_ddl.sql        # 营销效果表（6 张）
│   └── rbac_ddl.sql                    # RBAC 表（角色/权限/角色-权限映射）
├── dag/                                # DolphinScheduler DAG（Python Airflow 风格）
│   ├── rfm_segmentation.py             # RFM 分群
│   ├── churn_prediction.py             # 流失预测（含 ML 模型）
│   ├── ltv_calculation.py              # LTV 计算
│   ├── ab_experiment.py                # A/B 实验显著性检验
│   ├── conversion_funnel.py            # 转化漏斗分析
│   └── roi_analysis.py                 # ROI 分析
├── dashboards/                         # Superset Dashboard
│   ├── product_profile_dashboard.json  # 商品画像仪表盘
│   ├── member_dashboard.json           # 会员分析仪表盘
│   └── marketing_dashboard.json        # 营销效果仪表盘
├── tag-engine/
│   └── tag-engine-config.yaml          # 会员/商品标签计算配置
└── README.md                           # 本文件
```

Helm Chart 位于 `platform/industry-templates/charts/retail-template/`。

## 3. 业务域

### 3.1 商品画像

- 商品基础属性、类目体系、品牌信息、销量统计、评价画像、商品标签
- DDL 表：`product`、`product_category`、`product_brand`、`product_sales_stat`、`product_review_profile`、`product_tag`
- DAG：复用标签引擎计算商品标签
- Dashboard：商品画像仪表盘（销量 TOP N、类目分布、品牌矩阵、评价词云、商品标签分布）

### 3.2 会员分析

- RFM 分群（最近购买 R / 频率 F / 金额 M）
- 流失预测（机器学习二分类模型，逻辑回归 + GBDT 集成）
- LTV（生命周期价值，BG/NBD + Gamma-Gamma 模型）
- DDL 表：`member`、`member_rfm`、`member_churn_prediction`、`member_ltv`、`member_tag`、`member_behavior_profile`
- DAG：`rfm_segmentation.py`、`churn_prediction.py`、`ltv_calculation.py`
- Dashboard：会员分析仪表盘（RFM 8 分群、流失风险分布、LTV 分层、会员等级金字塔）

### 3.3 营销效果

- A/B 实验（实验组 / 对照组显著性检验，Z 检验 + 卡方检验 + T 检验）
- 转化漏斗（曝光 → 点击 → 加购 → 下单 → 支付，5 步漏斗）
- ROI（投入产出比，营销活动 ROI / 渠道 ROI / 整体 ROI）
- DDL 表：`ab_experiment`、`ab_experiment_variant`、`conversion_funnel`、`marketing_campaign`、`marketing_roi`、`marketing_channel_stat`
- DAG：`ab_experiment.py`、`conversion_funnel.py`、`roi_analysis.py`
- Dashboard：营销效果仪表盘（A/B 实验显著性矩阵、转化漏斗图、ROI 排行、渠道效能对比）

## 4. 标签引擎接入

复用 Phase 1 `platform/tag-engine/`（T000a Mock 已清零），通过 `tag-engine-config.yaml` 配置：

- 会员标签：RFM 等级、流失风险等级、LTV 分层、活跃度、价格偏好、品类偏好
- 商品标签：销量等级、评价等级、爆款标识、长尾标识、新品标识、季节性

## 5. Helm 一键部署

```bash
# 部署零售模板
helm install retail-template platform/industry-templates/charts/retail-template/ -n retail

# 验证
kubectl get configmap -n retail | grep retail-template
kubectl get job -n retail | grep retail-template-import

# 卸载
helm uninstall retail-template -n retail
```

部署内容：
1. ConfigMap 挂载全部模板资产（DDL/DAG/Dashboard/RBAC/tag-engine-config）
2. Job 依次将资产导入 Doris / DolphinScheduler / Superset / Keycloak / tag-engine

## 6. 验收标准

- ✅ 商品画像 + RFM + 流失预测 + LTV + A/B 实验 + 转化漏斗 + ROI 全部可用
- ✅ 接入标签引擎，会员/商品标签计算正确
- ✅ `helm install retail-template` 一键部署成功
- ✅ pytest 集成测试 ≥ 18 用例全部通过

## 7. 作者

- 工程师：T038 零售模板工程师
- 邮箱：hw029373469@shuqing.com
- 组织：数据引擎大数据平台团队