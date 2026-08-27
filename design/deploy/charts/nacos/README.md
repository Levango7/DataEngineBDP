# Nacos 配置中心 Helm Chart

> 引入 Nacos 集中化管理多环境配置，支持动态推送与灰度发布。
> Apache 顶级项目，Spring Cloud Alibaba（SCA）生态入口。

## 一、Chart 说明

本 Chart 安装 Nacos 配置中心，支持 standalone（单节点，Derby 内嵌 DB）与 cluster（集群，MySQL）两种模式。

表：Nacos 组件端口说明

| 组件 | 说明 | 端口 |
| --- | --- | --- |
| HTTP API / 控制台 | Nacos 配置读取、管理 UI、健康检查 | 8848 |
| gRPC 客户端 | Nacos 2.x 客户端通信 | 9848 |
| Raft | 集群内 Raft 选举通信 | 9849 |

## 二、快速开始

命令示例：开发环境安装（standalone 模式，Derby 内嵌 DB，零外部依赖）

```bash
helm install nacos design/deploy/charts/nacos \
  --namespace nacos --create-namespace \
  --set auth.tokenSecretKey=$(openssl rand -base64 32)
```

命令示例：生产环境安装（cluster 模式，外部 MySQL）

```bash
helm install nacos design/deploy/charts/nacos \
  --namespace nacos --create-namespace \
  --set mode=cluster \
  --set replicaCount=3 \
  --set mysql.host=mysql.database.svc.cluster.local \
  --set mysql.username=nacos \
  --set mysql.password=your-password \
  --set auth.tokenSecretKey=$(openssl rand -base64 32)
```

## 三、访问控制台

命令示例：端口转发访问 Nacos 控制台

```bash
kubectl port-forward -n nacos svc/nacos 8848:8848
# 访问 http://localhost:8848/nacos
# 默认账号：nacos / nacos（生产环境必须修改）
```

## 四、关键配置

表：Nacos 关键配置项说明

| 配置项 | 说明 | 默认值 | 必填 |
| --- | --- | --- | --- |
| `mode` | 运行模式：standalone / cluster | standalone | 否 |
| `replicaCount` | 副本数（cluster 建议 ≥3） | 1 | 否 |
| `auth.enabled` | 开启鉴权 | true | 否 |
| `auth.tokenSecretKey` | Token 加密密钥（≥32 字符 Base64） | "" | **是**（auth.enabled 时） |
| `mysql.host` | MySQL 主机（仅 cluster） | "" | cluster 时必填 |
| `resources` | 资源配额 | 0.2C/512Mi ~ 1C/1Gi | 否 |
| `jvm.xms`/`jvm.xmx` | JVM 堆内存 | 512m / 512m | 否 |

> **Fail-fast 机制**：`auth.enabled=true` 且 `auth.tokenSecretKey` 为空时，chart 渲染将失败，强制生产环境显式注入密钥。

## 五、核心服务接入 Nacos

### 5.1 Spring Boot 接入

在核心服务的 `application.yml` 中添加 Nacos 配置：

代码示例：Spring Boot application.yml 接入 Nacos

```yaml
spring:
  cloud:
    nacos:
      config:
        server-addr: nacos.nacos.svc.cluster.local:8848
        namespace: dev
        group: DEFAULT_GROUP
        file-extension: yaml
      discovery:
        server-addr: nacos.nacos.svc.cluster.local:8848
        namespace: dev
```

### 5.2 Maven 依赖

代码示例：Spring Cloud Alibaba Nacos 客户端依赖

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
    <version>2023.0.1.0</version>
</dependency>
```

### 5.3 启动参数

命令示例：Java 服务启动注入 Nacos 地址

```bash
java -jar app.jar \
  --spring.cloud.nacos.config.server-addr=nacos.nacos.svc.cluster.local:8848 \
  --spring.cloud.nacos.config.namespace=pro \
  --spring.cloud.nacos.config.group=DEFAULT_GROUP
```

### 5.4 多语言 ENV 注入（Go/Python）

Go/Python 服务无需 Nacos 客户端，通过 Helm values → Deployment env 注入配置，与现有 K8s 原生模式零冲突。

## 六、与 Apollo 切换说明

表：Apollo → Nacos 切换对照

| 维度 | Apollo（已归档） | Nacos（本 Chart） |
| --- | --- | --- |
| 项目归属 | 携程系 | Apache 顶级项目 + SCA 生态入口 |
| 部署形态 | Portal+Config+Admin 三件套 + MySQL | standalone(Derby) / cluster(MySQL) |
| 资源基线 | ≈0.6C/1.5Gi 起 | 0.2C/512Mi ~ 1C/1Gi |
| 镜像 | apolloconfig/apollo-* 2.3.0 | nacos/nacos-server v2.4.3 |
| 工作负载 | Deployment ×3 | StatefulSet（稳定网络标识） |
| 鉴权 | admin/admin 弱凭据 | tokenSecretKey fail-fast 强制注入 |
| 多语言 | 客户端生态一般 | ENV 注入零绑定 + SDK 生态较好 |
| 兼容性 | Boot 2.x 为主 | Boot 3.2 / Java 17 / K8s / 多语言 ENV 全绿 |

切换理由（主流优先原则）：

1. Nacos 作为 SCA 生态入口 + Apache 顶级项目，社区活跃度与主流度更高；
2. 兼容性全绿（Spring Boot 3.2 / Java 17 / K8s 原生 / 多语言 ENV 注入均兼容）；
3. 切换成本约 1 人日，已执行（本 chart + 引用修订）。

## 七、依赖

- Kubernetes >= 1.23
- Nacos >= 2.4.3
- MySQL >= 8.0（仅 cluster 模式）