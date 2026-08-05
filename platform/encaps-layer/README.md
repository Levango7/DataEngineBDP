# encaps-layer

数擎大数据平台（ShuqingBigDataPlatform）**封装层（Encaps Layer）**。

## 项目用途

封装层是平台的中层服务，承担两类职责：

- **向上屏蔽底层差异**：对前端 / 编排层暴露稳定的 REST API，使其无需感知底层 Kubernetes、大数据组件（HDFS/YARN/Kyuubi 等）的接入细节。
- **向下统一调度原语**：将上层语义（如"创建租户""调整配额"）翻译为底层资源供应原语调用。

本仓库当前为 MVP 阶段骨架，租户数据使用内存存储（`ConcurrentHashMap`），后续将替换为持久化仓储。

## 技术栈

- Java 17
- Spring Boot 3.2.5
- Spring Web / Actuator / Validation
- Lombok（编译期）
- Maven 构建

## 目录结构

```
encaps-layer/
├── pom.xml
├── Dockerfile
├── README.md
├── .gitignore
└── src/main/
    ├── java/com/shuqing/bigdata/encaps/
    │   ├── EncapsLayerApplication.java
    │   ├── controller/
    │   │   ├── HealthController.java
    │   │   └── TenantController.java
    │   ├── model/
    │   │   └── Tenant.java
    │   └── service/
    │       └── TenantService.java
    └── resources/
        └── application.yml
```

## 构建方式

```bash
mvn clean package -DskipTests
```

产物：`target/encaps-layer-0.1.0-SNAPSHOT.jar`

## 运行方式

### 本地 Java 启动

```bash
java -jar target/encaps-layer-0.1.0-SNAPSHOT.jar
```

默认端口 `8080`。

### Docker 启动

```bash
docker build -t shuqing/encaps-layer:0.1.0 .
docker run --rm -p 8080:8080 shuqing/encaps-layer:0.1.0
```

## API 端点列表

| 方法 | 路径 | 说明 | 成功状态码 |
|------|------|------|-----------|
| GET  | `/api/v1/health` | 封装层健康检查 | 200 |
| POST | `/api/v1/tenants` | 创建租户 | 201 |
| GET  | `/api/v1/tenants` | 列出全部租户 | 200 |
| GET  | `/api/v1/tenants/{id}` | 获取单个租户 | 200 / 404 |
| PUT  | `/api/v1/tenants/{id}` | 更新租户 | 200 / 404 |
| DELETE | `/api/v1/tenants/{id}` | 删除租户 | 204 / 404 |

Spring Boot Actuator 同时暴露：`/actuator/health`、`/actuator/info`、`/actuator/metrics`。

### 创建租户示例

```bash
curl -X POST http://localhost:8080/api/v1/tenants \
  -H 'Content-Type: application/json' \
  -d '{"name":"tenant-a","displayName":"租户A","namespace":"ns-tenant-a","quotaProfile":"medium"}'
```

## 与平台其他组件的关系

```
┌──────────────┐    ┌──────────────────┐    ┌────────────────────┐
│  frontend    │ -> │  encaps-layer    │ -> │  K8s / 大数据组件   │
│  (Vue 3)     │    │  (本仓库)         │    │  (HDFS/YARN/...)   │
└──────────────┘    └──────────────────┘    └────────────────────┘
                          ^
                          │ 调用
                  ┌───────┴────────┐
                  │  orchestration │  (编排层，后续阶段)
                  └────────────────┘
```

- **上游**：被前端 / 编排层调用，是平台对外的稳定 API 边界。
- **下游**：未来对接 K8s API Server 与大数据组件的 Operator / REST 接口。
- **同层**：与后续将建设的编排层（orchestration-layer）协同，由编排层负责跨封装层实例的流程编排。