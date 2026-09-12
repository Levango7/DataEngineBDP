# Data Standard - 数据引擎大数据平台数据标准治理模块（L3.2）

## 项目用途

数据引擎大数据平台（DataEngineBDP）的数据标准治理层模块，用于：

- **数据标准管理**：定义、维护企业级数据标准（字段命名、数据类型、值域、格式等）
- **标准分类管理**：按业务域 / 主题域对数据标准进行分类组织
- **标准版本管理**：支持数据标准的版本迭代与发布状态流转
- **JSON Schema 约束**：以 JSON Schema 描述数据结构约束，供下游校验引擎消费

本模块是 L3.2 数据标准治理层的核心服务，为数据质量检查、数据集成、数据服务提供统一的标准定义源。

## 技术栈

- Java 17
- Spring Boot 3.2.x
- Spring Data JPA + H2（开发）/ PostgreSQL（生产）
- Lombok
- Maven

## 构建方式

```bash
cd platform/data-standard
mvn -B -DskipTests clean package
```

构建产物：`target/data-standard-0.1.0.jar`

## 运行方式

```bash
java -jar target/data-standard-0.1.0.jar
```

默认监听端口 **8087**。

## API 端点列表

### 数据标准管理

| 方法   | 路径                              | 说明               |
|--------|-----------------------------------|--------------------|
| POST   | /api/v1/standards                 | 创建数据标准（201）|
| GET    | /api/v1/standards                 | 分页查询数据标准   |
| GET    | /api/v1/standards/{id}            | 获取单个数据标准   |
| PUT    | /api/v1/standards/{id}            | 更新数据标准       |
| DELETE | /api/v1/standards/{id}            | 删除数据标准       |

### 标准分类管理

| 方法   | 路径                              | 说明               |
|--------|-----------------------------------|--------------------|
| GET    | /api/v1/standard-categories       | 列出所有标准分类   |
| POST   | /api/v1/standard-categories       | 创建标准分类（201）|

Actuator 端点：`/actuator/health`、`/actuator/info`、`/actuator/metrics`、`/actuator/prometheus`

## 数据标准定义示例

```json
{
  "name": "std-user-id",
  "category": "用户域",
  "description": "用户唯一标识，正整数，非空",
  "rule": "type=integer; min=1; not_null=true",
  "jsonSchema": "{\"type\":\"integer\",\"minimum\":1}",
  "version": "1.0.0",
  "status": "PUBLISHED",
  "tenantId": "default"
}
```

## 多租户隔离

所有数据标准与分类均携带 `tenantId` 字段，按租户隔离查询 / 更新 / 删除，避免跨租户数据泄漏。

## 路线规划

1. MVP（当前）：标准 CRUD + 分类管理 + JPA 持久化
2. v0.2：标准版本审批流 + JSON Schema 在线校验
3. v0.3：标准映射（字段 → 标准）+ 标准合规度评估
4. v1.0：标准市场 + 跨租户标准共享 + 标准变更影响分析