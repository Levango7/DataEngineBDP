# 告警规则模板库

## 概述

本目录提供 P0/P1/P2 三级告警规则模板，供平台方与租户复用。
租户可基于模板自定义告警阈值，无需从零编写 PromQL。

## 文件结构

```
rules/
├── p0-rules.yaml  — P0 严重告警规则模板（电话/短信）
├── p1-rules.yaml  — P1 重要告警规则模板（邮件/IM）
├── p2-rules.yaml  — P2 一般告警规则模板（钉钉/飞书）
└── README.md      — 本文件
```

## 告警分级

| 级别 | 严重程度 | 通知渠道           | 时效要求              | 典型场景                     |
|------|----------|--------------------|-----------------------|------------------------------|
| P0   | 严重     | 电话 + 短信        | 7x24 立即，5 分钟重复 | 节点宕机、数据库不可达       |
| P1   | 重要     | 邮件 + 企业微信 IM | 30 秒聚合，30 分钟重复 | 作业失败率、查询延迟、存储水位 |
| P2   | 一般     | 钉钉 + 飞书        | 1 分钟聚合，4 小时重复 | 慢查询、资源提醒、配置变更   |

## 模板变量

模板中使用 `{{变量名}}` 占位符，租户自定义时替换为实际值。

| 变量                    | 默认值 | 说明               | 出现于   |
|-------------------------|--------|--------------------|----------|
| `{{tenant_id}}`         | —      | 租户 ID            | P0/P1/P2 |
| `{{fail_threshold}}`    | 0.1    | 作业失败率阈值     | P1       |
| `{{latency_p95}}`       | 5      | 查询延迟 P95 阈值  | P1       |
| `{{disk_threshold}}`    | 0.8    | 磁盘使用率阈值     | P1       |
| `{{slow_query_threshold}}` | 30   | 慢查询阈值（秒）   | P2       |
| `{{retry_threshold}}`   | 3      | 重试次数阈值       | P2       |

## 使用方式

### 平台方使用

平台方直接加载本目录所有规则文件，`tenant_id` 留空或使用 `platform`：

```yaml
# prometheus.yml
rule_files:
  - /etc/prometheus/rules/p0-rules.yaml
  - /etc/prometheus/rules/p1-rules.yaml
  - /etc/prometheus/rules/p2-rules.yaml
```

### 租户自定义

租户基于模板自定义阈值的流程：

1. **复制模板**：将 `p1-rules.yaml` 复制为 `tenant-xxx-p1-rules.yaml`。
2. **替换变量**：将 `{{tenant_id}}` 替换为实际租户 ID，调整阈值。
3. **加载规则**：将自定义规则文件放入 Prometheus `rule_files` 目录。

示例（租户 `acme-corp` 自定义 P1 作业失败率阈值为 5%）：

```yaml
# tenant-acme-corp-p1-rules.yaml
groups:
  - name: p1-important-tenant-acme-corp
    interval: 30s
    rules:
      - alert: TenantJobFailureRateHigh
        expr: |
          (
            sum(rate(shuqing_job_failed_total{tenant_id="acme-corp"}[15m]))
            /
            sum(rate(shuqing_job_total{tenant_id="acme-corp"}[15m]))
          ) > 0.05
        for: 10m
        labels:
          severity: P1
          component: job
          category: tenant
          tenant_id: "acme-corp"
        annotations:
          summary: "租户 acme-corp 作业失败率过高"
          description: "租户 acme-corp 作业失败率超过 5%，持续 10 分钟。"
          value: "{{ $value | humanizePercentage }}"
          runbook_url: "https://wiki.shuqing.bigdata/runbook/tenant-job-fail"
```

## 规则清单

### P0 严重告警（p0-rules.yaml）

| 规则名                   | 触发条件                  | 持续时间 | 适用范围 |
|--------------------------|---------------------------|----------|----------|
| `NodeDown`               | 节点 up==0                | 1m       | 平台     |
| `DatabaseUnreachable`    | 数据库 up==0              | 30s      | 平台     |
| `KubeAPIDown`            | API Server up==0          | 30s      | 平台     |
| `PrometheusScrapeFailure`| 采集失败率 > 10%          | 5m       | 平台     |
| `DiskSpaceExhausted`     | 磁盘使用率 > 95%          | 1m       | 平台     |
| `TenantAllJobsFailed`    | 租户作业全部失败          | 5m       | 租户     |

### P1 重要告警（p1-rules.yaml）

| 规则名                       | 触发条件                  | 持续时间 | 适用范围 |
|------------------------------|---------------------------|----------|----------|
| `HighCPUUsage`               | CPU > 80%                 | 10m      | 平台     |
| `HighMemoryUsage`            | 内存 > 85%                | 10m      | 平台     |
| `PrometheusRuleEvaluationFailures` | 规则评估失败        | 5m       | 平台     |
| `PodRestartTooFrequent`      | Pod 1h 重启 > 5           | 5m       | 平台     |
| `TenantJobFailureRateHigh`   | 作业失败率 > 阈值         | 10m      | 租户     |
| `TenantQueryLatencyHigh`     | 查询 P95 > 阈值           | 10m      | 租户     |
| `TenantStorageHigh`          | 存储水位 > 阈值           | 30m      | 租户     |

### P2 一般告警（p2-rules.yaml）

| 规则名                  | 触发条件                  | 持续时间 | 适用范围 |
|-------------------------|---------------------------|----------|----------|
| `HighGCTime`            | GC 耗时 > 10%             | 15m      | 平台     |
| `HTTP5xxRate`           | 5xx 错误率 > 1%           | 15m      | 平台     |
| `GrafanaConfigChanged`  | Grafana 配置重载          | 0m       | 平台     |
| `TenantSlowQuery`       | 查询 P99 > 阈值           | 15m      | 租户     |
| `TenantJobRetry`        | 作业重试 > 阈值           | 5m       | 租户     |
| `TenantQuotaUsage`      | API 配额 > 80%            | 30m      | 租户     |

## 与 Alertmanager 路由的关系

Prometheus 评估本目录规则产生告警，告警携带 `severity` label（P0/P1/P2），
Alertmanager 按 `severity` label 路由到对应通知渠道：

```
Prometheus (本规则) → Alertmanager (alertmanager.yml route) → 通知渠道
  severity=P0  → p0-pager        → 电话 + 短信
  severity=P1  → p1-email-im     → 邮件 + 企业微信
  severity=P2  → p2-dingtalk-feishu → 钉钉 + 飞书
```

详见 `../alertmanager/alertmanager.yml`。