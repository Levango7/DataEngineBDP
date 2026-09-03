# 前端 API ↔ 后端路由 合约对照表（多语言后端）

> 由 `scripts/gen-api-contract.py` 自动生成（Sprint 2.2 多语言版），勿手改。

- 前端入口：`frontend/src/api/*.ts`（共 36 个文件）
- 后端前缀：Java 64 / Python 20 / Go 16（含显式注册表 4 项）
- 扫描范围：Java `@RequestMapping`、Python `APIRouter(prefix)`、Go `Group(...)`+`GO_SERVICE_PREFIXES` 注册表
- 前端 baseURL=`/api/v1`（client.ts，engine.ts 物化视图例外用 `/api`）；「首段」为去掉 baseURL 后第一段

## account.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/account/plan`  | `/account` | encaps-tenant | ✅ |
| `/account/billing`  | `/account` | encaps-tenant | ✅ |
| `/account/upgrade`  | `/account` | encaps-tenant | ✅ |

## admin.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/admin/kpi`  | `/admin` | encaps-tenant | ✅ |
| `/admin/env-matrix`  | `/admin` | encaps-tenant | ✅ |

## ai-assistant.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/ai-assistant/chat`  | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/nl2sql`  | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/execute`  | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/recommend-chart`  | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/summarize`  | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/dashboard`  | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/superset/datasources`  | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/sessions`  | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/sessions/${id}` （1 变量） | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/sessions/${id}/pin` （1 变量） | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/sessions/${id}/rename` （1 变量） | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/messages/${messageId}/feedback` （1 变量） | `/ai-assistant` | ai-assistant | ✅ |
| `/ai-assistant/example-prompts`  | `/ai-assistant` | ai-assistant | ✅ |

## analyze.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/dashboards/${id}` （1 变量） | `/dashboards` | business-portal, finops | ✅ |
| `/dashboards`  | `/dashboards` | business-portal, finops | ✅ |
| `/dashboards/realtime`  | `/dashboards` | business-portal, finops | ✅ |

## apiCatalog.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/apis`  | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}/submit-review` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}/approve` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}/reject` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}/publish` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}/deprecate` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}/archive` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}/subscribe` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}/subscribers` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/subscriptions`  | `/subscriptions` | asset-exchange, open-api-catalog | ✅ |
| `/subscriptions/${id}/approve` （1 变量） | `/subscriptions` | asset-exchange, open-api-catalog | ✅ |
| `/subscriptions/${id}/suspend` （1 变量） | `/subscriptions` | asset-exchange, open-api-catalog | ✅ |
| `/subscriptions/${id}/resume` （1 变量） | `/subscriptions` | asset-exchange, open-api-catalog | ✅ |
| `/subscriptions/${id}/revoke` （1 变量） | `/subscriptions` | asset-exchange, open-api-catalog | ✅ |
| `/apis/${id}/call` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}/metrics` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |
| `/apis/${id}/docs` （1 变量） | `/apis` | encaps-gateway, open-api-catalog | ✅ |

## assetMarket.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/assets`  | `/assets` | asset-exchange | ✅ |
| `/assets/${id}` （1 变量） | `/assets` | asset-exchange | ✅ |
| `/assets/${id}/relist` （1 变量） | `/assets` | asset-exchange | ✅ |
| `/subscriptions`  | `/subscriptions` | asset-exchange, open-api-catalog | ✅ |
| `/assets/${assetId}/subscribe` （1 变量） | `/assets` | asset-exchange | ✅ |
| `/subscriptions/${subscriptionId}/deliver` （1 变量） | `/subscriptions` | asset-exchange, open-api-catalog | ✅ |
| `/subscriptions/${subscriptionId}/billing` （1 变量） | `/subscriptions` | asset-exchange, open-api-catalog | ✅ |

## businessPortal.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/business-lines`  | `/business-lines` | business-portal | ✅ |
| `/business-lines/${id}` （1 变量） | `/business-lines` | business-portal | ✅ |
| `/business-lines/${blId}/dashboard` （1 变量） | `/business-lines` | business-portal | ✅ |
| `/business-lines/${blId}/workbench` （1 变量） | `/business-lines` | business-portal | ✅ |
| `/business-lines/${blId}/catalog` （1 变量） | `/business-lines` | business-portal | ✅ |
| `/business-lines/${blId}/reports` （1 变量） | `/business-lines` | business-portal | ✅ |
| `/business-lines/${blId}/reports/${reportId}` （2 变量） | `/business-lines` | business-portal | ✅ |

## cluster.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/cluster/overview`  | `/cluster` | observability, observability/query-api | ✅ |
| `/cluster/nodes`  | `/cluster` | observability, observability/query-api | ✅ |
| `/cluster/pods`  | `/cluster` | observability, observability/query-api | ✅ |
| `/cluster/components`  | `/cluster` | observability, observability/query-api | ✅ |

## datasource.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/datasources/${id}` （1 变量） | `/datasources` | encaps-data | ✅ |
| `/datasources`  | `/datasources` | encaps-data | ✅ |
| `/datasources/${id}/test` （1 变量） | `/datasources` | encaps-data | ✅ |

## dev-ml.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/jobs`  | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${id}/run` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${id}/cancel` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${id}/logs` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/models/models`  | `/models` | llmops, ml-platform | ✅ |
| `/models/models/${encodeURIComponent(name)}/versions` （1 变量） | `/models` | llmops, ml-platform | ✅ |
| `/models/models/${id}` （1 变量） | `/models` | llmops, ml-platform | ✅ |
| `/registry/deployments`  | `/registry` | registry | ✅ |
| `/registry/deployments/${id}` （1 变量） | `/registry` | registry | ✅ |
| `/registry/deployments/${id}/scale` （1 变量） | `/registry` | registry | ✅ |

## dev-sched.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/jobs/${id}` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs`  | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${id}/run` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${id}/cancel` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${id}/pause` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${id}/resume` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/stream-batch/dags`  | `/stream-batch` | stream-batch-scheduler | ✅ |
| `/stream-batch/dags/${encodeURIComponent(dagId)}` （1 变量） | `/stream-batch` | stream-batch-scheduler | ✅ |

## dev-tag.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/tags`  | `/tags` | tag-engine | ✅ |
| `/tags/${id}` （1 变量） | `/tags` | tag-engine | ✅ |
| `/tags/${id}/rules` （1 变量） | `/tags` | tag-engine | ✅ |
| `/tags/${tagId}/rules/${ruleId}` （2 变量） | `/tags` | tag-engine | ✅ |
| `/tags/${id}/compute` （1 变量） | `/tags` | tag-engine | ✅ |
| `/tags/batch-compute`  | `/tags` | tag-engine | ✅ |
| `/profiles/${encodeURIComponent(userId)}` （1 变量） | `/profiles` | tag-engine | ✅ |
| `/profiles/query`  | `/profiles` | tag-engine | ✅ |
| `/profiles/count`  | `/profiles` | tag-engine | ✅ |
| `/audiences/select`  | `/audiences` | tag-engine | ✅ |

## develop.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/develop/files`  | `/develop` | encaps-layer | ✅ |
| `/develop/files/content`  | `/develop` | encaps-layer | ✅ |
| `/develop/run`  | `/develop` | encaps-layer | ✅ |
| `/develop/schedule`  | `/develop` | encaps-layer | ✅ |
| `/develop/dag`  | `/develop` | encaps-layer | ✅ |

## engine.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/virtual-tables`  | `/virtual-tables` | sql-gateway | ✅ |
| `/virtual-tables/${encodeURIComponent(name)}/schema` （1 变量） | `/virtual-tables` | sql-gateway | ✅ |
| `/virtual-tables/${encodeURIComponent(name)}/query` （1 变量） | `/virtual-tables` | sql-gateway | ✅ |
| `/virtual-tables/${encodeURIComponent(name)}/test-connection` （1 变量） | `/virtual-tables` | sql-gateway | ✅ |
| `/virtual-tables/${encodeURIComponent(name)}/refresh` （1 变量） | `/virtual-tables` | sql-gateway | ✅ |
| `/virtual-tables/cache/stats`  | `/virtual-tables` | sql-gateway | ✅ |
| `/virtual-tables/types`  | `/virtual-tables` | sql-gateway | ✅ |
| `/materialized-views`  | `/materialized-views` | flink-cdc | ✅ |
| `/materialized-views/${encodeURIComponent(name)}/refresh` （1 变量） | `/materialized-views` | flink-cdc | ✅ |
| `/materialized-views/${encodeURIComponent(name)}/status` （1 变量） | `/materialized-views` | flink-cdc | ✅ |
| `/materialized-views/status`  | `/materialized-views` | flink-cdc | ✅ |
| `/jobs/${encodeURIComponent(id)}` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs`  | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${encodeURIComponent(id)}/run` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${encodeURIComponent(id)}/cancel` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${encodeURIComponent(id)}/logs` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${encodeURIComponent(id)}/savepoint` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/flink/jobs/${encodeURIComponent(jobId)}/checkpoints` （1 变量） | `/flink` | encaps-data | ✅ |
| `/flink/jobs/${encodeURIComponent(jobId)}/savepoints` （1 变量） | `/flink` | encaps-data | ✅ |
| `/flink/jobs/${encodeURIComponent(jobId)}/backpressure` （1 变量） | `/flink` | encaps-data | ✅ |
| `/doris/databases/${encodeURIComponent(db)}/tables` （1 变量） | `/doris` | encaps-data | ✅ |
| `/kafka/${encodeURIComponent(clusterId)}/brokers` （1 变量） | `/kafka` | encaps-data | ✅ |
| `/kafka/${encodeURIComponent(clusterId)}/topics` （1 变量） | `/kafka` | encaps-data | ✅ |
| `/kafka/${encodeURIComponent(clusterId)}/topics/${encodeURIComponent(name)}` （2 变量） | `/kafka` | encaps-data | ✅ |
| `/kafka/${encodeURIComponent(clusterId)}/consumer-groups` （1 变量） | `/kafka` | encaps-data | ✅ |
| `/kafka/${encodeURIComponent(clusterId)}/topics/${encodeURIComponent(topic)}/sample` （2 变量） | `/kafka` | encaps-data | ✅ |
| `/iotdb/${encodeURIComponent(id)}/storage-groups` （1 变量） | `/iotdb` | encaps-data | ✅ |
| `/iotdb/${encodeURIComponent(id)}/devices` （1 变量） | `/iotdb` | encaps-data | ✅ |
| `/iotdb/${encodeURIComponent(id)}/timeseries` （1 变量） | `/iotdb` | encaps-data | ✅ |
| `/iotdb/${encodeURIComponent(id)}/write-throughput` （1 变量） | `/iotdb` | encaps-data | ✅ |
| `/virtual-tables/${encodeURIComponent(tableName)}/query` （1 变量） | `/virtual-tables` | sql-gateway | ✅ |
| `/virtual-tables/${encodeURIComponent(tableName)}/test-connection` （1 变量） | `/virtual-tables` | sql-gateway | ✅ |

## gateway.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/gateway/stats`  | `/gateway` | encaps-gateway | ✅ |
| `/gateway/keys`  | `/gateway` | encaps-gateway | ✅ |
| `/gateway/keys/${id}` （1 变量） | `/gateway` | encaps-gateway | ✅ |

## govern-meta.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/metadata/sources`  | `/metadata` | governance/metadata-collector | ✅ |
| `/metadata/sources/${id}` （1 变量） | `/metadata` | governance/metadata-collector | ✅ |
| `/metadata/collect/${sourceId}` （1 变量） | `/metadata` | governance/metadata-collector | ✅ |
| `/metadata/collect/status/${sourceId}` （1 变量） | `/metadata` | governance/metadata-collector | ✅ |
| `/metadata/collect/history/${sourceId}` （1 变量） | `/metadata` | governance/metadata-collector | ✅ |
| `/metadata/collect/test/${sourceId}` （1 变量） | `/metadata` | governance/metadata-collector | ✅ |
| `/metadata/collect/schedule/${sourceId}` （1 变量） | `/metadata` | governance/metadata-collector | ✅ |
| `/metadata/collectors`  | `/metadata` | governance/metadata-collector | ✅ |

## governance.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/governance/assets/${id}` （1 变量） | `/governance` | encaps-layer, governance/real-time-pipeline | ✅ |
| `/governance/assets`  | `/governance` | encaps-layer, governance/real-time-pipeline | ✅ |
| `/governance/assets/${id}/schema` （1 变量） | `/governance` | encaps-layer, governance/real-time-pipeline | ✅ |
| `/governance/assets/${id}/quality` （1 变量） | `/governance` | encaps-layer, governance/real-time-pipeline | ✅ |
| `/governance/assets/${id}/permissions` （1 变量） | `/governance` | encaps-layer, governance/real-time-pipeline | ✅ |
| `/governance/assets/${id}/apply-permission` （1 变量） | `/governance` | encaps-layer, governance/real-time-pipeline | ✅ |

## infra.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/clusters/xinchang`  | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/xinchang/${clusterId}` （1 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/xinchang/${clusterId}/scale` （1 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/private/${provider}` （1 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/private/${provider}/${clusterId}` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/cloud/${provider}` （1 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/cloud/${provider}/${clusterId}` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters`  | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}` （1 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/scale` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/nodes` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/components` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/providers`  | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/environments`  | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/network` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/network/policies` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/network/policies/${name}` （3 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/network/cnis` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/storage/classes` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/storage/pvcs` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/storage/pvcs/${name}` （3 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/storage/usage` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/storage/pvcs/${pvcName}/snapshot` （3 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/hpa` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/hpa/${name}` （3 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/scale/events` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |
| `/clusters/${env}/${clusterId}/scale/summary` （2 变量） | `/clusters` | infra-orchestrator, infra-provider-cloud, infra-provider-private, infra-provider-xinchang, karmada | ✅ |

## integrate.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/integrate/tasks/${id}` （1 变量） | `/integrate` | encaps-layer | ✅ |
| `/integrate/tasks`  | `/integrate` | encaps-layer | ✅ |
| `/integrate/tasks/${id}/run` （1 变量） | `/integrate` | encaps-layer | ✅ |
| `/integrate/tasks/${id}/stop` （1 变量） | `/integrate` | encaps-layer | ✅ |
| `/integrate/connectors`  | `/integrate` | encaps-layer | ✅ |

## job.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/jobs/${id}` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs`  | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${id}/cancel` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${id}/logs` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |
| `/jobs/${id}/status` （1 变量） | `/jobs` | stream-batch-scheduler | ✅ |

## knowledge.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/knowledge`  | `/knowledge` | encaps-layer | ✅ |
| `/knowledge/${id}` （1 变量） | `/knowledge` | encaps-layer | ✅ |
| `/knowledge/rag-strategy`  | `/knowledge` | encaps-layer | ✅ |
| `/knowledge/${kbId}/documents` （1 变量） | `/knowledge` | encaps-layer | ✅ |
| `/knowledge/upload`  | `/knowledge` | encaps-layer | ✅ |
| `/knowledge/${kbId}/documents/${docId}` （2 变量） | `/knowledge` | encaps-layer | ✅ |

## lineage.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/lineage/api/v1/lineage/upstream/${encodeURIComponent(table)}` （1 变量） | `/lineage` | governance/lineage-analyzer | ✅ |
| `/lineage/api/v1/lineage/downstream/${encodeURIComponent(table)}` （1 变量） | `/lineage` | governance/lineage-analyzer | ✅ |
| `/lineage/api/v1/lineage/impact/${encodeURIComponent(table)}` （1 变量） | `/lineage` | governance/lineage-analyzer | ✅ |

## llmops.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/llmops/models`  | `/llmops` | encaps-layer, llmops | ✅ |
| `/llmops/eval-metrics`  | `/llmops` | encaps-layer, llmops | ✅ |
| `/llmops/finetune`  | `/llmops` | encaps-layer, llmops | ✅ |
| `/llmops/finetune/${encodeURIComponent(taskId)}` （1 变量） | `/llmops` | encaps-layer, llmops | ✅ |
| `/llmops/human-eval`  | `/llmops` | encaps-layer, llmops | ✅ |
| `/llmops/inference-services`  | `/llmops` | encaps-layer, llmops | ✅ |

## ops.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/ops/overview`  | `/ops` | observability, observability/query-api | ✅ |
| `/ops/jobs`  | `/ops` | observability, observability/query-api | ✅ |
| `/ops/alerts`  | `/ops` | observability, observability/query-api | ✅ |
| `/ops/alerts/${id}/handle` （1 变量） | `/ops` | observability, observability/query-api | ✅ |
| `/ops/jobs/${jobId}/logs` （1 变量） | `/ops` | observability, observability/query-api | ✅ |

## orchestrator-viz.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/orchestrator/dags`  | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/stop` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/json` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/mermaid` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/thoughts` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/tool-calls` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/intervention` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/intervene` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/checkpoints` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/checkpoint` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/executions` （1 变量） | `/orchestrator` | rule-engine | ✅ |
| `/orchestrator/dags/${id}/replay/${execId}` （2 变量） | `/orchestrator` | rule-engine | ✅ |

## project.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/projects/${id}` （1 变量） | `/projects` | encaps-layer, encaps-tenant | ✅ |
| `/projects`  | `/projects` | encaps-layer, encaps-tenant | ✅ |
| `/projects/${id}/datasets` （1 变量） | `/projects` | encaps-layer, encaps-tenant | ✅ |
| `/projects/${id}/jobs` （1 变量） | `/projects` | encaps-layer, encaps-tenant | ✅ |
| `/projects/${id}/members` （1 变量） | `/projects` | encaps-layer, encaps-tenant | ✅ |

## quality.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/quality/rules/${id}` （1 变量） | `/quality` | rule-engine | ✅ |
| `/quality/rules`  | `/quality` | rule-engine | ✅ |
| `/quality/rules/${id}/check` （1 变量） | `/quality` | rule-engine | ✅ |
| `/quality/rules/summary`  | `/quality` | rule-engine | ✅ |

## quota.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/quotas`  | `/quotas` | encaps-tenant | ✅ |
| `/quotas/${id}` （1 变量） | `/quotas` | encaps-tenant | ✅ |
| `/quotas/workspace/${workspaceId}/usage` （1 变量） | `/quotas` | encaps-tenant | ✅ |

## search.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/search`  | `/search` | encaps-data, encaps-layer | ✅ |
| `/search/facets`  | `/search` | encaps-data, encaps-layer | ✅ |
| `/search/suggest`  | `/search` | encaps-data, encaps-layer | ✅ |
| `/search/export`  | `/search` | encaps-data, encaps-layer | ✅ |
| `/search/history`  | `/search` | encaps-data, encaps-layer | ✅ |
| `/search/history/clear`  | `/search` | encaps-data, encaps-layer | ✅ |
| `/search/history/${id}/delete` （1 变量） | `/search` | encaps-data, encaps-layer | ✅ |

## sec.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/sec/policies`  | `/sec` | encaps-layer | ✅ |
| `/sec/policies/${id}` （1 变量） | `/sec` | encaps-layer | ✅ |
| `/sec/approvals`  | `/sec` | encaps-layer | ✅ |
| `/sec/approvals/${id}/approve` （1 变量） | `/sec` | encaps-layer | ✅ |
| `/sec/approvals/${id}/reject` （1 变量） | `/sec` | encaps-layer | ✅ |

## standard.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/standards/${id}` （1 变量） | `/standards` | encaps-layer | ✅ |
| `/standards`  | `/standards` | encaps-layer | ✅ |
| `/standards/summary`  | `/standards` | encaps-layer | ✅ |

## streamBatch.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/stream-batch/dags/${encodeURIComponent(dagId)}/runs` （1 变量） | `/stream-batch` | stream-batch-scheduler | ✅ |
| `/stream-batch/dags/${encodeURIComponent(dagId)}/runs/${runId}/rerun` （2 变量） | `/stream-batch` | stream-batch-scheduler | ✅ |
| `/stream-batch/dags/${encodeURIComponent(dagId)}/backfill` （1 变量） | `/stream-batch` | stream-batch-scheduler | ✅ |

## template.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/templates`  | `/templates` | encaps-layer, industry-templates | ✅ |
| `/templates/${id}` （1 变量） | `/templates` | encaps-layer, industry-templates | ✅ |
| `/templates/${id}/deploy` （1 变量） | `/templates` | encaps-layer, industry-templates | ✅ |
| `/templates/${id}/preview` （1 变量） | `/templates` | encaps-layer, industry-templates | ✅ |
| `/templates/categories`  | `/templates` | encaps-layer, industry-templates | ✅ |
| `/templates/${id}/deployments` （1 变量） | `/templates` | encaps-layer, industry-templates | ✅ |

## tenant.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/tenants/all`  | `/tenants` | encaps-layer, encaps-tenant | ✅ |
| `/tenants/${id}` （1 变量） | `/tenants` | encaps-layer, encaps-tenant | ✅ |
| `/tenants`  | `/tenants` | encaps-layer, encaps-tenant | ✅ |

## vector.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/vector`  | `/vector` | vector-engine | ✅ |
| `/vector/search`  | `/vector` | vector-engine | ✅ |

## workspace.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/workspaces/all`  | `/workspaces` | encaps-tenant | ✅ |
| `/workspaces/${id}` （1 变量） | `/workspaces` | encaps-tenant | ✅ |
| `/workspaces`  | `/workspaces` | encaps-tenant | ✅ |
| `/workspaces/${id}/status` （1 变量） | `/workspaces` | encaps-tenant | ✅ |

## 汇总

- 匹配：258
- 未匹配：0

