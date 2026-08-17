# 政企场景端到端验证

> 场景文档：`design/场景模拟/政企场景端到端演示.md`
> 行业模板：government（政务行业模板）

## 1. 场景概述

某副省级城市政务大数据平台，将全市 30+ 委办局（公安、人社、卫健、教育、民政、住建等）业务数据汇聚到统一数据湖仓，形成"一数一源、一源多用"的政务数据资产体系，支撑人口分析、经济运行、民生服务、政务合规四大主题应用。

## 2. 业务流程

```
委办局业务系统 → SeaTunnel CDC → Kafka → Flink → Iceberg ODS
→ DolphinScheduler 调度 Spark 治理（质量校验+脱敏+标准化） → DWD
→ Spark 主题域汇总 → Doris DWS/ADS
→ 统一 SQL 网关 → BI 看板 + 开放 API
→ Keycloak RBAC + ABAC + 审计留痕
```

## 3. 验证步骤

| # | 步骤 | 需集群 | 说明 |
|---|------|--------|------|
| 1 | 登录与租户上下文建立 | 否 | admin/admin 登录，切换到 gov-city 租户 |
| 2 | 30+ 委办局数据汇聚 - 创建 CDC 集成任务 | 否 | 抽样 6 个委办局创建 SeaTunnel CDC → Kafka 任务 |
| 3 | Flink 实时入湖作业 | 是 | Kafka → Iceberg ODS，需 Flink+Kafka+Iceberg |
| 4 | 多租户隔离验证 | 否 | 不同租户资产互不可见 |
| 5 | 数据标准落标验证 | 否 | 创建标准+关联资产+落标率统计 |
| 6 | 数据质量检查验证 | 否 | 6 类质量规则清单 |
| 7 | 数据资产目录验证 | 否 | 多层次资产注册+按类型过滤+Schema 查询 |
| 8 | 数据共享交换 - 权限申请与审批 | 否 | 跨委办局权限申请+审批流 |
| 9 | 脱敏规则配置 | 否 | 身份证/姓名/手机号/住址字段级脱敏 |
| 10 | 开放 API 服务目录 | 否 | 政务 API 注册+分类过滤 |
| 11 | T+1 治理 DAG 调度 | 是 | DolphinScheduler+Spark on Yarn |
| 12 | 审计留痕 | 是 | audit_log 保留 180 天+不可篡改 |

## 4. 运行方式

```bash
# 进入项目根目录
cd F:\nexus\DataEngineBDP

# 确保后端已启动（端口 18086）
netstat -ano | findstr :18086

# 运行政企场景验证
node tests/scenarios/government/scenario-government.test.js
```

## 5. 验证结果

执行后生成 `scenario-government.result.json`，包含每个步骤的 PASS/FAIL/SKIP 状态、断言明细、耗时等。

## 6. 需完整集群环境的项目

以下步骤需完整 K8s 集群（Spark/Flink/Doris/Kafka/Iceberg/DolphinScheduler）才能实际执行：

- Flink 实时入湖作业（Kafka → Iceberg ODS）
- T+1 治理 DAG 调度（质量校验+脱敏+标准化+DWD+DWS+ADS）
- 审计留痕（audit_log 保留 180 天+不可篡改）

当前封装层后端已验证 API 契约可用，集群就绪后可直接接入实数据流。