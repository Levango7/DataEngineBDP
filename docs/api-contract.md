# 前端 API ↔ 后端路由 合约对照表（多语言后端）

> 由 `scripts/gen-api-contract.py` 自动生成（Sprint 2.2 多语言版），勿手改。

- 前端入口：`frontend/src/api/*.ts`（共 39 个文件）
- 后端前缀：Java 69 / Python 21 / Go 17（含显式注册表 9 项）
- 扫描范围：Java `@RequestMapping`、Python `APIRouter(prefix)`、Go `Group(...)`+`GO_SERVICE_PREFIXES` 注册表
- 前端 baseURL=`/api/v1`（client.ts，engine.ts 物化视图例外用 `/api`）；「首段」为去掉 baseURL 后第一段

## 字段级契约（A1 补充）

路由级契约由本表覆盖；**字段级**契约由 `scripts/check-api-schema-drift.py` 校验
——对比 springdoc 产出的 OpenAPI schema（快照 `docs/api-contract-openapi.json`）与
前端 `types.ts` 手写 interface 的字段集，同名类型字段漂移即 CI 阻断。

**快照刷新流程**（后端 Entity/DTO 字段变更后执行）：

```bash
# 1. 启动 encaps-layer（local-auth 模式便于拿 token）
java -jar platform/encaps-layer/target/encaps-layer-0.1.0-SNAPSHOT-exec.jar \
  --server.port=18099 --app.security.local-auth.enabled=true
# 2. 登录拿 token（admin/admin）
TOKEN=$(curl -s -X POST http://127.0.0.1:18099/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}' | jq -r .data.token)
# 3. 刷新快照并校验
python scripts/check-api-schema-drift.py --base-url http://127.0.0.1:18099 --token "$TOKEN" --refresh
```

同名不同义的已知冲突在脚本 `KNOWN_NAME_CLASHES` 显式豁免（须注明来源）。

## account.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/account/plan`  | `/account` | encaps-tenant/src | ✅ |
| `/account/plans`  | `/account` | encaps-tenant/src | ✅ |
| `/account/billing`  | `/account` | encaps-tenant/src | ✅ |
| `/account/upgrade`  | `/account` | encaps-tenant/src | ✅ |

## admin.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/admin/kpi`  | `/admin` | encaps-tenant/src | ✅ |
| `/admin/env-matrix`  | `/admin` | encaps-tenant/src | ✅ |

## ai-assistant.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/ai-assistant/chat`  | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/nl2sql`  | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/execute`  | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/recommend-chart`  | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/summarize`  | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/dashboard`  | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/superset/datasources`  | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/sessions`  | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/sessions/${id}` （1 变量） | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/sessions/${id}/pin` （1 变量） | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/sessions/${id}/rename` （1 变量） | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/messages/${messageId}/feedback` （1 变量） | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |
| `/ai-assistant/example-prompts`  | `/ai-assistant` | ai-assistant, ai-assistant/cmd | ✅ |

## analyze.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/dashboards/${id}` （1 变量） | `/dashboards` | business-portal/business_portal, finops/dashboard | ✅ |
| `/dashboards`  | `/dashboards` | business-portal/business_portal, finops/dashboard | ✅ |
| `/dashboards/realtime`  | `/dashboards` | business-portal/business_portal, finops/dashboard | ✅ |

## apiCatalog.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/apis`  | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/submit-review` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/approve` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/reject` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/publish` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/deprecate` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/archive` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/subscribe` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/subscribers` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/subscriptions`  | `/subscriptions` | open-api-catalog/openapi_catalog | ✅ |
| `/subscriptions/${id}/approve` （1 变量） | `/subscriptions` | open-api-catalog/openapi_catalog | ✅ |
| `/subscriptions/${id}/suspend` （1 变量） | `/subscriptions` | open-api-catalog/openapi_catalog | ✅ |
| `/subscriptions/${id}/resume` （1 变量） | `/subscriptions` | open-api-catalog/openapi_catalog | ✅ |
| `/subscriptions/${id}/revoke` （1 变量） | `/subscriptions` | open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/call` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/metrics` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/docs` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/apisix-deploy` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/generate/options`  | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/generate/sql`  | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/generate/model`  | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/generate/function`  | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/apis/${id}/billing` （1 变量） | `/apis` | encaps-gateway/src, open-api-catalog/openapi_catalog | ✅ |
| `/subscriptions/${id}` （1 变量） | `/subscriptions` | open-api-catalog/openapi_catalog | ✅ |
| `/subscriptions/${id}/keys` （1 变量） | `/subscriptions` | open-api-catalog/openapi_catalog | ✅ |
| `/subscriptions/${id}/rate-limit` （1 变量） | `/subscriptions` | open-api-catalog/openapi_catalog | ✅ |

## assetMarket.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/assets`  | `/assets` | asset-exchange/asset_exchange | ✅ |
| `/assets/${id}` （1 变量） | `/assets` | asset-exchange/asset_exchange | ✅ |
| `/assets/${id}/relist` （1 变量） | `/assets` | asset-exchange/asset_exchange | ✅ |
| `/asset-subscriptions`  | `/asset-subscriptions` | asset-exchange/asset_exchange | ✅ |
| `/assets/${assetId}/subscribe` （1 变量） | `/assets` | asset-exchange/asset_exchange | ✅ |
| `/asset-subscriptions/${subscriptionId}/deliver` （1 变量） | `/asset-subscriptions` | asset-exchange/asset_exchange | ✅ |
| `/asset-subscriptions/${subscriptionId}/billing` （1 变量） | `/asset-subscriptions` | asset-exchange/asset_exchange | ✅ |
| `/assets/${encodeURIComponent(assetId)}/audit` （1 变量） | `/assets` | asset-exchange/asset_exchange | ✅ |
| `/assets/${encodeURIComponent(assetId)}/publish` （1 变量） | `/assets` | asset-exchange/asset_exchange | ✅ |
| `/assets/register`  | `/assets` | asset-exchange/asset_exchange | ✅ |

## businessPortal.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/business-lines`  | `/business-lines` | business-portal/business_portal | ✅ |
| `/business-lines/${id}` （1 变量） | `/business-lines` | business-portal/business_portal | ✅ |
| `/business-lines/${blId}/dashboard` （1 变量） | `/business-lines` | business-portal/business_portal | ✅ |
| `/business-lines/${blId}/workbench` （1 变量） | `/business-lines` | business-portal/business_portal | ✅ |
| `/business-lines/${blId}/catalog` （1 变量） | `/business-lines` | business-portal/business_portal | ✅ |
| `/business-lines/${blId}/reports` （1 变量） | `/business-lines` | business-portal/business_portal | ✅ |
| `/business-lines/${blId}/reports/${reportId}` （2 变量） | `/business-lines` | business-portal/business_portal | ✅ |

## cluster.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/cluster/overview`  | `/cluster` | observability/query-api | ✅ |
| `/cluster/nodes`  | `/cluster` | observability/query-api | ✅ |
| `/cluster/pods`  | `/cluster` | observability/query-api | ✅ |
| `/cluster/components`  | `/cluster` | observability/query-api | ✅ |

## datasource.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/datasources/${id}` （1 变量） | `/datasources` | encaps-data/src | ✅ |
| `/datasources`  | `/datasources` | encaps-data/src | ✅ |
| `/datasources/${id}/test` （1 变量） | `/datasources` | encaps-data/src | ✅ |

## dev-ml.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/jobs`  | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${id}/run` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${id}/cancel` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${id}/logs` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/models`  | `/models` | llmops/llmops, ml-platform/ml_platform | ✅ |
| `/models/${encodeURIComponent(name)}/versions` （1 变量） | `/models` | llmops/llmops, ml-platform/ml_platform | ✅ |
| `/models/${id}` （1 变量） | `/models` | llmops/llmops, ml-platform/ml_platform | ✅ |
| `/registry/deployments`  | `/registry` | - | ❌ |
| `/registry/deployments/${id}` （1 变量） | `/registry` | - | ❌ |
| `/registry/deployments/${id}/scale` （1 变量） | `/registry` | - | ❌ |

## dev-sched.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/jobs/${id}` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs`  | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${id}/run` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${id}/cancel` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${id}/pause` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${id}/resume` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/stream-batch/dags`  | `/stream-batch` | stream-batch-scheduler/src | ✅ |
| `/stream-batch/dags/${encodeURIComponent(dagId)}` （1 变量） | `/stream-batch` | stream-batch-scheduler/src | ✅ |

## dev-tag.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/tags`  | `/tags` | tag-engine/src | ✅ |
| `/tags/${id}` （1 变量） | `/tags` | tag-engine/src | ✅ |
| `/tags/${id}/rules` （1 变量） | `/tags` | tag-engine/src | ✅ |
| `/tags/${tagId}/rules/${ruleId}` （2 变量） | `/tags` | tag-engine/src | ✅ |
| `/tags/${id}/compute` （1 变量） | `/tags` | tag-engine/src | ✅ |
| `/tags/batch-compute`  | `/tags` | tag-engine/src | ✅ |
| `/profiles/${encodeURIComponent(userId)}` （1 变量） | `/profiles` | tag-engine/src | ✅ |
| `/profiles/query`  | `/profiles` | tag-engine/src | ✅ |
| `/profiles/count`  | `/profiles` | tag-engine/src | ✅ |
| `/audiences/select`  | `/audiences` | tag-engine/src | ✅ |

## develop.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/develop/files`  | `/develop` | encaps-layer/src | ✅ |
| `/develop/files/content`  | `/develop` | encaps-layer/src | ✅ |
| `/develop/run`  | `/develop` | encaps-layer/src | ✅ |
| `/develop/schedule`  | `/develop` | encaps-layer/src | ✅ |
| `/develop/dag`  | `/develop` | encaps-layer/src | ✅ |

## engine.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/virtual-tables`  | `/virtual-tables` | sql-gateway/src | ✅ |
| `/virtual-tables/${encodeURIComponent(name)}/schema` （1 变量） | `/virtual-tables` | sql-gateway/src | ✅ |
| `/virtual-tables/${encodeURIComponent(name)}/query` （1 变量） | `/virtual-tables` | sql-gateway/src | ✅ |
| `/virtual-tables/${encodeURIComponent(name)}/test-connection` （1 变量） | `/virtual-tables` | sql-gateway/src | ✅ |
| `/virtual-tables/${encodeURIComponent(name)}/refresh` （1 变量） | `/virtual-tables` | sql-gateway/src | ✅ |
| `/virtual-tables/cache/stats`  | `/virtual-tables` | sql-gateway/src | ✅ |
| `/virtual-tables/types`  | `/virtual-tables` | sql-gateway/src | ✅ |
| `/materialized-views`  | `/materialized-views` | flink-cdc/src | ✅ |
| `/materialized-views/${encodeURIComponent(name)}/refresh` （1 变量） | `/materialized-views` | flink-cdc/src | ✅ |
| `/materialized-views/${encodeURIComponent(name)}/status` （1 变量） | `/materialized-views` | flink-cdc/src | ✅ |
| `/materialized-views/status`  | `/materialized-views` | flink-cdc/src | ✅ |
| `/jobs/${encodeURIComponent(id)}` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs`  | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${encodeURIComponent(id)}/run` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${encodeURIComponent(id)}/cancel` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${encodeURIComponent(id)}/logs` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${encodeURIComponent(id)}/savepoint` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/flink/jobs/${encodeURIComponent(jobId)}/checkpoints` （1 变量） | `/flink` | encaps-data/src | ✅ |
| `/flink/jobs/${encodeURIComponent(jobId)}/savepoints` （1 变量） | `/flink` | encaps-data/src | ✅ |
| `/flink/jobs/${encodeURIComponent(jobId)}/backpressure` （1 变量） | `/flink` | encaps-data/src | ✅ |
| `/doris/databases/${encodeURIComponent(db)}/tables` （1 变量） | `/doris` | encaps-data/src | ✅ |
| `/kafka/${encodeURIComponent(clusterId)}/brokers` （1 变量） | `/kafka` | encaps-data/src | ✅ |
| `/kafka/${encodeURIComponent(clusterId)}/topics` （1 变量） | `/kafka` | encaps-data/src | ✅ |
| `/kafka/${encodeURIComponent(clusterId)}/topics/${encodeURIComponent(name)}` （2 变量） | `/kafka` | encaps-data/src | ✅ |
| `/kafka/${encodeURIComponent(clusterId)}/consumer-groups` （1 变量） | `/kafka` | encaps-data/src | ✅ |
| `/kafka/${encodeURIComponent(clusterId)}/topics/${encodeURIComponent(topic)}/sample` （2 变量） | `/kafka` | encaps-data/src | ✅ |
| `/iotdb/${encodeURIComponent(id)}/storage-groups` （1 变量） | `/iotdb` | encaps-data/src | ✅ |
| `/iotdb/${encodeURIComponent(id)}/devices` （1 变量） | `/iotdb` | encaps-data/src | ✅ |
| `/iotdb/${encodeURIComponent(id)}/timeseries` （1 变量） | `/iotdb` | encaps-data/src | ✅ |
| `/iotdb/${encodeURIComponent(id)}/write-throughput` （1 变量） | `/iotdb` | encaps-data/src | ✅ |
| `/virtual-tables/${encodeURIComponent(tableName)}/query` （1 变量） | `/virtual-tables` | sql-gateway/src | ✅ |
| `/virtual-tables/${encodeURIComponent(tableName)}/test-connection` （1 变量） | `/virtual-tables` | sql-gateway/src | ✅ |

## exchange.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/assets/${encodeURIComponent(assetId)}/settle` （1 变量） | `/assets` | asset-exchange/asset_exchange | ✅ |
| `/assets/${encodeURIComponent(assetId)}/settlements` （1 变量） | `/assets` | asset-exchange/asset_exchange | ✅ |
| `/assets/${encodeURIComponent(assetId)}/allocate` （1 变量） | `/assets` | asset-exchange/asset_exchange | ✅ |
| `/assets/${encodeURIComponent(assetId)}/allocations` （1 变量） | `/assets` | asset-exchange/asset_exchange | ✅ |
| `/audit-logs`  | `/audit-logs` | asset-exchange/asset_exchange | ✅ |
| `/audit-logs/integrity`  | `/audit-logs` | asset-exchange/asset_exchange | ✅ |
| `/audit-logs/${encodeURIComponent(logId)}` （1 变量） | `/audit-logs` | asset-exchange/asset_exchange | ✅ |
| `/settlements`  | `/settlements` | asset-exchange/asset_exchange | ✅ |

## gateway.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/gateway/stats`  | `/gateway` | encaps-gateway/src | ✅ |
| `/gateway/keys`  | `/gateway` | encaps-gateway/src | ✅ |
| `/gateway/keys/${id}` （1 变量） | `/gateway` | encaps-gateway/src | ✅ |

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
| `/governance/assets/${id}` （1 变量） | `/governance` | encaps-layer/src, governance/real-time-pipeline | ✅ |
| `/governance/assets`  | `/governance` | encaps-layer/src, governance/real-time-pipeline | ✅ |
| `/governance/assets/${id}/schema` （1 变量） | `/governance` | encaps-layer/src, governance/real-time-pipeline | ✅ |
| `/governance/assets/${id}/quality` （1 变量） | `/governance` | encaps-layer/src, governance/real-time-pipeline | ✅ |
| `/governance/assets/${id}/permissions` （1 变量） | `/governance` | encaps-layer/src, governance/real-time-pipeline | ✅ |
| `/governance/assets/${id}/apply-permission` （1 变量） | `/governance` | encaps-layer/src, governance/real-time-pipeline | ✅ |

## infra.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/clusters/xinchang`  | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/xinchang/${clusterId}` （1 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/xinchang/${clusterId}/scale` （1 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/private/${provider}` （1 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/private/${provider}/${clusterId}` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/cloud/${provider}` （1 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/cloud/${provider}/${clusterId}` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters`  | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}` （1 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/scale` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/nodes` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/components` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/providers`  | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/environments`  | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/network` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/network/policies` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/network/policies/${name}` （3 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/network/cnis` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/storage/classes` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/storage/pvcs` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/storage/pvcs/${name}` （3 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/storage/usage` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/storage/pvcs/${pvcName}/snapshot` （3 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/hpa` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/hpa/${name}` （3 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/scale/events` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |
| `/clusters/${env}/${clusterId}/scale/summary` （2 变量） | `/clusters` | infra-orchestrator/src, infra-provider-cloud/src, infra-provider-private/src, infra-provider-xinchang/src, karmada/failover, karmada/karmada-api | ✅ |

## integrate.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/integrate/tasks/${id}` （1 变量） | `/integrate` | encaps-layer/src | ✅ |
| `/integrate/tasks`  | `/integrate` | encaps-layer/src | ✅ |
| `/integrate/tasks/${id}/run` （1 变量） | `/integrate` | encaps-layer/src | ✅ |
| `/integrate/tasks/${id}/stop` （1 变量） | `/integrate` | encaps-layer/src | ✅ |
| `/integrate/connectors`  | `/integrate` | encaps-layer/src | ✅ |

## job.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/jobs/${id}` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs`  | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${id}/cancel` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${id}/logs` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |
| `/jobs/${id}/status` （1 变量） | `/jobs` | stream-batch-scheduler/src | ✅ |

## karmada.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/override-policies`  | `/override-policies` | karmada/failover | ✅ |
| `/override-policies/${encodeURIComponent(name)}` （1 变量） | `/override-policies` | karmada/failover | ✅ |
| `/failover-events/${encodeURIComponent(eventId)}` （1 变量） | `/failover-events` | karmada/failover | ✅ |
| `/failover-events`  | `/failover-events` | karmada/failover | ✅ |
| `/replica-plans/${encodeURIComponent(policyName)}` （1 变量） | `/replica-plans` | karmada/failover | ✅ |
| `/replica-plans`  | `/replica-plans` | karmada/failover | ✅ |
| `/failover-policies/${encodeURIComponent(name)}` （1 变量） | `/failover-policies` | karmada/failover | ✅ |
| `/failover-policies`  | `/failover-policies` | karmada/failover | ✅ |

## knowledge.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/knowledge`  | `/knowledge` | encaps-layer/src | ✅ |
| `/knowledge/${id}` （1 变量） | `/knowledge` | encaps-layer/src | ✅ |
| `/knowledge/rag-strategy`  | `/knowledge` | encaps-layer/src | ✅ |
| `/knowledge/${kbId}/documents` （1 变量） | `/knowledge` | encaps-layer/src | ✅ |
| `/knowledge/upload`  | `/knowledge` | encaps-layer/src | ✅ |
| `/knowledge/${kbId}/documents/${docId}` （2 变量） | `/knowledge` | encaps-layer/src | ✅ |

## lineage.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/lineage/api/v1/lineage/upstream`  | `/lineage` | governance/lineage-analyzer | ✅ |
| `/lineage/api/v1/lineage/downstream`  | `/lineage` | governance/lineage-analyzer | ✅ |
| `/lineage/api/v1/lineage/impact`  | `/lineage` | governance/lineage-analyzer | ✅ |

## llmops.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/llmops/models`  | `/llmops` | llmops/llmops | ✅ |
| `/llmops/eval-metrics`  | `/llmops` | llmops/llmops | ✅ |
| `/llmops/finetune`  | `/llmops` | llmops/llmops | ✅ |
| `/llmops/finetune/${encodeURIComponent(taskId)}` （1 变量） | `/llmops` | llmops/llmops | ✅ |
| `/llmops/human-eval`  | `/llmops` | llmops/llmops | ✅ |
| `/llmops/inference-services`  | `/llmops` | llmops/llmops | ✅ |

## ops.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/ops/overview`  | `/ops` | observability/query-api | ✅ |
| `/ops/jobs`  | `/ops` | observability/query-api | ✅ |
| `/ops/alerts`  | `/ops` | observability/query-api | ✅ |
| `/ops/alerts/${id}/handle` （1 变量） | `/ops` | observability/query-api | ✅ |
| `/ops/jobs/${jobId}/logs` （1 变量） | `/ops` | observability/query-api | ✅ |

## orchestrator-viz.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/orchestrator/dags`  | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/stop` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/json` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/mermaid` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/thoughts` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/tool-calls` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/intervention` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/intervene` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/checkpoints` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/checkpoint` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/executions` （1 变量） | `/orchestrator` | rule-engine/src | ✅ |
| `/orchestrator/dags/${id}/replay/${execId}` （2 变量） | `/orchestrator` | rule-engine/src | ✅ |

## project.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/projects/${id}` （1 变量） | `/projects` | encaps-layer/src, encaps-tenant/src | ✅ |
| `/projects`  | `/projects` | encaps-layer/src, encaps-tenant/src | ✅ |
| `/projects/${id}/datasets` （1 变量） | `/projects` | encaps-layer/src, encaps-tenant/src | ✅ |
| `/projects/${id}/jobs` （1 变量） | `/projects` | encaps-layer/src, encaps-tenant/src | ✅ |
| `/projects/${id}/members` （1 变量） | `/projects` | encaps-layer/src, encaps-tenant/src | ✅ |

## quality.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/quality/rules/${id}` （1 变量） | `/quality` | rule-engine/src | ✅ |
| `/quality/rules`  | `/quality` | rule-engine/src | ✅ |
| `/quality/rules/${id}/check` （1 变量） | `/quality` | rule-engine/src | ✅ |
| `/quality/rules/summary`  | `/quality` | rule-engine/src | ✅ |

## quota.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/quotas`  | `/quotas` | encaps-tenant/src | ✅ |
| `/quotas/${id}` （1 变量） | `/quotas` | encaps-tenant/src | ✅ |
| `/quotas/workspace/${workspaceId}/usage` （1 变量） | `/quotas` | encaps-tenant/src | ✅ |

## search.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/search`  | `/search` | encaps-data/src, encaps-layer/src | ✅ |
| `/search/facets`  | `/search` | encaps-data/src, encaps-layer/src | ✅ |
| `/search/suggest`  | `/search` | encaps-data/src, encaps-layer/src | ✅ |
| `/search/export`  | `/search` | encaps-data/src, encaps-layer/src | ✅ |
| `/search/history`  | `/search` | encaps-data/src, encaps-layer/src | ✅ |
| `/search/history/clear`  | `/search` | encaps-data/src, encaps-layer/src | ✅ |
| `/search/history/${id}/delete` （1 变量） | `/search` | encaps-data/src, encaps-layer/src | ✅ |

## sec.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/sec/policies`  | `/sec` | encaps-layer/src | ✅ |
| `/sec/policies/${id}` （1 变量） | `/sec` | encaps-layer/src | ✅ |
| `/sec/approvals`  | `/sec` | encaps-layer/src | ✅ |
| `/sec/approvals/${id}/approve` （1 变量） | `/sec` | encaps-layer/src | ✅ |
| `/sec/approvals/${id}/reject` （1 变量） | `/sec` | encaps-layer/src | ✅ |

## standard.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/standards/${id}` （1 变量） | `/standards` | data-standard/src, encaps-layer/src | ✅ |
| `/standards`  | `/standards` | data-standard/src, encaps-layer/src | ✅ |
| `/standards/summary`  | `/standards` | data-standard/src, encaps-layer/src | ✅ |

## streamBatch.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/stream-batch/dags/${encodeURIComponent(dagId)}/runs` （1 变量） | `/stream-batch` | stream-batch-scheduler/src | ✅ |
| `/stream-batch/dags/${encodeURIComponent(dagId)}/runs/${runId}/rerun` （2 变量） | `/stream-batch` | stream-batch-scheduler/src | ✅ |
| `/stream-batch/dags/${encodeURIComponent(dagId)}/backfill` （1 变量） | `/stream-batch` | stream-batch-scheduler/src | ✅ |

## template.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/templates`  | `/templates` | industry-templates/industry_templates | ✅ |
| `/templates/${id}` （1 变量） | `/templates` | industry-templates/industry_templates | ✅ |
| `/templates/${id}/deploy` （1 变量） | `/templates` | industry-templates/industry_templates | ✅ |
| `/templates/${id}/preview` （1 变量） | `/templates` | industry-templates/industry_templates | ✅ |
| `/templates/categories`  | `/templates` | industry-templates/industry_templates | ✅ |
| `/templates/${id}/deployments` （1 变量） | `/templates` | industry-templates/industry_templates | ✅ |

## tenant.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/tenants/all`  | `/tenants` | encaps-layer/src, encaps-tenant/src | ✅ |
| `/tenants/${id}` （1 变量） | `/tenants` | encaps-layer/src, encaps-tenant/src | ✅ |
| `/tenants`  | `/tenants` | encaps-layer/src, encaps-tenant/src | ✅ |

## tenantAdminApi.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/tenants`  | `/tenants` | encaps-layer/src, encaps-tenant/src | ✅ |
| `/tenants/${id}` （1 变量） | `/tenants` | encaps-layer/src, encaps-tenant/src | ✅ |
| `/invites`  | `/invites` | encaps-layer/src | ✅ |
| `/invites/${id}` （1 变量） | `/invites` | encaps-layer/src | ✅ |
| `/invites/${encodeURIComponent(code.toUpperCase())}/preview` （1 变量） | `/invites` | encaps-layer/src | ✅ |
| `/registrations`  | `/registrations` | encaps-layer/src | ✅ |
| `/registrations/${id}/decision` （1 变量） | `/registrations` | encaps-layer/src | ✅ |

## vector.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/vector`  | `/vector` | vector-engine | ✅ |
| `/vector/search`  | `/vector` | vector-engine | ✅ |

## workspace.ts

| 前端调用 | 首段 | 后端模块 | 状态 |
|---|---|---|---|
| `/workspaces/all`  | `/workspaces` | encaps-tenant/src | ✅ |
| `/workspaces/${id}` （1 变量） | `/workspaces` | encaps-tenant/src | ✅ |
| `/workspaces`  | `/workspaces` | encaps-tenant/src | ✅ |
| `/workspaces/${id}/status` （1 变量） | `/workspaces` | encaps-tenant/src | ✅ |

## 汇总

- 匹配：291
- 未匹配：3

> ❌ 项为真实待收敛缺口（后端无此前缀的任何路由）。Sprint 2.2 已消除多语言误报，剩余项需按 Sprint 计划补建。
