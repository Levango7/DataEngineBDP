# Master Data - 数据引擎大数据平台主数据管理模块（L3.6）

## 项目用途

数据引擎大数据平台（DataEngineBDP）的主数据管理模块，用于：

- **主数据模型定义**：定义企业核心主数据的结构 schema（如组织、人员、物料、客户等）
- **主数据记录管理**：基于模型定义创建、查询、更新、删除主数据记录
- **主数据版本管理**：支持主数据的版本迭代与状态流转
- **多租户隔离**：所有主数据按 tenantId 隔离，避免跨租户数据泄漏

本模块是 L3.6 主数据管理层的核心服务，为数据集成、数据质量、数据服务提供统一的主数据源。

## 技术栈

- Java 17
- Spring Boot 3.2.x
- Spring Data JPA + H2（开发）/ PostgreSQL（生产）
- Lombok
- Maven

## 构建方式

```bash
cd platform/master-data
mvn -B -DskipTests clean package
```

构建产物：`target/master-data-0.1.0.jar`

## 运行方式

```bash
java -jar target/master-data-0.1.0.jar
```

默认监听端口 **8088**。

## API 端点列表

### 主数据模型管理

| 方法   | 路径                              | 说明                 |
|--------|-----------------------------------|----------------------|
| POST   | /api/v1/master-data/models        | 创建主数据模型（201）|
| GET    | /api/v1/master-data/models        | 列出所有主数据模型   |

### 主数据记录管理

| 方法   | 路径                              | 说明                   |
|--------|-----------------------------------|------------------------|
| POST   | /api/v1/master-data               | 创建主数据记录（201）  |
| GET    | /api/v1/master-data?modelCode=xxx | 按模型查询主数据       |
| PUT    | /api/v1/master-data/{id}          | 更新主数据记录         |
| DELETE | /api/v1/master-data/{id}          | 删除主数据记录         |

Actuator 端点：`/actuator/health`、`/actuator/info`、`/actuator/metrics`、`/actuator/prometheus`

## 主数据模型定义示例

```json
{
  "code": "md-organization",
  "name": "组织主数据",
  "description": "企业组织架构主数据",
  "fieldsSchema": "[{\"name\":\"orgCode\",\"type\":\"string\",\"required\":true},{\"name\":\"orgName\",\"type\":\"string\",\"required\":true}]"
}
```

## 主数据记录示例

```json
{
  "modelCode": "md-organization",
  "dataKey": "ORG-001",
  "dataValue": "总部",
  "attributes": "{\"orgCode\":\"ORG-001\",\"orgName\":\"总部\",\"level\":1}",
  "version": "1.0.0",
  "status": "ACTIVE",
  "tenantId": "default"
}
```

## 多租户隔离

所有主数据模型与记录均携带 `tenantId` 字段，按租户隔离查询 / 更新 / 删除，避免跨租户数据泄漏。

## 路线规划

1. MVP（当前）：模型定义 + 记录 CRUD + JPA 持久化
2. v0.2：主数据审批流 + 变更审计 + 版本对比
3. v0.3：主数据分发（推下游系统）+ 映射关系管理
4. v1.0：主数据质量校验 + 跨域主数据关联 + 主数据市场