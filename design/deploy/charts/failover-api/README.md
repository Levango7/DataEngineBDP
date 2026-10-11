# failover-api Chart

故障转移 API - 多集群故障检测与迁移决策面 - 数据引擎大数据平台

- **台账背景**：#2 部署单元缺口之一；模块原名含 basename 撞名问题，2026-10-11 按台账 #64 方案 A 改名后补 chart。
- **端口**：8094；探针 `/api/v1/health`。
- **敏感项**（`valueFrom.secretKeyRef`，留空则不注入）：JWT_SECRET
- **只读根**：/tmp emptyDir 默认开；
- **ServiceMonitor**：默认关（未确认暴露 /metrics 前不启用）。
- **注意**：镜像 uid 非固定（`useradd --system`）⇒ chart 不设 runAsUser，只留 runAsNonRoot + fsGroup。

```bash
helm template failover-api design/deploy/charts/failover-api
```
