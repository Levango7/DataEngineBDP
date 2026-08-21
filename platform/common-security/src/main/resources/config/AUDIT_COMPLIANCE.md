# 审计合规增强（v2.1 生产化加固）

> 操作审计日志全链路覆盖，满足等保三级与金融行业审计要求。

## 一、功能说明

| 能力 | 说明 |
| --- | --- |
| 全链路审计 | HTTP 请求/响应 + 方法级审计，覆盖所有重要操作 |
| 敏感字段脱敏 | 密码、Token、身份证、银行卡等自动脱敏 |
| 防篡改 | HMAC-SHA256 签名保证审计日志完整性 |
| 异步写入 | 高并发下不阻塞业务线程 |
| 合规保留 | 等保三级 180 天，金融行业 7 年（2555 天） |
| 等保三级合规 | 满足 GB/T 22239-2019 8.1.4.3 安全审计要求 |
| 金融行业审计 | 满足 JR/T 0071-2012 金融行业网络安全等级保护实施指引 |

## 二、审计事件字段

审计事件包含以下字段（满足等保三级 8.1.4.3 b) 要求）：

| 字段 | 说明 | 等保三级条款 |
| --- | --- | --- |
| timestamp | 事件时间戳 | 8.1.4.3 b) 日期、时间 |
| userId | 操作用户 ID | 8.1.4.3 b) 用户 |
| tenantId | 租户 ID | 多租户隔离 |
| actionType | 操作类型 | 8.1.4.3 b) 事件类型 |
| action | 操作动作 | 8.1.4.3 b) 事件类型 |
| resource | 操作资源 | 8.1.4.3 b) 事件内容 |
| sourceIp | 来源 IP | 8.1.4.3 b) 事件来源 |
| requestMethod | HTTP 方法 | 8.1.4.3 b) 事件内容 |
| requestPath | 请求路径 | 8.1.4.3 b) 事件内容 |
| requestParams | 请求参数（脱敏后） | 8.1.4.3 b) 事件内容 |
| responseStatus | 响应状态码 | 8.1.4.3 b) 事件是否成功 |
| responseTimeMs | 响应耗时 | 性能审计 |
| result | 操作结果 | 8.1.4.3 b) 事件是否成功 |
| traceId | 链路追踪 ID | 全链路关联 |
| level | 审计级别 | 分级审计 |
| category | 审计分类 | 分类审计 |

## 三、使用方式

### 3.1 HTTP 请求审计（自动）

`AuditLogFilter` 自动拦截所有 HTTP 请求，无需额外配置。

### 3.2 方法级审计（注解）

使用 `@Auditable` 注解标注需要审计的方法：

```java
@Auditable(
    actionType = ActionType.DELETE,
    action = "删除集群",
    resource = "cluster",
    category = Category.SYSTEM_ADMIN,
    level = Level.CRITICAL
)
public void deleteCluster(String clusterId) {
    // 业务逻辑
}
```

### 3.3 手动审计

```java
@Autowired
private AuditLogService auditLogService;

public void someBusinessMethod() {
    // 业务逻辑
    auditLogService.audit(AuditLogService.builder()
        .actionType(ActionType.UPDATE)
        .action("更新配置")
        .resource("system-config")
        .result(Result.SUCCESS)
        .category(Category.CONFIGURATION)
        .level(Level.IMPORTANT)
        .build());
}
```

## 四、配置

```yaml
app:
  audit:
    enabled: true
    storage: file
    log-file-path: /var/log/dataenginebdp/audit
    log-file-max-size-mb: 100
    log-file-max-history: 90
    async-write: true
    async-queue-size: 10000
    log-request-params: true
    request-params-max-length: 2000
    sensitive-fields:
      - password
      - token
      - idCard
      - bankAccount
    exclude-paths:
      - /actuator/health
      - /api/v1/health
    dengbao-level3-enabled: true
    finance-audit-enabled: true
    retention-days: 2555
    tamper-proof: true
    hmac-secret: ${AUDIT_HMAC_SECRET}
```

## 五、等保三级合规对应

| 等保三级条款 | 实现方式 |
| --- | --- |
| 8.1.4.3 a) 审计覆盖到每个用户 | AuditLogFilter 拦截所有 HTTP 请求 |
| 8.1.4.3 b) 审计记录包含日期、时间、用户、事件类型、事件是否成功 | AuditEvent 包含所有必需字段 |
| 8.1.4.3 c) 审计记录保护，防止未预期的删除、修改或覆盖 | HMAC 签名 + 日志文件权限控制 + 滚动归档 |
| 8.1.4.3 d) 审计进程保护，免受未预期的中断 | 异步写入 + 队列缓冲 + 守护线程 |

## 六、金融行业审计对应

| 金融行业要求 | 实现方式 |
| --- | --- |
| 操作审计日志应覆盖所有重要业务操作 | @Auditable 注解 + AuditLogFilter |
| 审计日志应保留至少 7 年 | logback 配置 maxHistory=2555 |
| 审计日志应防篡改 | HMAC-SHA256 签名 |
| 敏感数据应脱敏存储 | 敏感字段自动脱敏 |
| 重要操作应记录操作人、操作时间、操作内容、操作结果 | AuditEvent 完整字段 |

## 七、依赖

- Spring Boot >= 3.2
- Spring AOP（方法级审计切面）
- Jackson（审计事件 JSON 序列化）