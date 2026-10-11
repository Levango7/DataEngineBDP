# failover-engine Chart

故障转移引擎 - 集群健康检测与迁移执行（依赖 Karmada 控制面与 Prometheus）- 数据引擎大数据平台

- **台账背景**：#2 部署单元缺口之一；模块原名含 basename 撞名问题，2026-10-11 按台账 #64 方案 A 改名后补 chart。
- **端口**：8095；探针 `/healthz`。
- **敏感项**（`valueFrom.secretKeyRef`，留空则不注入）：KARMADA_API_TOKEN
- **只读根**：/tmp emptyDir 默认开；无数据卷需求（无本地 DB）。
- **数据卷**：（无 DB 文件） 文件库 ⇒ /app/data 默认挂 emptyDir。
- **ServiceMonitor**：默认关（未确认暴露 /metrics 前不启用）。
- **注意**：镜像 uid 非固定（`useradd --system`）⇒ chart 不设 runAsUser，只留 runAsNonRoot + fsGroup。

```bash
helm template failover-engine design/deploy/charts/failover-engine
```
