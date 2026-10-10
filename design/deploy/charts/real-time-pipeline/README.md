# real-time-pipeline Chart

实时治理管道（`platform/governance/real-time-pipeline`）的部署单元。

- **台账背景**：#2 部署单元缺口清单里"最要紧"的一项 —— 治理闭环阶段 1/2 的两条跨进程边
  （`MetadataWriterService`、`LineageIngestClient`）都在本进程执行，而此前集群里**没有承载者**，
  "闭环已接通"只在 compose 与单元/契约层面成立。本 chart 补齐部署单元。
- **端口**：18090（容器与 Service 同名端口）；探针 `/api/v1/health`（应用自带 Controller）；
  指标 `/actuator/prometheus`（actuator 暴露 prometheus）。
- **敏感项**：`JWT_SECRET` 走 `auth.jwtSecretName` 指向的已有 Secret（`valueFrom.secretKeyRef`），
  **不写入 ConfigMap 或 Pod spec 字面量**（`scripts/check-chart-secret-hygiene.py` 门禁保证）。
  留空则不注入（内网/服务网格承担鉴权时可用）。
- **只读根配套**：`securityContext.readOnlyRootFilesystem: true` + `/tmp`、`/app/logs` 两个 emptyDir
  （默认开）—— 否则嵌入式 Tomcat 因无法写 `/tmp` 启动失败。
- **上游依赖**：Iceberg REST Catalog / Flink SQL Gateway / NebulaGraph / rule-engine，
  端点由 `env.*` 覆盖（默认值同 `application.yml`）；缺失时相关能力降级，进程本身可启动。
- **剩余步骤（未做）**：纳入本地/交付 umbrella values 与 kind 冒烟（需要 Nebula 等依赖先就位）；
  多副本前先确认事件轮询幂等（见服务 README）。

```bash
helm template real-time-pipeline design/deploy/charts/real-time-pipeline
helm upgrade --install real-time-pipeline design/deploy/charts/real-time-pipeline -n sq-governance
```
