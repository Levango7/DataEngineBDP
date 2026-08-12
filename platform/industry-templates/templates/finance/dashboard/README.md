# 金融行业模板 - Dashboard 仪表盘定义

> 模块：T018-4 Dashboard 仪表盘设计
> 数据源：T018-2 DDL 表（Apache Doris / db_finance）
> 业务场景：T018-3 DAG（risk_feature_daily / customer_tag_update / transaction_aml_check 等）
> 格式：Superset 4.x 导入 JSON

## 1. Dashboard 概述

本目录定义 3 个业务视角的 Superset Dashboard，覆盖金融行业风控、客户、交易三大核心场景，每个 Dashboard 含 6 个图表（≥5 满足任务要求），含全局筛选器（时间范围 / 业务条线 / 机构），采用 2 行 3 列网格布局。

| Dashboard | 文件 | slug | 图表数 | 数据集 | DDL 来源 |
| --- | --- | --- | --- | --- | --- |
| 风控视角仪表盘 | `risk_dashboard.json` | `risk-dashboard` | 6 | risk_model / risk_rule / risk_feature / risk_evaluation / risk_alert | `ddl/01_risk_control_ddl.sql` |
| 客户视角仪表盘 | `customer_dashboard.json` | `customer-dashboard` | 6 | customer / customer_tag / customer_profile / customer_relation | `ddl/02_customer_ddl.sql` |
| 交易视角仪表盘 | `transaction_dashboard.json` | `transaction-dashboard` | 6 | transaction / transaction_detail / aml_alert / transaction_monitor | `ddl/04_transaction_ddl.sql` |

## 2. 图表清单

### 2.1 风控视角仪表盘（risk_dashboard.json）

| 序号 | 图表名 | viz_type | 类型说明 | 数据集 | 业务含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | 风控评分分布 | bar | 柱状图 | risk_evaluation | 按评分区间(0-300/300-500/.../800-1000)统计客户数 |
| 2 | 风险告警趋势 | line | 线图 | risk_alert | 按日统计告警数量趋势 |
| 3 | 告警类型占比 | pie | 饼图 | risk_alert | 按 alert_type(FRAUD/AML/CREDIT/BEHAVIOR)分类占比 |
| 4 | 风控规则命中Top10 | table | 表格 | risk_rule | 按 hit_count 降序 Top10 |
| 5 | 风控覆盖率 | big_number | 指标卡 | risk_evaluation | 已评估客户/活跃客户比率(%) |
| 6 | 告警级别分布 | bar | 柱状图 | risk_alert | 按 alert_level(LOW/MEDIUM/HIGH/CRITICAL)统计 |

### 2.2 客户视角仪表盘（customer_dashboard.json）

| 序号 | 图表名 | viz_type | 类型说明 | 数据集 | 业务含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | 客户总数增长 | line | 线图 | customer | 按月统计累计客户数 |
| 2 | 客户标签分布 | pie | 饼图 | customer_tag | 按 tag_category(BUSINESS/RISK/BEHAVIOR/MARKETING)分类 |
| 3 | 客户等级分布 | bar | 柱状图 | customer | 按 risk_level(A/B/C/D/E)统计客户数 |
| 4 | 客户关系网络Top10 | table | 表格 | customer_relation | 按担保金额降序 Top10 |
| 5 | 活跃客户数 | big_number | 指标卡 | customer_profile | 近30天 lifecycle_stage=ACTIVE 客户数 |
| 6 | 客户生命周期分布 | pie | 饼图 | customer_profile | 按 lifecycle_stage(NEW/ACTIVE/DORMANT/LOST)统计 |

### 2.3 交易视角仪表盘（transaction_dashboard.json）

| 序号 | 图表名 | viz_type | 类型说明 | 数据集 | 业务含义 |
| --- | --- | --- | --- | --- | --- |
| 1 | 交易金额趋势 | line | 线图 | transaction | 按日统计交易总额 |
| 2 | 交易类型分布 | pie | 饼图 | transaction | 按 txn_type(TRANSFER/PAYMENT/DEPOSIT/WITHDRAW/REFUND)分类 |
| 3 | 大额交易Top10 | table | 表格 | transaction | 按交易金额降序 Top10 |
| 4 | AML告警统计 | bar | 柱状图 | aml_alert | 按 risk_level(LOW/MEDIUM/HIGH)统计告警数 |
| 5 | 日交易总额 | big_number | 指标卡 | transaction | 当日成功交易总额 |
| 6 | 交易渠道分布 | pie | 饼图 | transaction | 按 channel(COUNTER/ONLINE/MOBILE/ATM/API)统计 |

## 3. 数据集与 DDL 表映射关系

| Dashboard | 数据集(Superset) | DDL 表 | DDL 文件 | 主时间字段 |
| --- | --- | --- | --- | --- |
| 风控 | risk_model | risk_model | 01_risk_control_ddl.sql §1 | created_at |
| 风控 | risk_rule | risk_rule | 01_risk_control_ddl.sql §2 | updated_at |
| 风控 | risk_feature | risk_feature | 01_risk_control_ddl.sql §3 | updated_at |
| 风控 | risk_evaluation | risk_evaluation | 01_risk_control_ddl.sql §4 | eval_at |
| 风控 | risk_alert | risk_alert | 01_risk_control_ddl.sql §5 | alert_at |
| 客户 | customer | customer | 02_customer_ddl.sql §1 | created_at |
| 客户 | customer_tag | customer_tag | 02_customer_ddl.sql §2 | tagged_at |
| 客户 | customer_profile | customer_profile | 02_customer_ddl.sql §3 | computed_at |
| 客户 | customer_relation | customer_relation | 02_customer_ddl.sql §4 | created_at |
| 交易 | transaction | transaction | 04_transaction_ddl.sql §1 | occurred_at |
| 交易 | transaction_detail | transaction_detail | 04_transaction_ddl.sql §2 | created_at |
| 交易 | aml_alert | aml_alert | 04_transaction_ddl.sql §3 | detected_at |
| 交易 | transaction_monitor | transaction_monitor | 04_transaction_ddl.sql §4 | checked_at |

数据库连接：`doris://doris_user:doris_pwd@doris-fe:9030/db_finance`（schema = db_finance）

## 4. 全局筛选器说明

每个 Dashboard 均配置 3 个 Native Filter（Superset 4.x 原生筛选器），作用于所有图表：

| 筛选器 ID | 名称 | filterType | 作用字段 | 默认值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| NATIVE_FILTER_TIME_RANGE | 时间范围 | filter_time | 主时间字段(eval_at/created_at/occurred_at) | Last 30 days | 时间范围筛选，所有图表的 adhoc_filters 中通过 `{{ from_dttm }}` / `{{ to_dttm }}` 模板变量接收 |
| NATIVE_FILTER_BIZ_LINE | 业务条线 | filter_select | biz_type / customer_type / txn_type | 无 | 多选下拉，按业务条线过滤 |
| NATIVE_FILTER_ORG | 机构 | filter_select | dept_code / industry_code / channel | 无 | 多选下拉，按机构/渠道过滤 |

筛选器在 SQL 中的体现：每个图表的 `adhoc_filters` 中包含 `WHERE <时间字段> >= '{{ from_dttm }}' AND <时间字段> < '{{ to_dttm }}'`，Superset 导入后会自动将 Native Filter 的时间范围注入到 `from_dttm` / `to_dttm` 模板变量。

## 5. 导入 Superset 步骤

### 5.1 前置条件

- Superset 4.x 已部署（参考 `design/deploy/values/superset-values.yaml`）
- Doris 集群已就绪，`db_finance` 数据库及 13 张 DDL 表已创建（参考 `ddl/*.sql`）
- DDL 表中已有数据（参考 `dag/*.json` 调度任务已运行）

### 5.2 导入方式一：UI 导入（推荐）

1. 登录 Superset Web UI（默认 http://superset.example.com，admin/admin）
2. 顶部菜单 → Settings → Import Dashboards
3. 选择本目录下的 JSON 文件（如 `risk_dashboard.json`）
4. 点击 Import，等待导入完成
5. 导入后进入 Dashboards 列表，可见 "风控视角仪表盘" / "客户视角仪表盘" / "交易视角仪表盘"
6. 点击 Dashboard 进入预览，验证 6 个图表渲染正常

### 5.3 导入方式二：CLI 导入

```bash
# 假设 superset 容器名为 superset-app
docker exec -it superset-app superset import-dashboards \
  --path /opt/superset/industry-templates/templates/finance/dashboard/risk_dashboard.json

docker exec -it superset-app superset import-dashboards \
  --path /opt/superset/industry-templates/templates/finance/dashboard/customer_dashboard.json

docker exec -it superset-app superset import-dashboards \
  --path /opt/superset/industry-templates/templates/finance/dashboard/transaction_dashboard.json
```

### 5.4 导入方式三：REST API 导入

```bash
# 获取 access token
TOKEN=$(curl -s -X POST http://superset.example.com/api/v1/security/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin","provider":"db"}' | jq -r .access_token)

# 导入 dashboard
curl -X POST http://superset.example.com/api/v1/dashboard/import/ \
  -H "Authorization: Bearer $TOKEN" \
  -F "formData=@risk_dashboard.json"
```

### 5.5 验证渲染

1. 打开 Dashboard，确认 6 个图表全部加载（无 "Error: undefined datasource" 等报错）
2. 切换全局筛选器 "时间范围" 为 Last 7 days，所有图表应同步刷新
3. 在 "业务条线" 筛选器中选择特定值，验证图表数据过滤生效
4. 检查指标卡（big_number）显示数值，柱/线/饼图渲染完整，表格分页正常

## 6. 布局说明

每个 Dashboard 采用 **2 行 3 列** 网格布局（GRID → ROW × 2 → CHART × 6），每个图表占 4/12 宽度（width=4），高度 50px。布局结构：

```
┌─────────────┬─────────────┬─────────────┐
│  CHART_1    │  CHART_2    │  CHART_3    │  Row 1
├─────────────┼─────────────┼─────────────┤
│  CHART_4    │  CHART_5    │  CHART_6    │  Row 2
└─────────────┴─────────────┴─────────────┘
```

## 7. 与上下游模块的关系

- **上游 T018-2 DDL**：本目录所有数据集均引用 T018-2 定义的 DDL 表（13 张表，覆盖风控/客户/交易/账户/信贷 5 个域）
- **上游 T018-3 DAG**：DAG 任务（risk_feature_daily / customer_tag_update / transaction_aml_check 等）产出的数据进入上述 DDL 表，本 Dashboard 直接查询这些表展示
- **下游 L4.4 BI 可视化**：本 Dashboard 是 L4.4 Superset+ECharts 详细设计的具体落地实例，对应 `design/详细设计/多平台多租户大数据平台_BI可视化详细设计_v0.1.md`

## 8. 维护说明

- 修改图表 SQL：编辑对应 JSON 文件的 `charts[].query` 字段
- 修改图表样式：编辑 `charts[].params` 字段
- 新增图表：在 `charts` 数组追加图表定义，并在 `dashboards[0].position` 中追加 CHART 节点、`metadata.chart_configuration` 追加配置、`charts` 数组追加图表名
- 修改筛选器：编辑 `dashboards[0].metadata.native_filter_configuration`
- 修改布局：编辑 `dashboards[0].position` 中的 GRID/ROW/CHART 节点