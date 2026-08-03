# 多平台多租户大数据平台 · 端到端验证 PoC（湖仓集联动）详细设计（v0.1）

> 版本：v0.1 ｜ 日期：2026-08-03 ｜ 状态：验证方案稿（P1-E4 / P2-E5 已修复）
> 依赖文档：产品原型 v0.4（§5.5 选型决策表 / §7.2 治理闭环）、封装层详细设计 v0.1、统一存储详细设计 v0.1、统一 SQL 网关详细设计 v0.1、部署清单详细设计 v0.1、治理中台详细设计 v0.1、BI 可视化详细设计 v0.1
> 目标：用一条 `MySQL → Iceberg(湖) → Spark(仓) → Doris(集) → 治理闭环 → 统一 SQL 网关 → BI 看板` 的真实数据流，验证「一套主代码、四环境一致、客户无感知 K8s」从部署到查询到看板全链路跑通。
> 评审修复：P1-E4 补齐治理闭环（L3.1 元数据/L3.3 质量/L3.4 血缘）+ BI 可视化（L4.4 Superset）；P2-E5 明确 X4 APISIX 与 L2.7 统一 SQL 网关职责边界；P2-E1 术语已对齐 §5.5 拍板结果（湖仓集一体/自研轻量 Catalog/自研规则引擎/Kafka）。

---

## 1. PoC 目标与范围

### 1.1 验证目标

| 编号 | 验证项 | 对应设计 | 通过标准 |
| --- | --- | --- | --- |
| V1 | 封装层可建工作空间与数据项目 | L0.11 封装层 | 调用封装层 API 后，K8s 内生成对应 Namespace + ResourceQuota + NetworkPolicy（deny-all），客户不接触 kubeconfig |
| V2 | 实时入湖（CDC） | L2.1 统一存储 / L2 引擎 | MySQL 变更经 Flink CDC 写入 Iceberg 湖层表，秒级可见 |
| V3 | 湖→仓主题建模 | L2.1 / L2.7 | Spark 作业由 Iceberg 原始表产出主题层（dwd/dws）表，数据共享不冗余拷贝 |
| V4 | 湖仓集联动 | L2（湖仓集一体）/ Doris | Doris 经 External Catalog 直读 Iceberg 建物化视图，承载在线查询 |
| V5 | 统一 SQL 联邦查询 | L2.7 统一 SQL 网关 | 单条 SQL 跨 Iceberg + Doris 关联，经网关返回合并结果 |
| V6 | 客户无感知 | L0.11 / 全架构 | 全程无 Pod / Deployment / Operator 概念暴露；底层由 Operator 托管 |
| V7 | 四环境一致 | L0 / 环境适配框架 | 同一套作业与查询在信创 / 本地 / 公有云 / 私有云 Profile 下结果一致，仅存储驱动不同 |
| V4.5 | 治理闭环 | L3.1/L3.3/L3.4/L3.5 | 元数据自动注册 + 质量校验阻断脏数据 + 字段级血缘下钻 + 资产入目录（P1-E4 新增） |
| V5.5 | BI 可视化 | L4.4 / L2.7 / X1 | Superset 经 L2.7 网关建看板，权限脱敏生效，ECharts 渲染正常（P1-E4 新增） |

### 1.2 不在范围

- 多租户并发压测、SLA 验证（属 GA 阶段）
- 国密加密落盘验证（属安全专项 PoC，见安全脱敏 v0.1）
- 控制台 UI 点击演示（见控制台原型 v0.3，本文档用 API 视角描述，与 UI 严格对应）

---

## 2. 前置条件

1. 已完成 `deploy/` 骨架部署：封装层 Operator 先于平台组件就绪（部署清单 v0.1 §6）。
2. `preflight.sh` 通过，能力矩阵显示 `k8s.self_built=true`、存储驱动已就绪、无云托管 K8s（部署清单 v0.1 §9/§10）。
3. 选定 Profile（本文以 `xinchuang` 为例，其余环境仅 `storage.driver` 与镜像 `extraVariant` 不同，作业与 SQL 不变）。
4. 已推送多 arch 镜像（build-images.yaml 产物），包含 `flink-cdc`、`spark`、`doris-fe/be`、`sql-gateway` 等。

---

## 3. 数据流与架构

```text
                客户视角（控制台 / 封装层 API）
   ┌──────────────────────────────────────────────────────────────┐
   │  工作空间 demo-fin  →  数据项目 trade                          │
   └──────────────────────────────────────────────────────────────┘
                              │ 翻译为
                              ▼
   ┌────────────────────────  K8s（客户不可见）  ──────────────────┐
   │  Namespace: ws-demo-fin  (Quota + deny-all NetworkPolicy)      │
   │                                                                │
   │  MySQL(外部)                                                   │
   │     │ Flink CDC (FlinkDeployment CR, Operator 托管)           │
   │     ▼                                                          │
   │  Iceberg 湖层: lakehouse/demo-fin/trade/ods_user_order        │
   │     │ Spark (SparkApplication CR, Operator 托管)              │
   │     ▼                                                          │
   │  Iceberg 仓层: .../dwd_user_order / .../dws_user_order_1d     │
   │     │ Doris External Catalog (直读 Iceberg)                   │
   │     ▼                                                          │
   │  Doris 集层: mv_dws_user_order_1d（物化视图, 在线服务）        │
   │                                                                │
   │  统一 SQL 网关 (Deployment, 无状态) ──联邦查询──┐              │
   └───────────────────────────────────────────────┼──────────────┘
                                                    ▼
                                     返回合并结果给客户（无 Pod 概念）
```

**关键不变量**：客户只操作「工作空间 / 数据项目 / 作业 / 查询」，所有 K8s 资源由封装层翻译并交 Operator 托管；同一份 Iceberg 数据被 Spark、Doris 共享，无冗余拷贝（呼应 L2.1 湖仓集一体）。

---

## 4. 步骤 1：建工作空间与数据项目（封装层 API）

客户通过封装层 REST API（或控制台 v0.3「工作空间」页）发起，底层 K8s 动作对客户透明。

```text
POST /api/v1/workspaces
{
  "name": "demo-fin",
  "displayName": "金融演示空间",
  "quota": { "cpu": "16", "memory": "64Gi", "storage": "500Gi" },
  "tenantType": "internal"
}
→ 200 { "workspaceId": "ws-demo-fin",
        "k8s": { "namespace": "ws-demo-fin",
                 "resourceQuota": "applied",
                 "networkPolicy": "deny-all" } }   # 客户通常不看 k8s 字段

POST /api/v1/workspaces/ws-demo-fin/projects
{
  "name": "trade",
  "displayName": "交易域",
  "storagePrefix": "lakehouse/demo-fin/trade"
}
→ 200 { "projectId": "ws-demo-fin/trade",
        "storagePrefix": "lakehouse/demo-fin/trade/",
        "labels": { "ws": "demo-fin", "project": "trade" } }
```

**封装层翻译（R1/R2，见封装层 v0.1 §4）**：
- `workspace` → Namespace `ws-demo-fin` + ResourceQuota + NetworkPolicy `deny-all`
- `project` → 标签 `ws=demo-fin,project=trade` + 存储前缀 `lakehouse/demo-fin/trade/`，作为所有 Iceberg 表 warehouse 路径

---

## 5. 步骤 2：实时入湖（Flink CDC → Iceberg 湖层）

客户在「数据集成 / 数据开发」提交 Flink SQL 作业，封装层翻译为 `FlinkDeployment` CR；Pod 由 Flink Operator 托管。

```sql
-- 作业名: cdc-user-order  (客户视角: 一个"同步任务")
CREATE TABLE mysql_user_order (
  order_id     BIGINT,
  user_id      BIGINT,
  amount       DECIMAL(18,2),
  status       STRING,
  update_time  TIMESTAMP(3),
  PRIMARY KEY (order_id) NOT ENFORCED
) WITH (
  'connector' = 'mysql-cdc',
  'hostname'  = '${secret.mysql.host}',
  'port'      = '3306',
  'username'  = '${secret.mysql.user}',
  'password'  = '${secret.mysql.pass}',   -- 经封装层 Secret 注入, 不落明文
  'database-name' = 'fin',
  'table-name'    = 'user_order'
);

CREATE TABLE iceberg_ods_user_order (
  order_id     BIGINT,
  user_id      BIGINT,
  amount       DECIMAL(18,2),
  status       STRING,
  update_time  TIMESTAMP(3),
  PRIMARY KEY (order_id) NOT ENFORCED
) WITH (
  'format'      = 'iceberg',
  'catalog-type'= 'hadoop',
  'warehouse'   = 'lakehouse/demo-fin/trade',   -- 来自 project.storagePrefix
  'table'       = 'ods_user_order'
);

INSERT INTO iceberg_ods_user_order
SELECT order_id, user_id, amount, status, update_time
FROM mysql_user_order;
```

**验证 V2**：在 MySQL 执行 `UPDATE user_order SET amount=99.9 WHERE order_id=1;`，3~5s 后在 Iceberg 湖层 `ods_user_order` 查到新值（快照可见）。底层 Flink 作业 Pod 由 Operator 自愈，客户无感。

---

## 6. 步骤 3：湖 → 仓主题建模（Spark SQL）

客户提交 Spark 批作业（封装层翻译为 `SparkApplication` CR），从湖层产出主题层，数据仍驻 Iceberg，不拷贝。

```sql
-- 作业名: spark-dwd-user-order
-- 明细层 dwd
INSERT OVERWRITE iceberg_dwd_user_order
SELECT
  order_id, user_id, amount, status,
  DATE(update_time) AS order_date,
  update_time
FROM iceberg_ods_user_order
WHERE update_time >= current_date;

-- 汇总层 dws (近1日)
INSERT OVERWRITE iceberg_dws_user_order_1d
SELECT
  order_date,
  COUNT(*)                 AS order_cnt,
  SUM(amount)              AS total_amount,
  COUNT(DISTINCT user_id)  AS uv
FROM iceberg_dwd_user_order
GROUP BY order_date;
```

**验证 V3**：`dwd_user_order` / `dws_user_order_1d` 与 `ods_user_order` 共享同一 warehouse 路径，仅 database 分层不同；存储成本不随层数线性增长（湖仓集一体核心收益，呼应 L2.1）。

---

## 7. 步骤 4：湖仓集联动（Doris External Catalog + 物化视图）

Doris 作为「集层」承载在线查询，经 External Catalog 直读 Iceberg，无需数据导入；对高频汇总建物化视图加速。

```sql
-- Doris 侧: 建 Iceberg External Catalog (由封装层在数据项目初始化时自动建)
CREATE EXTERNAL CATALOG IF NOT EXISTS iceberg_trade PROPERTIES (
  "type" = "iceberg",
  "iceberg.catalog.type" = "hadoop",
  "warehouse" = "lakehouse/demo-fin/trade"
);

-- 集层物化视图: 直接基于 Iceberg dws 层
CREATE MATERIALIZED VIEW mv_dws_user_order_1d
DISTRIBUTED BY HASH(order_date)
AS
SELECT order_date, order_cnt, total_amount, uv
FROM iceberg_trade.dwd.dws_user_order_1d;
```

**验证 V4**：Doris 直接 `SELECT * FROM iceberg_trade.dwd.dws_user_order_1d;` 命中 Iceberg 数据；`mv_dws_user_order_1d` 提供毫秒级在线查询，承接 BI / API 高并发（呼应 L2「集=实时在线服务」）。

---

## 7.5 步骤 4.5：治理闭环（L3.1 元数据注册 + L3.3 质量校验 + L3.4 血缘追踪）★ P1-E4 修复

> 原 PoC v0.1 步骤 5 仅提及"L3.7 策略经网关强制生效"，未展示完整治理闭环。本步骤在数据入湖（步骤 2）与主题建模（步骤 3）之后、服务开放（步骤 5/5.5）之前插入治理环节，形成"采集元数据 → 检质量 → 采血缘 → 入资产目录"闭环（呼应产品原型 §7.2 治理闭环）。

### 7.5.1 L3.1 元数据注册（自研轻量 Catalog）

步骤 2/3/4 产出的 Iceberg 表自动注册到自研轻量 Catalog（L3.1，Java/Go，§5.5 拍板），无需客户手工登记。

```text
# 引擎侧自动事件（无需客户操作）
Iceberg 表创建/变更 → Kafka topic sq.metadata.change
  → L3.1 自研轻量 Catalog 订阅 → 注册表结构 + 字段 + 分区 + owner
  → L3.5 资产目录（L5.1 控制台内）同步可见
```

客户在控制台「资产目录」页可检索到 `ods_user_order` / `dwd_user_order` / `dws_user_order_1d` / `mv_dws_user_order_1d` 四张表，含字段、分区、owner、更新时间。

### 7.5.2 L3.3 数据质量校验（自研规则引擎）

客户在「治理 → 数据质量」为 `ods_user_order` 配置规则（自研规则引擎，Java，§5.5 拍板；Griffin 已 Apache 停孵不可选）：

```yaml
# 规则示例: 订单金额非负 + 订单状态枚举 + 主键唯一
rules:
  - name: amount_non_negative
    table: ods_user_order
    expression: "amount >= 0"
    severity: BLOCK
  - name: status_enum
    table: ods_user_order
    expression: "status IN ('PAID','REFUNDED','CLOSED')"
    severity: WARN
  - name: pk_unique
    table: ods_user_order
    expression: "COUNT(DISTINCT order_id) = COUNT(*)"
    severity: BLOCK
```

调度编排（L4.2 DolphinScheduler）在步骤 2 CDC 作业完成后自动触发质量校验：

```text
Flink CDC 作业完成 → Kafka topic sq.job.complete
  → L3.3 自研规则引擎消费 → 执行规则 → 输出质量分
  → 弱规则告警控制台；强规则阻断下游步骤 3
```

**验证 V4.5-Q**：`ods_user_order` 质量分 ≥ 95 分（强规则全过），下游步骤 3 Spark 作业允许执行；若注入脏数据（`amount=-1`），强规则阻断，控制台红色告警。

### 7.5.3 L3.4 数据血缘追踪（表级 + 字段级）

血缘由 L3.4 自动从 Spark/Flink SQL 解析采集，无需客户手工标注：

```text
步骤 2 Flink SQL: mysql_user_order → iceberg_ods_user_order
  → L3.4 解析 SQL → 表级血缘: mysql_user_order → ods_user_order
  → 字段级血缘: order_id/user_id/amount/status/update_time 全字段映射

步骤 3 Spark SQL: ods_user_order → dwd_user_order → dws_user_order_1d
  → L3.4 解析 SQL → 表级血缘链: ods → dwd → dws
  → 字段级血缘: dws.order_cnt ← COUNT(*) ← dwd.order_id

步骤 4 Doris MV: dws_user_order_1d → mv_dws_user_order_1d
  → L3.4 解析 MV 定义 → 表级血缘: dws → mv_dws
```

客户在控制台「数据血缘」页查看 `mv_dws_user_order_1d` 的上游链路：

```text
mv_dws_user_order_1d
  ↑ (Doris MV)
dws_user_order_1d
  ↑ (Spark SQL: GROUP BY order_date)
dwd_user_order
  ↑ (Spark SQL: SELECT FROM ods)
ods_user_order
  ↑ (Flink CDC)
mysql.user_order（外部源）
```

**验证 V4.5-L**：在血缘页点击 `mv_dws_user_order_1d`，可下钻到外部源 `mysql.user_order`；点击任一字段（如 `order_cnt`），可看到字段级血缘 `COUNT(*) ← dwd.order_id`。影响分析：假设下线 `ods_user_order.amount` 字段，系统提示"将影响 dwd_user_order.amount → dws_user_order_1d.total_amount → mv_dws_user_order_1d.total_amount"。

### 7.5.4 治理闭环总结

```text
采集元数据(L3.1) → 检质量(L3.3) → 采血缘(L3.4) → 入资产目录(L3.5)
  ↑                                                        ↓
  └──────────── 监控反馈（质量分/血缘变更触发重检） ←──────┘
```

治理结果（质量分、分级、owner、血缘）反馈到资产目录与权限系统，形成闭环（呼应产品原型 §7.2）。

## 8. 步骤 5：统一 SQL 网关 联邦查询

客户在「统一 SQL」页（控制台 v0.3）或网关 API 提交单条 SQL，跨 Iceberg（湖仓）与 Doris（集）关联，网关负责解析、路由、权限下推、结果合并。

```text
POST /api/v1/sql/submit
{
  "workspaceId": "ws-demo-fin",
  "sql": "
    SELECT d.order_date,
           d.order_cnt,
           d.total_amount,
           m.user_id AS vip_user
    FROM iceberg_trade.dwd.dws_user_order_1d d
    LEFT JOIN iceberg_trade.dwd.dwd_user_order m
      ON d.order_date = m.order_date AND m.amount > 1000
    WHERE d.order_date = CURRENT_DATE
  ",
  "engines": ["iceberg", "doris"]   -- 客户只声明"查全部", 不指定引擎
}
```

**网关内部（见统一 SQL 网关 v0.1 §3）**：
1. Parser（ANTLR）解析 → 2. Planner（Calcite 联邦优化，下推投影/过滤到各引擎）→ 3. Router 分发：Iceberg 部分交 Spark/Trino、Doris 部分交 Doris FE → 4. Executor Adapter 取各端结果 → 5. Merger 按 `order_date` 关联合并 → 6. 权限脱敏下推（L3.7 策略经网关强制生效）。

**返回（节选）**：
```json
{
  "status": "SUCCESS",
  "rows": [
    {"order_date":"2026-08-01","order_cnt":1280,"total_amount":256000.50,"vip_user":42},
    {"order_date":"2026-08-01","order_cnt":1280,"total_amount":256000.50,"vip_user":57}
  ],
  "enginePlan": "iceberg(dws_user_order_1d)+doris(dwd_user_order) → merge on order_date",
  "elapsedMs": 320
}
```

**验证 V5**：单入口跨引擎关联成功，客户不感知背后 Iceberg / Doris 差异（对标星环 Quark）。

### 8.1 X4 APISIX 与 L2.7 统一 SQL 网关 职责边界（P2-E5 修复）

> 原文档未澄清 X4 API 网关与 L2.7 统一 SQL 网关的关系，可能误认为同一组件。本节明确两者职责边界。

| 维度 | X4 APISIX（南北向流量） | L2.7 统一 SQL 网关（东西向查询联邦） |
| --- | --- | --- |
| 流量方向 | 南北向：外部用户 → 平台 API | 东西向：SQL → 多引擎 |
| 入口 | 客户/控制台/运营后台/开放 API | 统一 SQL 查询入口 |
| 出口 | 平台内各服务（控制台/运营/SQL 网关/IDE/BI） | Spark/Flink/Trino/Doris/Iceberg |
| 核心能力 | 鉴权/限流/灰度/计量/路由/SSL 卸载 | SQL 解析/联邦优化/查询路由/结果合并/权限下推 |
| 协议 | HTTP/HTTPS/gRPC | SQL（JDBC/REST） |
| 选型 | Apache APISIX 3.8（§5.5 拍板） | 自研（Calcite/ANTLR） |

**本 PoC 步骤 5 的协作流程**：

```text
客户提交 SQL 查询:
  客户 → X4 APISIX（鉴权/限流/计量，南北向入口）
       → L2.7 统一 SQL 网关（解析/联邦优化/路由，东西向联邦）
            → Spark/Trino/Doris/Iceberg（执行）
       ← 合并结果 + L3.7 权限脱敏下推
  ← 返回结果给客户
```

> X4 是平台全局 API 网关（所有外部请求入口），L2.7 是 SQL 联邦查询网关（仅处理 SQL 路由）。两者是上下游关系，非同一组件。


---

## 8.5 步骤 5.5：BI 可视化（L4.4 Superset 建看板）★ P1-E4 修复

> 原 PoC v0.1 完全缺失 BI 可视化环节。本步骤在统一 SQL 查询（步骤 5）之后、客户无感知验证（步骤 6）之前插入 BI 步骤，验证"数据 → 治理 → 查询 → 看板"完整闭环。

### 8.5.1 Superset 接入数据源（经 L2.7 统一 SQL 网关）

Superset（L4.4，Apache Superset 4 + 自研大屏，§5.5 拍板）不直连 Doris/Trino，而是经 L2.7 统一 SQL 网关接入，确保权限/脱敏策略统一生效：

```text
Superset → L2.7 统一 SQL 网关（Trino 方言模式）
  → 网关鉴权（X1 Keycloak OIDC SSO，superset-values.yaml 已配置）
  → 网关下推查询到 Doris/Trino/Iceberg
  → 网关合并结果 + 应用 L3.7 脱敏策略
  → 返回 Superset 渲染
```

配置 Superset 数据源：

```python
# 代码示例：Superset 配置 L2.7 网关数据源（Python）
from superset.db_engine_specs import TrinoEngineSpec

# 数据源指向 L2.7 统一 SQL 网关（而非直连 Doris/Trino）
SQLALCHEMY_URI = "trino://sq-sql-gateway:8080/iceberg_trade"
# 经网关统一鉴权 + 脱敏下推，确保 BI 用户看到的字段已脱敏
```

### 8.5.2 建数据集 + 看板

客户在 Superset 建数据集与看板（ECharts 图表，§5.5 拍板）：

```text
1. 建数据集 ds_user_order_1d:
   SELECT order_date, order_cnt, total_amount, uv
   FROM iceberg_trade.dwd.dws_user_order_1d
   -- 经 L2.7 网关路由到 Doris（命中物化视图 mv_dws_user_order_1d）

2. 建看板 "交易域日报":
   - 折线图: order_date × order_cnt（ECharts line）
   - 柱状图: order_date × total_amount（ECharts bar）
   - 指标卡: 今日 order_cnt / total_amount / uv（ECharts gauge）

3. 嵌入控制台:
   看板 URL 嵌入 L5.1 控制台「数据项目 → 看板」页
   客户在控制台直接查看，不跳转 Superset 原生 UI
```

### 8.5.3 验证 V5.5-BI

| 验证点 | 通过标准 |
| --- | --- |
| 数据源经网关 | Superset 查询日志显示经 `sq-sql-gateway:8080`，非直连 Doris |
| 权限脱敏生效 | BI 用户（仅看聚合）看不到 `user_id` 明文，仅看 `uv` 汇总 |
| 物化视图命中 | 看板查询 P95 ≤ 200ms（Doris mv_dws 命中） |
| ECharts 渲染 | 折线/柱状/指标卡正常渲染（Apache ECharts，§5.5 拍板） |
| 控制台嵌入 | 看板在 L5.1 控制台内 iframe 展示，无 Superset 原生 UI 暴露 |

## 9. 步骤 6：客户无感知验证（黑盒确认）

| 客户动作 | 客户看到 | 平台内部（客户不可见） |
| --- | --- | --- |
| 建空间 | 「金融演示空间 已创建」 | Namespace + Quota + deny-all NetworkPolicy |
| 提交 CDC | 「同步任务 cdc-user-order 运行中」 | FlinkDeployment CR → JobManager/TaskManager Pod |
| 提交 Spark | 「批作业 spark-dwd 成功」 | SparkApplication CR → Driver/Executor Pod |
| 建物化视图 | 「物化视图已建」 | Doris BE 内部表，无 K8s 资源 |
| 统一查询 | 结果表格 | 网关 Deployment + 临时查询 Pod（用完即销） |

**验证 V6**：全程客户零接触 kubeconfig / Pod / YAML；故障切换（如 Flink Pod 重启）由 Operator 自愈，客户仅感知「任务持续运行」。

---

## 10. 四环境一致性（V7）

同一份作业与 SQL 在四环境运行，差异仅由 Profile + 驱动吸收：

| 维度 | xinchuang | onprem | publiccloud | privatecloud |
| --- | --- | --- | --- | --- |
| 存储驱动 | XCObjectDriver | CephDriver | S3Driver（客户提供密钥） | PrivateDriver(MinIO) |
| 镜像变体 | openeuler | rocky | ubuntu | 厂商 OS |
| 国密 | SM3 哈希启用 | 标准 | 标准 | SM4 启用 |
| 作业/SQL | **完全相同** | **完全相同** | **完全相同** | **完全相同** |
| 查询结果 | 一致 | 一致 | 一致 | 一致 |

公有云环境：`S3Driver` 仅消费客户在公有云自购 VM 上的对象存储，**不启用任何云托管 K8s / RDS / Kafka**（部署清单 v0.1 铁律）。

---

## 11. 验收标准（PoC 出口）

- [ ] V1~V7 全部通过，截图/日志留痕
- [ ] V4.5-Q 治理质量校验：脏数据强规则阻断下游（P1-E4 新增）
- [ ] V4.5-L 治理血缘追踪：字段级血缘下钻到外部源（P1-E4 新增）
- [ ] V5.5-BI BI 可视化：Superset 经 L2.7 网关建看板，脱敏生效（P1-E4 新增）
- [ ] 端到端时延：MySQL 变更 → 统一 SQL 可查 ≤ 30s（CDC 秒级 + 物化视图实时）
- [ ] 端到端时延：MySQL 变更 → BI 看板可看 ≤ 35s（含治理闭环 + 物化视图刷新，P1-E4 新增）
- [ ] 存储：湖/仓/集三层数据共享同一 warehouse，无冗余副本
- [ ] 客户视角操作零 K8s 概念暴露
- [ ] 四环境各跑一遍，结果字节级一致
- [ ] X4 与 L2.7 职责边界清晰：X4 处理南北向鉴权/限流，L2.7 处理东西向 SQL 联邦（P2-E5 新增）

---

## 12. 风险与排查

| 风险 | 现象 | 对策 |
| --- | --- | --- |
| Iceberg 小文件过多 | 查询变慢 | 封装层定时 `compact` 作业（L2.1 §6） |
| CDC 位点丢失 | 数据断流 | Flink checkpoint + 封装层自动从最近快照恢复 |
| Doris 物化视图不同步 | 在线查询陈旧 | 设 refresh 策略（异步/定时），网关读时校验版本 |
| 网关联邦下推失败 | 跨引擎关联超时 | Planner 降级为网关侧 merge，告警并记日志 |
| 信创 ARM 镜像缺失 | Pod 起不来 | build-images.yaml 已产 arm64 manifest，preflight 校验 arch |

---

## 13. 与整体交付物的关系

本文档是部署阶段（部署清单 v0.1 §12）末项，把「方案 v0.4 + UI v0.3 + 8 份后端契约 + deploy/ 骨架」收敛为**可演示闭环**：

```
方案 v0.4 ─┐
UI v0.3   ─┤
8 份契约  ─┼─→ deploy/ 骨架 ─→ 本 PoC（端到端跑通）
封装层    ─┤
统一存储  ─┘
```

下一步（GA 前）：基于本 PoC 扩展多租户并发、SLA 套餐、计量计费埋点（L5 运营后台），进入商业化压测。
