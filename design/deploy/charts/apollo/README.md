# Apollo 配置中心 Helm Chart（v2.1 生产化加固）

> 引入 Apollo 集中化管理多环境配置，支持热更新与回滚。

## 一、Chart 说明

本 Chart 安装 Apollo 配置中心（Portal + ConfigService + AdminService），并提供核心服务接入示例。

| 组件 | 说明 | 端口 |
| --- | --- | --- |
| Portal | 管理界面 Web UI，配置管理、发布、回滚、灰度发布 | 8070 |
| ConfigService | 配置服务，提供配置读取与热更新通知 | 8080 |
| AdminService | 管理服务，提供配置管理 REST API | 8090 |
| MySQL | 配置数据存储（内置或外部） | 3306 |

## 二、安装

```bash
# 开发环境（内置 MySQL）
helm install apollo design/deploy/charts/apollo \
  --namespace apollo --create-namespace

# 生产环境（外部 MySQL）
helm install apollo design/deploy/charts/apollo \
  --namespace apollo --create-namespace \
  --set db.internal.enabled=false \
  --set db.external.enabled=true \
  --set db.external.host=mysql.database.svc.cluster.local \
  --set db.external.username=apollo \
  --set db.external.password=your-password
```

## 三、访问 Portal

```bash
kubectl port-forward -n apollo svc/apollo-portal 8070:8070
# 访问 http://localhost:8070
# 默认账号：apollo / admin（生产环境必须修改）
```

## 四、核心服务接入 Apollo

### 4.1 Spring Boot 接入

在核心服务的 `application.yml` 中添加 Apollo 配置：

```yaml
apollo:
  meta: http://apollo-configservice.apollo.svc.cluster.local:8080
  bootstrap:
    enabled: true
    eagerLoad:
      enabled: true
  cacheDir: /opt/data/apollo-cache
  autoUpdateInjectedSpringProperties: true
```

### 4.2 Maven 依赖

```xml
<dependency>
    <groupId>com.ctrip.framework.apollo</groupId>
    <artifactId>apollo-client</artifactId>
    <version>2.3.0</version>
</dependency>
```

### 4.3 启动参数

```bash
java -jar app.jar \
  -Dapp.id=sq-encaps-layer \
  -Denv=PRO \
  -Dapollo.meta=http://apollo-configservice.apollo.svc.cluster.local:8080 \
  -Dapollo.cluster=default
```

## 五、配置热更新

Apollo 支持配置热更新，无需重启应用：

```java
@RefreshScope
@RestController
public class ConfigController {
    @Value("${datasource.url}")
    private String datasourceUrl;

    @GetMapping("/config/datasource")
    public String getDatasourceUrl() {
        return datasourceUrl;  // 配置变更后自动更新
    }
}
```

## 六、配置回滚

通过 Portal 界面或 REST API 进行配置回滚：

```bash
# 回滚到上一版本
curl -X POST "http://apollo-portal:8070/apps/sq-encaps-layer/envs/pro/clusters/default/namespaces/application/releases/previous"
```

## 七、多环境配置

Chart 预置 4 个环境：dev / fat / uat / pro，可在 Portal 中切换环境管理配置。

| 环境 | 说明 |
| --- | --- |
| dev | 开发环境 |
| fat | 功能测试环境（Feature Acceptance Test） |
| uat | 用户验收环境（User Acceptance Test） |
| pro | 生产环境 |

## 八、核心服务接入示例

Chart 为以下核心服务预置 Apollo 接入 ConfigMap：

| 服务 | AppId | 命名空间 |
| --- | --- | --- |
| sq-encaps-layer | sq-encaps-layer | application, datasource, redis |
| sq-sql-gateway | sq-sql-gateway | application, datasource, trino, doris |
| sq-rule-engine | sq-rule-engine | application, datasource |

## 九、依赖

- Kubernetes >= 1.23
- MySQL >= 8.0（内置或外部）
- Apollo >= 2.3.0