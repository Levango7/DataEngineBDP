# 数擎大数据平台用户手册

> 版本：V2.0 | 适用对象：数据工程师、数据分析师、业务用户、租户管理员 | 更新日期：2026-08-08

## 第1章 平台概述

### 1.1 产品定位

数擎大数据平台（ShuqingBigDataPlatform）是一款面向企业级数据治理与分析的一站式大数据平台，V2.0 在 V1.0 基础上完成云原生、AI、数据联邦、实时数仓与行业模板五大能力跃迁。平台以"多平台多租户、零改动交付"为核心设计理念，支持信创、本地数据中心、公有云、私有云四环境统一镜像交付。

### 1.2 核心能力

| 能力域 | 关键特性 |
|--------|----------|
| 多平台多租户 | 信创/本地/公有云/私有云四环境零改动交付；基于 K8s Namespace + ResourceQuota + NetworkPolicy 三重隔离 |
| 云原生 | K8s 1.28+ + Helm 3.14+ + ArgoCD GitOps + Service Mesh(Istio) |
| AI 能力 | NL2SQL 自然语言查询、AI 助手、多模态切片器、混合检索重排 |
| 数据联邦 | Calcite 优化器、跨源 Join、5 种外部源虚拟化（MySQL/Oracle/JDBC/Kafka/REST） |
| 实时数仓 | Flink CDC + Iceberg V2 upsert + Doris 物化视图 |
| 行业模板 | 金融/能源/政务三个行业开箱即用模板 |
| 安全合规 | 等保三级 + 国密（SM2/SM3/SM4） |
| 统一 SQL 网关 | Trino + Doris 路由 + 查询改写 |

### 1.3 适用场景

- **金融行业**：风控评分卡、监管报表、反洗钱规则引擎
- **能源行业**：设备监测、用能分析、碳排放核算
- **政务行业**：人口分析、经济运行、民生服务
- **企业数据中台**：数据集成、开发、调度、治理、共享全流程
- **实时数仓**：CDC 实时入仓、流批一体、亚秒级查询
- **数据联邦**：跨源 Join 查询、虚拟化访问、避免数据搬运

## 第2章 快速入门

### 2.1 登录平台

1. 浏览器访问平台入口：`https://<platform-domain>/`
2. 输入租户管理员分配的用户名与密码（首次登录由租户管理员通过 Keycloak 创建）
3. 完成多因子认证（如已启用）
4. 进入工作台首页，可见左侧导航：数据集成、数据开发、调度编排、BI 可视化、AI 助手、数据联邦、实时数仓、标签画像、机器学习、治理中心

### 2.2 创建租户

租户创建需平台管理员权限，调用封装层 API 完成：

```bash
curl -X POST https://<platform-domain>/api/v1/tenants \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "tenant-finance",
    "displayName": "金融业务租户",
    "namespace": "ns-finance",
    "quota": {"cpu": "20", "memory": "40Gi", "storage": "500Gi"}
  }'
```

返回 201 表示创建成功，平台会自动创建对应 K8s Namespace、ResourceQuota、NetworkPolicy 与 Keycloak 子域。

### 2.3 配置数据源

1. 进入「治理中心 → 数据源管理」
2. 点击「新增数据源」，选择类型（MySQL/Oracle/JDBC/Kafka/REST/Hive/Iceberg 等）
3. 填写连接信息：主机、端口、用户名、密码、数据库
4. 点击「测试连接」，确认连通性
5. 保存后可在数据开发 IDE 与 SQL 网关中使用

### 2.4 提交 SQL 查询

通过 SQL 网关统一入口提交查询：

```bash
curl -X POST https://<platform-domain>/api/v1/sql/execute \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT customer_id, SUM(amount) FROM transaction WHERE occurred_at >= \"2026-01-01\" GROUP BY customer_id LIMIT 100",
    "tenantId": "tenant-finance",
    "dialect": "trino"
  }'
```

平台将根据路由规则自动选择 Trino 或 Doris 引擎执行，并返回结果集。

## 第3章 功能模块使用

### 3.1 数据集成

数据集成基于 Apache SeaTunnel 提供丰富连接器，支持批流一体数据同步。

#### 3.1.1 SeaTunnel 连接器配置

1. 进入「数据集成 → 任务管理」
2. 点击「新建任务」，选择 Source 与 Sink 连接器
3. 配置 Source（以 MySQL CDC 为例）：

```yaml
source:
  MySQL-CDC:
    hostname: 10.0.0.10
    port: 3306
    username: cdc_user
    password: ${SECRET_KEY}
    database-name: finance_db
    table-name: transaction
    server-id: 5400-5404
    startup.mode: latest
```

4. 配置 Sink（以 Iceberg 为例）：

```yaml
sink:
  Iceberg:
    catalog-name: shuqing_catalog
    warehouse: s3://shuqing/warehouse
    database: finance_iceberg
    table: transaction_iceberg
    write.mode: upsert
    primary-keys: transaction_id
```

5. 配置调度策略（Cron 或事件触发）
6. 提交任务，可在任务列表查看运行状态、检查点、延迟指标

### 3.2 数据开发 IDE

数据开发 IDE 基于 Eclipse Theia 二开，提供 SQL 与 Python 双语言开发环境。

#### 3.2.1 SQL 开发

1. 进入「数据开发 → IDE」，新建 SQL 文件
2. 选择数据源与方言（Trino/Doris/Hive/Spark）
3. 编写 SQL，支持语法高亮、自动补全、错误提示
4. 点击「执行」，结果在下方面板展示，支持图表可视化
5. 可保存为脚本、加入调度 DAG、或发布为 API

#### 3.2.2 Python 开发

1. 新建 Python Notebook 或脚本
2. 内置 PySpark、Pandas、Scikit-learn、MLflow SDK
3. 可直接读取平台数据：

```python
from shuqing_sdk import DataSource
ds = DataSource(name="finance_doris")
df = ds.sql("SELECT * FROM transaction WHERE dt='2026-08-01'").to_pandas()
print(df.head())
```

### 3.3 调度编排

调度编排基于 Apache DolphinScheduler 3.2，支持 DAG 可视化编排。

#### 3.3.1 DAG 配置

1. 进入「调度编排 → 项目管理」，新建项目
2. 新建工作流，拖拽任务节点到画布
3. 任务类型支持：Shell、SQL、Python、Spark、Flink、HTTP、子流程、依赖
4. 配置任务参数与上下游依赖
5. 设置调度策略：

```json
{
  "schedule": {
    "crontab": "0 1 * * * ?",
    "timezone": "Asia/Shanghai",
    "startTime": "2026-01-01T00:00:00",
    "failureStrategy": "CONTINUE",
    "alertGroup": "ops-alert"
  }
}
```

6. 上线工作流，可在监控页面查看运行历史、甘特图、执行日志

### 3.4 BI 可视化

BI 可视化基于 Apache Superset，提供拖拽式看板构建。

#### 3.4.1 看板创建

1. 进入「BI 可视化 → 数据集」，注册数据集（基于已配置数据源）
2. 点击「新建看板」，选择布局模板
3. 拖拽图表组件：折线图、柱状图、饼图、地图、透视表、指标卡
4. 配置图表数据源与样式
5. 设置过滤器与交互联动
6. 保存看板，可分享给同租户用户或嵌入外部页面

### 3.5 AI 助手

AI 助手提供 NL2SQL 自然语言查询能力，降低数据消费门槛。

#### 3.5.1 NL2SQL 查询

1. 进入「AI 助手 → 查询」
2. 选择目标数据集（已注册的数据表或视图）
3. 输入自然语言问题，例如：

> 查询最近 30 天交易金额 TOP10 客户及其风险等级

4. AI 助手自动生成 SQL 并展示，可编辑后执行
5. 结果以表格 + 图表形式展示，可一键生成看板

#### 3.5.2 AI 助手配置

- 模型选择：内置 GLM、Qwen、GPT 等模型路由
- Prompt 模板：可在「治理中心 → AI 配置」自定义
- 安全策略：敏感字段自动脱敏、查询行数限制、执行前预览

### 3.6 数据联邦

数据联邦支持跨源 Join 查询，无需数据搬运即可联合多源数据。

#### 3.6.1 跨源查询配置

1. 进入「数据联邦 → 虚拟表」，注册外部源为虚拟表：

```bash
curl -X POST https://<platform-domain>/api/v1/virtual-tables \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "tableName": "v_mysql_orders",
    "dataSourceType": "MYSQL",
    "connection": {
      "host": "10.0.0.20", "port": 3306,
      "username": "ro_user", "password": "${SECRET}",
      "database": "order_db", "table": "orders"
    },
    "schema": [{"name": "order_id", "type": "BIGINT"}, {"name": "amount", "type": "DECIMAL(18,2)"}]
  }'
```

2. 在 SQL 网关执行跨源查询：

```sql
SELECT c.customer_name, SUM(v.amount) AS total_amount
FROM doris.customer c
JOIN v_mysql_orders v ON c.customer_id = v.customer_id
WHERE v.occurred_at >= DATE '2026-08-01'
GROUP BY c.customer_name
ORDER BY total_amount DESC
LIMIT 50;
```

3. 平台自动识别跨源，调用 Calcite 优化器生成执行计划，单源直接代理、多源并行查询 + 内存归并

### 3.7 实时数仓

实时数仓基于 Flink CDC + Iceberg V2 upsert + Doris 物化视图，提供亚秒级查询。

#### 3.7.1 Flink CDC 管道配置

1. 进入「实时数仓 → CDC 管道」，新建管道
2. 配置 Source（MySQL CDC）、Sink（Iceberg V2）
3. 配置 Flink 作业参数（并行度、Checkpoint 间隔、State TTL）
4. 启动管道，监控延迟、吞吐、Checkpoint 状态
5. 在 Doris 创建物化视图加速查询：

```sql
CREATE MATERIALIZED VIEW mv_customer_daily_amount AS
SELECT customer_id, DATE(occurred_at) AS dt, SUM(amount) AS daily_amount
FROM iceberg.finance.transaction_iceberg
GROUP BY customer_id, DATE(occurred_at);
```

### 3.8 标签画像

标签画像引擎支持人群圈选与画像计算。

#### 3.8.1 人群圈选

1. 进入「标签画像 → 标签管理」，查看已有标签体系
2. 进入「人群圈选」，输入圈选条件：

```
(标签.高净值客户 = 是) AND (标签.风险等级 IN [低,中]) AND (年龄 BETWEEN 30 AND 50)
```

3. 点击「计算」，返回人群数量与样本
4. 可保存为人群包，用于营销推荐或风控策略

### 3.9 机器学习

机器学习平台基于 MLflow 提供实验管理、模型注册、模型服务。

#### 3.9.1 MLflow 实验管理

1. 进入「机器学习 → 实验」，新建实验
2. 在 Python IDE 中训练模型并记录：

```python
import mlflow
mlflow.set_experiment("credit_score_v2")
with mlflow.start_run():
    model = train_model(X_train, y_train)
    mlflow.log_metric("auc", 0.85)
    mlflow.log_metric("ks", 0.42)
    mlflow.sklearn.log_model(model, "model")
```

3. 在实验面板对比不同 Run 的指标
4. 选择最优模型注册到模型仓库
5. 部署为在线推理服务或批量评分作业

## 第4章 行业模板使用

平台预置金融、能源、政务三个行业模板，详见《行业模板使用指南》。快速部署示例：

```bash
# 部署金融模板
helm install finance-template ./charts/finance-template \
  -n ns-finance --create-namespace \
  -f finance-values.yaml

# 部署能源模板
helm install energy-template ./charts/energy-template \
  -n ns-energy --create-namespace \
  -f energy-values.yaml

# 部署政务模板
helm install government-template ./charts/government-template \
  -n ns-government --create-namespace \
  -f government-values.yaml
```

部署完成后，模板中的 DDL 表、DAG 作业、Superset 看板、RBAC 角色将自动注入到目标租户。

## 第5章 多租户管理

### 5.1 租户创建

平台管理员通过封装层 API 或运营后台创建租户：

```bash
curl -X POST https://<platform-domain>/api/v1/tenants \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"name":"tenant-energy","displayName":"能源租户","namespace":"ns-energy"}'
```

### 5.2 资源配额

通过 ResourceQuota 限制租户资源使用：

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: ns-energy-quota
  namespace: ns-energy
spec:
  hard:
    requests.cpu: "50"
    requests.memory: 100Gi
    persistentvolumeclaims: "20"
    requests.storage: 1Ti
```

也可通过封装层 Quota API 动态调整：

```bash
curl -X PUT https://<platform-domain>/api/v1/quotas/ns-energy \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -d '{"cpu":"80","memory":"160Gi","storage":"2Ti"}'
```

### 5.3 权限管理

权限基于 Keycloak + RBAC 模型：

1. 在 Keycloak 创建租户子域与角色（如 data_engineer、data_analyst、tenant_admin）
2. 为角色分配资源权限（表、DAG、看板的读/写/执行）
3. 用户绑定角色后即获得对应权限
4. 敏感数据访问受数据分级策略控制（L1~L4）

## 第6章 常见问题 FAQ

### Q1：登录后看不到任何菜单？

A：联系租户管理员分配角色权限。新用户默认无任何资源访问权限。

### Q2：SQL 查询超时？

A：1) 检查查询数据量是否过大，考虑加分区过滤；2) 在 SQL 网关调整 `query.timeout` 参数；3) 大查询路由到 Trino 而非 Doris；4) 参见运维手册「SQL 查询超时排查」章节。

### Q3：跨源 Join 报错"source not found"？

A：确认外部源已通过 `/api/v1/virtual-tables` 注册且租户 ID 匹配。可通过 `GET /api/v1/virtual-tables` 查看当前租户全部虚拟表。

### Q4：CDC 管道延迟持续上升？

A：1) 检查 Source 数据库负载；2) 增加 Flink 作业并行度；3) 检查 Checkpoint 是否成功；4) 确认 Iceberg Sink 写入无瓶颈。

### Q5：AI 助手生成的 SQL 不准确？

A：1) 完善数据集的 schema 描述与字段注释；2) 在 Prompt 模板中加入业务术语词典；3) 切换更强大的模型；4) 启用 NL2SQL 的 few-shot 示例。

### Q6：如何切换部署环境（信创/本地/公有云/私有云）？

A：平台四环境零改动交付，仅需切换 `values.yaml` 中的 `environment.profile` 参数（xinchuang/local/public-cloud/private-cloud），无需修改镜像或代码。

### Q7：租户资源不足如何扩容？

A：联系平台管理员调整 ResourceQuota，或通过 HPA 自动扩缩容。租户管理员可在「治理中心 → 资源监控」查看使用率。

### Q8：行业模板如何自定义？

A：基于现有模板二次开发：1) 修改 DDL 表结构；2) 增删 DAG 作业；3) 自定义看板；4) 调整 RBAC 角色。详见《行业模板使用指南》「模板定制化」章节。

### Q9：国密算法如何启用？

A：在 `values.yaml` 中设置 `security.crypto.provider: GM`，平台自动启用 SM2（签名/密钥交换）、SM3（摘要）、SM4（对称加密）替代 RSA/SHA/AES。

### Q10：如何获取平台 API Token？

A：通过 Keycloak OAuth2 端点获取 JWT：

```bash
curl -X POST https://<platform-domain>/realms/<tenant>/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=shuqing-cli" \
  -d "username=<user>" \
  -d "password=<password>"
```

返回的 `access_token` 即为 JWT，有效期默认 30 分钟，可通过 `refresh_token` 刷新。详见《API 参考文档》。

## 第7章 附录

### 7.1 相关文档

- 《API 参考文档》（api-reference.md）
- 《运维手册》（ops-manual.md）
- 《升级指南》（upgrade-guide.md）
- 《行业模板使用指南》（industry-template-guide.md）
- 《架构设计文档》（../architecture.md）
- 《部署指南》（../deployment-guide.md）

### 7.2 术语表

| 术语 | 说明 |
|------|------|
| 租户（Tenant） | 平台资源隔离的最小业务单元，对应一个 K8s Namespace |
| 工作空间（Workspace） | 租户内项目级隔离单元 |
| 虚拟表（Virtual Table） | 外部数据源在平台的虚拟化映射 |
| CDC | Change Data Capture，变更数据捕获 |
| NL2SQL | Natural Language to SQL，自然语言转 SQL |
| RBAC | Role-Based Access Control，基于角色的访问控制 |
| GitOps | 基于 Git 仓库的声明式部署运维模式 |

### 7.3 联系支持

- 平台运维：ops@shuqing.com
- 技术支持：support@shuqing.com
- 文档反馈：docs@shuqing.com
- 紧急故障：400-SHUQING-OPS（7×24）