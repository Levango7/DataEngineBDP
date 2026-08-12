# 告警通知渠道

> 版本：v0.1 ｜ 日期：2026-08-07
> 依赖：ArgoCD Notifications 控制器（v2.5+ 默认安装）

---

## 1. 告警渠道

| 渠道 | 用途 | 接收人 | 适用严重程度 |
| --- | --- | --- | --- |
| Slack | 实时通知 + @ 人 | `#prod-alerts` / `#drift-warnings` / `#drift-info` | 全部 |
| 邮件 | 留痕 + 归档 | `sre-team@company.com` / `dev-team@company.com` | High / Critical |
| 钉钉 | 国内团队通知 | SRE 群机器人 | High / Critical |
| Webhook | 对接告警平台 | Alertmanager / 自建系统 | 全部 |
| PagerDuty | 电话呼叫 | SRE on-call | Critical |

## 2. 通知触发条件

| 触发器 | 条件 | 默认订阅 |
| --- | --- | --- |
| `on-drift-detected` | 检测到任意漂移 | 全环境 |
| `on-drift-critical` | 检测到 Critical 漂移（镜像漂移） | prod |
| `on-sync-failed` | ArgoCD sync 失败 | 全环境 |
| `on-sync-succeeded` | ArgoCD sync 成功（漂移修复完成） | prod |
| `on-health-degraded` | App health 变为 Degraded | 全环境 |
| `on-remediation-failed` | 自动修复失败 | 全环境 |
| `on-circuit-breaker` | 触发熔断 | prod |

## 3. Slack 频道规划

```text
#prod-alerts        ← Critical / High（prod 环境）
                        @sre-on-call 全员通知
#drift-warnings     ← Medium（全环境）
                        仅通知，不 @ 人
#drift-info         ← Low（全环境）
                        仅记录，不 @ 人
#prod-deploy        ← sync 成功通知（prod）
                        部署留痕
```

## 4. 通知模板

### 4.1 漂移检测通知

```text
🚨 配置漂移检测
─────────────────────────
App:        root-prod
环境:       prod
严重程度:   CRITICAL
漂移类型:   image-drift
资源:       prod-platform/prod-data-engineer/Deployment/data-engineer
差异:
  - image: registry.company.com/data-engineer:v1.2.0
  + image: registry.company.com/data-engineer:latest
检测时间:   2026-08-07 09:30:00 UTC
修复策略:   需人工确认
─────────────────────────
查看详情: https://argocd.company.com/applications/root-prod
```

### 4.2 修复完成通知

```text
✅ 漂移修复完成
─────────────────────────
App:        root-dev
环境:       dev
修复方式:   auto-heal
耗时:       7s
原漂移:     config-drift (medium)
─────────────────────────
```

### 4.3 修复失败通知

```text
❌ 漂移修复失败
─────────────────────────
App:        root-prod
环境:       prod
失败原因:   sync timeout (300s)
当前状态:   OutOfSync / Degraded
建议操作:   人工介入，检查 Git 配置
─────────────────────────
@sre-on-call 请立即处理
```

## 5. 通知抑制（避免告警风暴）

| 抑制规则 | 配置 | 说明 |
| --- | --- | --- |
| 同 App 同类型 | 5 分钟内仅通知 1 次 | 避免重复告警 |
| 修复中 | 修复期间不重复告警 | 避免修复过程告警 |
| 维护窗口 | 标记维护期 App 不告警 | 计划内变更 |
| 熔断后 | 熔断状态仅告警 1 次 | 避免熔断告警风暴 |

## 6. 告警路由矩阵

```text
漂移事件
   │
   ├─ severity=critical
   │   ├─ env=prod   → Slack #prod-alerts + 钉钉 + 邮件 SRE + PagerDuty
   │   ├─ env=staging → Slack #prod-alerts + 邮件 SRE
   │   └─ env=dev    → Slack #drift-warnings
   │
   ├─ severity=high
   │   ├─ env=prod   → Slack #prod-alerts + 钉钉 + 邮件 SRE
   │   ├─ env=staging → Slack #drift-warnings + 邮件 dev
   │   └─ env=dev    → Slack #drift-warnings
   │
   ├─ severity=medium
   │   └─ 全环境 → Slack #drift-warnings
   │
   └─ severity=low
       └─ 全环境 → Slack #drift-info
```