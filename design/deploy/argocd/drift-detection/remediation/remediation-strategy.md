# 自动修复策略

> 版本：v0.1 ｜ 日期：2026-08-07
> 依赖：漂移检测模块（detection/）已部署

---

## 1. 修复目标

对检测到的配置漂移，按环境与严重程度执行分级自动修复，使集群状态回归 Git 声明。

## 2. 修复机制

### 2.1 一级修复：ArgoCD selfHeal（实时）

```text
漂移发生
   │
   ▼
ArgoCD 检测到 OutOfSync
   │
   ▼
syncPolicy.automated.selfHeal == true ?
   ├─ 是 → ArgoCD 自动触发 sync，回滚到 Git 声明（秒级）
   └─ 否 → 仅记录，等待人工处理
```

**适用场景**：dev / staging 全量启用；prod 仅在同步窗口内启用。

### 2.2 二级修复：修复 CronJob（定时巡检）

针对 selfHeal 未覆盖的场景：

- selfHeal 被临时关闭（prod 紧急修复期）
- 漂移涉及多 App 依赖顺序
- 需要修复前校验（如检查镜像签名、配置 schema）

```text
CronJob 每 N 分钟执行
   │
   ▼
调用 detect-drift.sh 获取漂移列表
   │
   ▼
对每个漂移事件：
   ├─ 加载修复策略（remediation-policy）
   ├─ 校验前置条件（如 prod 需在同步窗口）
   ├─ 执行修复（argocd app sync <app>）
   ├─ 验证修复结果（等待 Synced）
   └─ 更新漂移事件 ConfigMap 状态
```

### 2.3 三级修复：人工确认修复

针对 prod 环境 High / Critical 漂移：

```text
检测到 Critical 漂移
   │
   ▼
触发告警（Slack + 电话）
   │
   ▼
SRE 确认修复
   │
   ▼
执行 remediate.sh --app <app> --confirm
```

## 3. 修复策略矩阵

| 环境 | 严重程度 | selfHeal | 修复 Job | 修复窗口 | 人工确认 | 修复超时 |
| --- | --- | --- | --- | --- | --- | --- |
| dev | Critical | ✅ | ✅ | 7×24 | 否 | 60s |
| dev | High | ✅ | ✅ | 7×24 | 否 | 60s |
| dev | Medium | ✅ | ✅ | 7×24 | 否 | 60s |
| dev | Low | ✅ | ❌ | 7×24 | 否 | 60s |
| staging | Critical | ✅ | ✅ | 7×24 | 否 | 120s |
| staging | High | ✅ | ✅ | 7×24 | 否 | 120s |
| staging | Medium | ✅ | ✅ | 7×24 | 否 | 120s |
| staging | Low | ✅ | ❌ | 7×24 | 否 | 120s |
| prod | Critical | ✅ | ✅ | 工作日 09-18 | **是** | 300s |
| prod | High | ✅ | ✅ | 工作日 09-18 | **是** | 300s |
| prod | Medium | ✅ | ❌ | 工作日 09-18 | 否 | 300s |
| prod | Low | ❌ | ❌ | - | 否 | - |

> prod Low 漂移（标签/注解）不自动修复，避免非关键变更引发 prod 同步。

## 4. 修复前置校验

修复前必须通过以下校验：

1. **同步窗口校验**（prod）：当前时间在 `syncWindows` 允许范围内
2. **镜像签名校验**（prod）：待修复镜像已签名（cosign verify）
3. **配置 schema 校验**：待 apply 的 manifest 符合 CRD schema
4. **依赖顺序校验**：被依赖资源先于依赖资源修复
5. **限流校验**：同一 App 5 分钟内修复次数 ≤ 3（避免修复风暴）
6. **熔断校验**：连续修复失败 ≥ 3 次则暂停自动修复，触发告警

## 5. 修复后验证

```text
执行 argocd app sync <app>
   │
   ▼
等待 sync 完成（超时 = 修复超时）
   │
   ▼
检查 sync status == Synced ?
   ├─ 是 → 修复成功，更新事件状态 = remediated
   └─ 否 → 修复失败，更新事件状态 = failed，触发告警
   │
   ▼
再次执行 detect-drift.sh --app <app>
   │
   ▼
漂移已消除 ?
   ├─ 是 → 修复确认
   └─ 否 → 修复未生效，升级告警
```

## 6. 修复风暴防护

为避免修复循环（修复后又漂移，漂移后又修复），设置：

| 防护机制 | 配置 | 说明 |
| --- | --- | --- |
| 修复频率限制 | 同一 App 5 分钟内 ≤ 3 次 | 超过则暂停修复，触发告警 |
| 修复失败熔断 | 连续失败 ≥ 3 次 | 暂停该 App 自动修复 30 分钟 |
| 全局修复并发 | 同时修复 App 数 ≤ 5 | 避免集群负载突增 |
| 修复窗口冷却 | 修复后 60s 内不再修复同一 App | 等待集群稳定 |

## 7. 修复事件记录

每次修复操作记录以下信息，写入审计日志：

```yaml
remediation-event:
  app: root-prod
  environment: prod
  drift-event: drift-event-root-prod-20260807-093000
  remediation-action: "argocd app sync root-prod"
  remediation-trigger: "auto-heal"  # auto-heal / cronjob / manual
  started-at: "2026-08-07T09:30:05Z"
  completed-at: "2026-08-07T09:30:12Z"
  duration: 7s
  result: success  # success / failed / timeout
  verified: true   # 修复后再次检测确认
  operator: "drift-remediation-cronjob"  # 或人工 SRE 名
```