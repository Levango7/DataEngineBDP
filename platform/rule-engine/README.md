# Rule Engine - 数据引擎大数据平台自研规则引擎

## 项目用途

数据引擎大数据平台（DataEngineBDP）的统一规则执行框架，用于：

- **数据质量检查（DQ）**：对数据集执行空值、唯一性、值域、引用完整性等检查
- **数据脱敏（MASK）**：对敏感字段执行脱敏策略（掩码、替换、哈希等）
- **告警（ALERT）**：基于阈值或表达式触发告警事件

通过统一的规则定义、加载与执行 API，将上述三类规则收敛到同一引擎中，便于版本管理、审计与可观测性建设。

## 技术栈

- Java 17
- Spring Boot 3.2.5
- Lombok
- Maven

## 构建方式

```bash
cd platform/rule-engine
mvn -B -DskipTests clean package
```

构建产物：`target/rule-engine-0.1.0.jar`

## 运行方式

```bash
java -jar target/rule-engine-0.1.0.jar
```

默认监听端口 **8083**。

Docker 运行：

```bash
docker build -t shuqing/rule-engine:0.1.0 .
docker run -p 8083:8083 shuqing/rule-engine:0.1.0
```

## API 端点列表

| 方法   | 路径                       | 说明         |
|--------|----------------------------|--------------|
| POST   | /api/v1/rules              | 创建规则     |
| GET    | /api/v1/rules              | 列出所有规则 |
| GET    | /api/v1/rules/{id}         | 获取单个规则 |
| PUT    | /api/v1/rules/{id}         | 更新规则     |
| DELETE | /api/v1/rules/{id}         | 删除规则     |
| POST   | /api/v1/rules/execute      | 执行规则     |
| GET    | /api/v1/rules/types        | 列出规则类型 |
| GET    | /api/v1/health             | 健康检查     |

Actuator 端点：`/actuator/health`、`/actuator/info`、`/actuator/metrics`

## 规则类型说明

| 类型   | 含义           | 执行器                |
|--------|----------------|-----------------------|
| DQ     | 数据质量检查   | DqRuleExecutor        |
| MASK   | 数据脱敏       | MaskRuleExecutor      |
| ALERT  | 告警           | AlertRuleExecutor     |

每种类型对应一个 `RuleExecutor` 实现，通过 `getType()` 注册到 `RuleExecutionService` 的分派表。

## 规则定义示例

```json
{
  "name": "dq-not-null",
  "description": "字段 user_id 不能为空",
  "type": "DQ",
  "expression": "user_id IS NOT NULL",
  "severity": "ERROR",
  "enabled": true
}
```

## 执行请求示例

```bash
curl -X POST http://localhost:8083/api/v1/rules/execute \
  -H "Content-Type: application/json" \
  -d '{"ruleId": 1, "context": {"table": "t_user"}, "tenantId": "default"}'
```

MVP 阶段返回模拟结果：

```json
{
  "ruleId": 1,
  "status": "PASS",
  "message": "SIMULATED",
  "details": {"type": "DQ", "expression": "user_id IS NOT NULL"},
  "durationMs": 1,
  "executedAt": "2026-08-05T12:34:56"
}
```

## 与数据质量 / 脱敏 / 告警的关系

本引擎是数据引擎大数据平台三类治理能力的统一执行入口：

- **数据质量**：DQ 规则由数据集成 / 调度平台在 ETL 后触发，结果写入质量度量库
- **数据脱敏**：MASK 规则由数据服务 / API 网关在查询返回前触发，按租户隔离策略
- **告警**：ALERT 规则由指标 / 日志流式计算触发，结果对接告警中心

后续路线：

1. MVP（当前）：内存存储 + 模拟执行
2. v0.2：接入表达式引擎（Aviator / MVEL）+ 持久化（PostgreSQL）
3. v0.3：规则版本管理 + 执行审计 + 分布式执行
4. v1.0：可视化规则编辑器 + 多租户策略隔离 + 性能基线