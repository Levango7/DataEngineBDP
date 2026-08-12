# 合规审计策略

> 版本：v0.1 ｜ 日期：2026-08-07
> 依赖：漂移检测模块 + Kubernetes Audit Log

---

## 1. 审计目标

对 GitOps 漂移事件进行全量审计，满足以下合规要求：

1. **可追溯**：谁在何时改了什么（变更来源追溯）
2. **可归档**：审计日志保留 90 天，不可篡改
3. **可报告**：定期生成合规报告，供安全 / 合规团队审查
4. **可校验**：通过 OPA 规则校验变更合规性

## 2. 审计数据源

| 数据源 | 内容 | 采集方式 |
| --- | --- | --- |
| Kubernetes Audit Log | 所有 API 写操作（who/when/what） | kube-apiserver audit log |
| ArgoCD Operation Log | ArgoCD sync 操作记录 | ArgoCD Application operationState |
| 漂移事件 ConfigMap | 漂移检测事件 | drift-event-* ConfigMap |
| 修复事件 ConfigMap | 修复操作记录 | remediation-event-* ConfigMap |
| Git Commit History | 配置变更提交 | Git log + webhook |

## 3. 审计策略

### 3.1 Kubernetes Audit Policy

记录所有写操作，重点关注：

- `apply` / `patch` / `create` / `update` / `delete` 请求
- 请求来源（user / serviceAccount）
- 请求体（完整 manifest）
- 响应状态

### 3.2 合规规则（OPA/Rego）

```text
合规校验规则
   │
   ├─ rule-1: 仅允许 ArgoCD ServiceAccount 执行部署变更
   │          （其他 SA 的写操作视为违规）
   │
   ├─ rule-2: prod 环境变更必须来自 Git main 分支
   │          （直接 kubectl apply 视为违规）
   │
   ├─ rule-3: 镜像必须来自受信 registry
   │          （非 registry.company.com 视为违规）
   │
   ├─ rule-4: Secret 不允许明文存储
   │          （必须通过 SealedSecret / ExternalSecret）
   │
   └─ rule-5: 资源必须包含合规标签
              （owner / environment / compliance）
```

### 3.3 审计日志格式

```json
{
  "audit-id": "drift-2026-08-07-093000-root-prod",
  "timestamp": "2026-08-07T09:30:00Z",
  "event-type": "drift-detected",
  "app": "root-prod",
  "environment": "prod",
  "severity": "high",
  "drift-type": "config-drift",
  "resource": "prod-platform/prod-data-engineer/ConfigMap/engine-config",
  "diff-summary": "...",
  "source": {
    "user": "kube:admin",
    "operation": "kubectl-patch",
    "source-ip": "10.0.0.5",
    "timestamp": "2026-08-07T09:25:00Z"
  },
  "remediation": {
    "action": "auto-heal",
    "status": "success",
    "duration": "7s",
    "operator": "drift-remediation-cronjob"
  },
  "compliance": {
    "rule-violated": "rule-2",
    "compliant": false,
    "note": "直接 kubectl patch，非 ArgoCD 来源"
  }
}
```

## 4. 审计导出

### 4.1 实时导出

漂移事件 / 修复事件实时写入 ConfigMap，同时通过 webhook 推送到 SIEM（如 ELK / Splunk）。

### 4.2 定期导出

CronJob 每日凌晨导出前一日审计日志到对象存储（MinIO/S3）：

```text
audit-export CronJob（每日 02:00）
   │
   ▼
收集前一日所有 drift-event-* / remediation-event-* ConfigMap
   │
   ▼
转换为 JSON Lines 格式
   │
   ▼
上传到 MinIO/S3: audit/drift/2026-08-07.jsonl
   │
   ▼
保留 90 天后自动删除
```

### 4.3 合规报告

CronJob 每周一生成合规周报：

```text
drift-compliance-report CronJob（每周一 08:00）
   │
   ▼
统计上周：
  - 漂移总次数 / 按环境 / 按严重程度
  - 修复成功率 / 平均修复时长
  - 违规变更列表（非 ArgoCD 来源）
  - 熔断事件列表
   │
   ▼
生成 PDF / HTML 报告
   │
   ▼
发送到 sre-team@company.com + compliance@company.com
```

## 5. 合规报告样例

```text
┌─────────────────────────────────────────────────────────┐
│  数据引擎大数据平台 · GitOps 漂移合规周报                    │
│  周期：2026-08-01 ~ 2026-08-07                          │
├─────────────────────────────────────────────────────────┤
│  一、漂移统计                                            │
│  ─────────────────────────────────────────────────────  │
│  总漂移次数：23 次                                       │
│    prod：    5 次（Critical: 1, High: 2, Medium: 2）     │
│    staging：8 次（High: 3, Medium: 5）                  │
│    dev：    10 次（Medium: 6, Low: 4）                  │
│                                                         │
│  二、修复统计                                            │
│  ─────────────────────────────────────────────────────  │
│  修复成功率：87%（20/23）                                │
│  平均修复时长：12s                                       │
│  熔断事件：1 次（root-prod，已人工介入）                 │
│                                                         │
│  三、违规变更                                            │
│  ─────────────────────────────────────────────────────  │
│  1. 2026-08-03 14:23 root-prod ConfigMap/engine-config   │
│     来源：kube:admin（kubectl patch）                   │
│     违规规则：rule-2（非 ArgoCD 来源）                   │
│     处理：已自愈 + 已通知 SRE                            │
│                                                         │
│  四、合规建议                                            │
│  ─────────────────────────────────────────────────────  │
│  1. prod 环境建议加强 RBAC，限制直接 kubectl 权限        │
│  2. dev 环境漂移频率较高，建议开发者使用 Git 提交变更    │
└─────────────────────────────────────────────────────────┘
```

## 6. 数据保留策略

| 数据类型 | 保留期 | 存储位置 | 删除方式 |
| --- | --- | --- | --- |
| 漂移事件 ConfigMap | 7 天 | 集群内 | TTL 自动删除 |
| 修复事件 ConfigMap | 7 天 | 集群内 | TTL 自动删除 |
| 审计日志（JSONL） | 90 天 | MinIO/S3 | 生命周期策略 |
| 合规周报 | 1 年 | MinIO/S3 | 生命周期策略 |
| Kubernetes Audit Log | 30 天 | 节点本地 | logrotate |