# 统一 SQL 网关 (SQL Gateway)

> 数据引擎大数据平台 · 统一 SQL 网关骨架（MVP）

## 项目用途

统一 SQL 网关是数据引擎大数据平台的 SQL 入口，负责将用户的 SQL 请求按规则路由到合适的后端查询引擎：

- **Trino**：交互式即席查询、跨源联邦查询。
- **Doris**：OLAP 加速查询、面向 BI 报表与 dashboard。

通过统一入口，平台可对 SQL 执行进行鉴权、限流、审计、缓存与多租户隔离，屏蔽后端差异。

## 技术栈

| 维度       | 选型                                   |
|------------|----------------------------------------|
| 语言       | Java 17                                |
| 框架       | Spring Boot 3.2.5                      |
| Web        | spring-boot-starter-web (Tomcat)       |
| 异步代理   | spring-boot-starter-webflux (WebClient)|
| 校验       | spring-boot-starter-validation         |
| 监控       | spring-boot-starter-actuator           |
| 构建       | Maven                                  |
| 容器       | 多阶段 Dockerfile (eclipse-temurin:17) |

## 目录结构

```
platform/sql-gateway/
├── pom.xml
├── Dockerfile
├── README.md
├── .gitignore
└── src/main/
    ├── java/com/shuqing/bigdata/sqlgateway/
    │   ├── SqlGatewayApplication.java
    │   ├── controller/
    │   │   ├── HealthController.java
    │   │   └── SqlGatewayController.java
    │   ├── model/
    │   │   ├── RouteRule.java
    │   │   ├── SqlExecuteRequest.java
    │   │   └── SqlExecuteResponse.java
    │   └── service/
    │       ├── BackendProxyService.java
    │       └── SqlRoutingService.java
    └── resources/
        └── application.yml
```

## 构建方式

```bash
# 本地构建（跳过测试）
mvn -B clean package -DskipTests

# 生成可执行 jar
target/sql-gateway-0.1.0.jar
```

## 运行方式

```bash
# 本地运行
java -jar target/sql-gateway-0.1.0.jar

# 容器构建与运行
docker build -t shuqing/sql-gateway:0.1.0 platform/sql-gateway
docker run --rm -p 8081:8081 shuqing/sql-gateway:0.1.0
```

默认监听端口：`8081`。

## API 端点列表

| 方法 | 路径                    | 说明                          |
|------|-------------------------|-------------------------------|
| POST | `/api/v1/sql/execute`   | 执行 SQL（路由到 Trino/Doris）|
| GET  | `/api/v1/sql/routes`    | 列出当前路由规则              |
| POST | `/api/v1/sql/routes`    | 添加路由规则                  |
| GET  | `/api/v1/sql/engines`   | 列出可用引擎 `["trino","doris"]` |
| GET  | `/api/v1/health`        | 网关健康检查                  |
| GET  | `/actuator/health`      | Spring Boot Actuator 健康检查 |
| GET  | `/actuator/info`        | 应用信息                      |
| GET  | `/actuator/metrics`     | 指标度量                      |

### 执行 SQL 示例

```bash
curl -X POST http://localhost:8081/api/v1/sql/execute \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT count(*) FROM orders WHERE dt = '"'"'2024-01-01'"'"'",
    "engine": "trino",
    "tenantId": "demo",
    "limit": 100
  }'
```

MVP 阶段响应（模拟）：

```json
{
  "queryId": "8f4b...-uuid",
  "status": "SIMULATED",
  "columns": [],
  "rows": [],
  "durationMs": 1,
  "engine": "trino"
}
```

## 路由规则说明

路由优先级（自高至低）：

1. **请求显式指定**：`SqlExecuteRequest.engine` 非空时直接使用。
2. **路由规则匹配**：按 `priority` 升序遍历启用的规则，SQL 文本包含 `pattern`（大小写不敏感）即命中。
3. **默认引擎**：`sql-gateway.default-engine`（默认 `trino`）。

路由规则 MVP 阶段存储于内存 `ConcurrentHashMap`，重启后丢失；后续将持久化至 PostgreSQL/Redis。

添加规则示例：

```bash
curl -X POST http://localhost:8081/api/v1/sql/routes \
  -H "Content-Type: application/json" \
  -d '{
    "pattern": "INSERT INTO",
    "engine": "doris",
    "priority": 10,
    "enabled": true
  }'
```

## 与 Trino / Doris 的关系

| 后端  | 用途               | 配置项                            | 默认地址                  |
|-------|--------------------|-----------------------------------|---------------------------|
| Trino | 交互查询 / 联邦    | `sql-gateway.backends.trino.url`  | `http://trino-service:8080`   |
| Doris | OLAP / 报表        | `sql-gateway.backends.doris.url`  | `http://doris-fe-service:9030`|

`BackendProxyService` 通过 WebFlux `WebClient` 代理后端请求：

- `proxyToTrino(sql)`：将接入 Trino Statement API（`POST /v1/statement`）。
- `proxyToDoris(sql)`：将接入 Doris FE HTTP API。

> **MVP 约束**：当前不实际连接后端，仅返回 `SIMULATED` 模拟结果。后续阶段将逐步接入真实后端、鉴权、限流与审计。