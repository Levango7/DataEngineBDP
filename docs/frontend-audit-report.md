# 前端页面全面审计报告

## 1. 审计概览

- 审计日期：2026-08-16
- 项目路径：`F:\nexus\DataEngineBDP`
- 审计范围：前端 34 个现有页面与后端 API 的对接情况
- 前端路由总数：53 条（含 1 条根重定向 + 1 条兜底重定向 + 51 条业务路由）
- 前端实际功能页面：34 个（已全部接入实际 Vue 组件，无 Roadmap.vue 占位）
- 前端框架页面：2 个（Login、Dashboard）
- 前端 API 模块总数：36 个（含 `client.ts` 与 `types.ts`，业务模块 34 个）
- 后端 Java Controller 总数：60 个（含 HealthController/GlobalExceptionHandler）
- 后端 Java 业务 Controller：约 35 个（去重后）
- 后端 Go 服务：4 个（catalog、ai-assistant、vector-engine、observability query-api）
- 后端 Python FastAPI 服务：4 个（business-portal、asset-exchange、open-api-catalog、industry-templates）
- 后端 REST API 端点总数：约 220 个
- Vite proxy 条目数：11 个
- 审计结论：**所有 34 个功能页面已替换原 Roadmap 占位，但存在 40 处前后端 API 不匹配问题**

## 2. 路由配置审计

### 2.1 所有路由清单

| 序号 | 路由 path | 组件 | 类型 | 状态 |
|---:|---|---|---|---|
| 1 | `/` | — | 重定向 | → /dashboard |
| 2 | `/login` | Login.vue | 框架页 | 正常 |
| 3 | `/dashboard` | Dashboard.vue | 框架页 | 正常 |
| 4 | `/workspaces` | Workspaces.vue | 功能页 | 正常 |
| 5 | `/projects` | Projects.vue | 功能页 | 正常 |
| 6 | `/integrate` | Integrate.vue | 功能页 | 正常 |
| 7 | `/develop` | Develop.vue | 功能页 | 正常 |
| 8 | `/sql` | Sql.vue | 功能页 | 正常 |
| 9 | `/govern` | Govern.vue | 功能页 | 正常 |
| 10 | `/standard` | Standard.vue | 功能页 | 正常 |
| 11 | `/quality` | Quality.vue | 功能页 | 正常 |
| 12 | `/lineage` | Lineage.vue | 功能页 | 正常 |
| 13 | `/data-lineage` | DataLineage.vue | 功能页 | 正常 |
| 14 | `/sec` | Sec.vue | 功能页 | 正常 |
| 15 | `/vector` | Vector.vue | 功能页 | 正常 |
| 16 | `/kb` | Kb.vue | 功能页 | 正常 |
| 17 | `/llmops` | Llmops.vue | 功能页 | 正常 |
| 18 | `/gateway` | Gateway.vue | 功能页 | 正常 |
| 19 | `/analyze` | Analyze.vue | 功能页 | 正常 |
| 20 | `/ops` | Ops.vue | 功能页 | 正常 |
| 21 | `/account` | Account.vue | 功能页 | 正常 |
| 22 | `/admin` | Admin.vue | 功能页 | 正常 |
| 23 | `/tenants` | TenantManagement.vue | 功能页 | 正常 |
| 24 | `/cluster` | ClusterOverview.vue | 功能页 | 正常 |
| 25 | `/datasources` | DataSourceManagement.vue | 功能页 | 正常 |
| 26 | `/jobs` | JobManagement.vue | 功能页 | 正常 |
| 27 | `/scheduler-ops` | SchedulerOps.vue | 功能页 | 正常 |
| 28 | `/workspace-management` | WorkspaceManagement.vue | 功能页 | 正常 |
| 29 | `/quota-management` | QuotaManagement.vue | 功能页 | 正常 |
| 30 | `/sql-workbench` | SqlWorkbench.vue | 功能页 | 正常 |
| 31 | `/search` | SearchPortal.vue | 功能页 | 正常 |
| 32 | `/orchestrator/dag` | DagVisualizer.vue | 功能页 | 正常 |
| 33 | `/ai-assistant` | AiAssistant.vue | 功能页 | 正常 |
| 34 | `/infra-machine` | InfraMachine.vue | 基础设施层 | 正常 |
| 35 | `/infra-k8s` | InfraK8s.vue | 基础设施层 | 正常 |
| 36 | `/infra-net` | InfraNet.vue | 基础设施层 | 正常 |
| 37 | `/infra-store` | InfraStore.vue | 基础设施层 | 正常 |
| 38 | `/infra-sched` | InfraSched.vue | 基础设施层 | 正常 |
| 39 | `/eng-storage` | EngStorage.vue | 引擎层 | 正常 |
| 40 | `/eng-spark` | EngSpark.vue | 引擎层 | 正常 |
| 41 | `/eng-flink` | EngFlink.vue | 引擎层 | 正常 |
| 42 | `/eng-doris` | EngDoris.vue | 引擎层 | 正常 |
| 43 | `/eng-kafka` | EngKafka.vue | 引擎层 | 正常 |
| 44 | `/eng-iotdb` | EngIotdb.vue | 引擎层 | 正常 |
| 45 | `/eng-mmg` | EngMmg.vue | 引擎层 | 正常 |
| 46 | `/govern-meta` | GovernMeta.vue | 治理/开发层 | 正常 |
| 47 | `/dev-sched` | DevSched.vue | 治理/开发层 | 正常 |
| 48 | `/dev-tag` | DevTag.vue | 治理/开发层 | 正常 |
| 49 | `/dev-ml` | DevMl.vue | 治理/开发层 | 正常 |
| 50 | `/ops-tpl` | TemplateMarket.vue | 行业应用 | 正常 |
| 51 | `/ops-portal` | BusinessPortal.vue | 业务线门户 | 正常 |
| 52 | `/ops-api` | APIMarket.vue | 开放API | 正常 |
| 53 | `/ops-flow` | AssetMarket.vue | 资产流通 | 正常 |
| 54 | `/:pathMatch(.*)*` | — | 兜底重定向 | → /dashboard |

### 2.2 路由配置问题

| 问题 | 严重程度 | 说明 |
|---|---|---|
| **Roadmap.vue 未被路由引用** | P0 | 任务描述称有 16 个占位页面使用 Roadmap.vue，但实际路由文件中所有路由均引用实际 Vue 组件。Roadmap.vue 文件仍存在（24 行）但已成为死代码 |
| 部分路由缺少 `name` 属性 | P2 | 批次 12 新增的 16 个路由（infra-*、eng-*、govern-meta、dev-*、ops-tpl）未设置 `name` 字段，不利于编程式导航 |
| 路由 `meta` 不统一 | P2 | 早期 19 个路由（dashboard~admin）未设置 `meta.title` 与 `meta.icon`，仅批次 4+ 路由有完整 meta |
| 路由鉴权白名单仅 `/login` | P2 | `PUBLIC_PATHS` 只含 `/login`，无公开页（如健康检查、文档页） |
| `createWebHashHistory` 使用 Hash 模式 | P2 | 生产环境 Hash 路由不利于 SEO 与 URL 美观，建议评估切换 History 模式 |

## 3. API 对接审计

### 3.1 前端 API 模块清单

| 序号 | 模块文件 | BASE 路径 | 导出函数数 | 对接后端服务 |
|---:|---|---|---:|---|
| 1 | `client.ts` | — | 4 (get/post/put/del) | Axios 封装 |
| 2 | `types.ts` | — | — | 公共类型定义 |
| 3 | `account.ts` | `/account` | 3 | encaps-layer AccountController |
| 4 | `admin.ts` | `/admin` | 2 | encaps-layer AdminController |
| 5 | `ai-assistant.ts` | `/ai-assistant` | 13 | ai-assistant Go :18110 |
| 6 | `analyze.ts` | `/dashboards` | 6 | finops-dashboard :8085 |
| 7 | `apiCatalog.ts` | `/apis`、`/subscriptions` | 18 | open-api-catalog Python |
| 8 | `assetMarket.ts` | `/assets`、`/asset-subscriptions` | 9 | asset-exchange Python |
| 9 | `businessPortal.ts` | `/business-lines` | 10 | business-portal Python |
| 10 | `cluster.ts` | `/cluster` | 4 | observability query-api :8090 |
| 11 | `datasource.ts` | `/datasources` | 6 | encaps-layer DataSourceController |
| 12 | `dev-ml.ts` | `/jobs`、`/ml` | 9 | encaps-layer MLController |
| 13 | `dev-sched.ts` | `/jobs`、`/stream-batch/dags` | 12 | stream-batch-scheduler :8087 |
| 14 | `dev-tag.ts` | `/tags`、`/profiles`、`/audiences` | 13 | tag-engine |
| 15 | `develop.ts` | `/develop` | 5 | **缺失后端** |
| 16 | `engine.ts` | `/virtual-tables`、`/materialized-views`、`/jobs`、`/flink/jobs`、`/doris/*`、`/kafka/*`、`/iotdb/*` | 36 | sql-gateway + flink-cdc + 多引擎 |
| 17 | `gateway.ts` | `/gateway` | 5 | **缺失后端** |
| 18 | `govern-meta.ts` | `/metadata` | 12 | metadata-collector |
| 19 | `governance.ts` | `/assets` | 9 | encaps-layer AssetController |
| 20 | `infra.ts` | `/clusters/xinchang`、`/clusters/private`、`/clusters/cloud`、`/clusters` | 40 | infra-orchestrator + 3 个 provider |
| 21 | `integrate.ts` | `/integrate` | 8 | encaps-layer IntegrateController |
| 22 | `job.ts` | `/jobs` | 7 | stream-batch JobController |
| 23 | `knowledge.ts` | `/knowledge` | 3 | encaps-layer KnowledgeController |
| 24 | `lineage.ts` | `/lineage/api/v1/lineage` | 4 | lineage-analyzer :8089 |
| 25 | `llmops.ts` | `/llmops` | 4 | **缺失后端** |
| 26 | `ops.ts` | `/ops` | 5 | observability query-api :8090 |
| 27 | `orchestrator-viz.ts` | `/orchestrator/dags` | 18 | rule-engine OrchestratorController |
| 28 | `project.ts` | `/projects` | 8 | encaps-layer ProjectController |
| 29 | `quality.ts` | `/quality/rules` | 7 | rule-engine QualityRuleController |
| 30 | `quota.ts` | `/quotas` | 6 | encaps-layer QuotaController |
| 31 | `search.ts` | `/search` | 7 | encaps-layer SearchController |
| 32 | `sec.ts` | `/sec` | 7 | encaps-layer SecController |
| 33 | `sqlworkbench.ts` | `/sql/*` | 7 | sql-gateway :8088 |
| 34 | `standard.ts` | `/standards` | 6 | encaps-layer StandardController |
| 35 | `streamBatch.ts` | `/stream-batch/dags` | 3 | stream-batch-scheduler :8087 |
| 36 | `template.ts` | `/templates` | 5 | industry-templates Python |
| 37 | `tenant.ts` | `/tenants` | 6 | encaps-layer TenantController |
| 38 | `vector.ts` | `/vector` | 3 | vector-engine :8086 |
| 39 | `workspace.ts` | `/workspaces` | 7 | encaps-layer WorkspaceController |

**前端 API 函数总数：约 270 个**

### 3.2 后端 API 端点清单

#### 3.2.1 encaps-layer（Java，:8080）

| Controller | 前缀 | 端点 |
|---|---|---|
| AuthController | `/api/v1/auth` | POST /login |
| TenantController | `/api/v1/tenants` | GET、POST、GET/{id}、PUT/{id}、DELETE/{id} |
| WorkspaceController | `/api/v1/workspaces` | GET、POST、GET/{id}、PUT/{id}、DELETE/{id}、GET/{id}/status |
| QuotaController | `/api/v1/quotas` | GET、POST、GET/{id}、PUT/{id}、DELETE/{id}、GET/workspace/{wid}/usage |
| DataSourceController | `/api/v1/datasources` | GET、POST、GET/{id}、PUT/{id}、DELETE/{id} |
| ProjectController | `/api/v1/projects` | GET、POST、GET/{id}、PUT/{id}、DELETE/{id} |
| IntegrateController | `/api/v1/integrate` | GET/tasks、GET/tasks/{id}、POST/tasks、PUT/tasks/{id}、DELETE/tasks/{id} |
| StandardController | `/api/v1/standards` | GET、POST、GET/{id}、PUT/{id}、DELETE/{id} |
| SecController | `/api/v1/sec` | GET/policies、GET/policies/{id}、POST/policies、PUT/policies/{id}、DELETE/policies/{id} |
| AssetController | `/api/v1/assets` | GET、POST、GET/{id}、PUT/{id}、DELETE/{id} |
| KnowledgeController | `/api/v1/knowledge` | GET、POST、GET/{id}、PUT/{id}、DELETE/{id} |
| TemplateController | `/api/v1/templates` | GET、POST、GET/{id}、PUT/{id}、DELETE/{id} |
| ApiCatalogController | `/api/v1/apis` | GET、POST、GET/{id}、PUT/{id}、DELETE/{id} |
| SearchController | `/api/v1/search` | POST、GET/facets、GET/suggest、GET/history |
| AccountController | `/api/v1/account` | GET/plan、GET/billing、POST/upgrade |
| AdminController | `/api/v1/admin` | GET/kpi、GET/env-matrix |
| MLController | `/api/v1/ml` | GET/models、GET/models/{id}、POST/models、DELETE/models/{id}、GET/models/{name}/versions、GET/inference-services、POST/inference-services、DELETE/inference-services/{id}、POST/inference-services/{id}/scale |
| SecurityFacadeController | `/api/v1/security` | GET/status、POST/mask、GET/audit/events、GET/auth/check、POST/evidence/collect、POST/assessment/export |

#### 3.2.2 sql-gateway（Java，:8088）

| Controller | 前缀 | 端点 |
|---|---|---|
| SqlGatewayController | `/api/v1/sql` | POST/execute、GET/routes、POST/routes、GET/engines、POST/parse、POST/validate、POST/convert、POST/optimize、POST/explain、GET/optimize/rules、POST/cross-source、POST/cross-source/explain |
| VirtualTableController | `/api/v1/virtual-tables` | GET/{tableName}、PUT/{tableName}、DELETE/{tableName}、POST/{tableName}/query、GET/{tableName}/schema、POST/{tableName}/test-connection、POST/{tableName}/refresh、GET/cache/stats、GET/types |
| RewriteController | `/api/v1/rewrite` | POST/execute、POST/route、POST/candidates、GET/views、GET/views/{viewName}、POST/views、PUT/views/{viewName}、DELETE/views/{viewName}、POST/views/{viewName}/refresh、GET/rules、GET/rules/{ruleName}、POST/rules、DELETE/rules/{ruleName} |

#### 3.2.3 rule-engine（Java，:8091）

| Controller | 前缀 | 端点 |
|---|---|---|
| QualityRuleController | `/api/v1/quality/rules` | GET/{id}、PUT/{id}、DELETE/{id} |
| RuleController | `/api/v1/rules` | GET/{id}、PUT/{id}、DELETE/{id}、POST/execute、POST/execute/batch、GET/types |
| SchedulerController | `/api/v1/scheduler` | POST/tasks、GET/tasks/{id}、GET/tasks、DELETE/tasks/{id}、GET/status、POST/tenants、GET/tenants、PUT/tenants/{tid}/enabled、GET/quotas、PUT/quotas/{tid} |
| AgentController | `/api/v1/agents` | POST/{role}/execute、GET/describe、GET/{role}/describe |
| OrchestratorController | `/api/v1/orchestrator/dags` | GET/{id}、POST/{id}/run、POST/{id}/stop、GET/{id}/results、GET/{id}/mermaid、GET/{id}/json、DELETE/{id} |

#### 3.2.4 stream-batch-scheduler（Java，:8087）

| Controller | 前缀 | 端点 |
|---|---|---|
| JobController | `/api/v1/jobs` | GET/{id}、PUT/{id}、DELETE/{id}、POST/{id}/run |
| StreamBatchSchedulerController | `/api/v1/stream-batch` | POST/dags、GET/dags/{dagId}、GET/dags、GET/dags/{dagId}/runs、POST/dags/{dagId}/runs/{runId}/rerun、POST/dags/{dagId}/backfill、POST/router/route |

#### 3.2.5 governance（Java）

| Controller | 前缀 | 端点 |
|---|---|---|
| CollectorController（metadata-collector） | `/api/v1/metadata` | POST/sources、GET/sources、GET/sources/{id}、PUT/sources/{id}、DELETE/sources/{id}、POST/collect/{sourceId}、GET/collect/status/{sourceId}、POST/collect/test/{sourceId}、POST/collect/schedule/{sourceId}、DELETE/collect/schedule/{sourceId}、GET/collectors |
| LineageController（lineage-analyzer :8089） | `/api/v1/lineage` | POST/analyze、GET/upstream/{table}、GET/downstream/{table}、GET/impact/{table} |
| GovernanceController（real-time-pipeline :8092） | `/api/v1/governance` | POST/metadata/collect、GET/metadata/{tableIdentifier}、POST/lineage/parse、GET/lineage/{targetTable}、GET/lineage、POST/quality/rules、DELETE/quality/rules/{ruleId}、GET/quality/rules、POST/quality/evaluate、GET/alerts、GET/alerts/{tableIdentifier}、GET/pipeline/metrics、GET/pipeline/history |

#### 3.2.6 infra（Java）

| Controller | 前缀 | 端点 |
|---|---|---|
| ClusterController（infra-orchestrator） | `/api/v1/clusters` | DELETE/{env}/{cid}、GET/{env}/{cid}、POST/{env}/{cid}/scale、GET/{env}、GET/providers、GET/environments、GET/profiles |
| XinchangClusterController | `/api/v1/clusters/xinchang` | DELETE/{cid}、GET/{cid}、POST/{cid}/scale |
| PrivateClusterController | `/api/v1/clusters/private` | POST/{provider}、DELETE/{provider}/{id}、GET/{provider}/{id}、GET/{provider}、POST/{provider}/{id}/scale |
| CloudClusterController | `/api/v1/clusters/cloud` | POST/{provider}、DELETE/{provider}/{id}、GET/{provider}/{id}、GET/{provider}、POST/{provider}/{id}/scale、POST/{provider}/{id}/start、POST/{provider}/{id}/stop、GET/providers |

#### 3.2.7 finops（Java）

| Controller | 前缀 | 端点 |
|---|---|---|
| BiDashboardController（dashboard :8085） | `/api/v1/dashboards` | GET/{id}、PUT/{id}、DELETE/{id} |
| DashboardController | `/api/v1/dashboard` | GET/top10、GET/trend、GET/details |
| BillingController | `/api/v1/dashboard/billing` | GET/tenant、GET/tenant/trend |
| AllocationController | `/api/v1/allocation` | GET/configs、GET/configs/{id}、POST/configs、DELETE/configs/{id}、GET/execute |
| SuggestionController | `/api/v1/suggestions` | GET/idle、GET/list |
| BillExportController | `/api/v1/bill/export` | GET/csv、GET/excel |
| CostController（cost-model） | `/api/v1/cost` | POST/calculate、GET/report |
| PricingController | `/api/v1/pricing` | GET/{name}、PUT/{name} |
| MeteringController | `/api/v1/finops/metering` | POST/query |
| BillingController（cost-model） | `/api/v1/finops/billing` | GET/tenant、GET/tenant/trend |

#### 3.2.8 tag-engine（Java）

| Controller | 前缀 | 端点 |
|---|---|---|
| TagController | `/api/v1/tags` | GET/{id}、DELETE/{id}、POST/{id}/rules、GET/{id}/rules、POST/{id}/compute、POST/batch-compute |
| ProfileController | `/api/v1/profiles` | GET/{userId}、POST/query、POST/count |
| AudienceController | `/api/v1/audiences` | POST/select |

#### 3.2.9 flink-cdc（Java）

| Controller | 前缀 | 端点 |
|---|---|---|
| MaterializedViewController | `/api/materialized-views` | GET/{name}、PUT/{name}、DELETE/{name}、POST/{name}/refresh、GET/{name}/status、GET/status |

#### 3.2.10 Go 服务

| 服务 | 端口 | 端点 |
|---|---|---|
| catalog | — | GET/POST/DELETE `/api/v1/catalog/databases`、GET/POST/PUT/DELETE `/api/v1/catalog/tables` |
| ai-assistant | :18110 | POST `/api/v1/ai-assistant/chat`、POST `/chat/stream`、POST `/nl2sql`、POST `/execute`、POST `/recommend-chart`、POST `/summarize`、POST `/dashboard`、GET/POST `/sessions`、GET/DELETE `/sessions/:id` |
| vector-engine | :8086 | POST/DELETE `/collections`、POST `/collections/:name/vectors`、POST `/collections/:name/search`、POST `/collections/:name/hybrid-search`、DELETE `/collections/:name/vectors`、GET `/collections/:name/stats`、GET `/vector`、POST `/vector/search` |
| observability query-api | :8090 | GET `/api/v1/ops/health/overview`、GET `/api/v1/cluster/overview`、GET `/cluster/nodes`、GET `/cluster/pods`、GET `/cluster/components`、GET `/platform/api/v1/query` 等 |

#### 3.2.11 Python FastAPI 服务

| 服务 | 前缀 | 端点 |
|---|---|---|
| business-portal | `/api/v1/business-lines` | POST/GET/PUT/DELETE 业务线 CRUD、GET/{id}/dashboard、GET/{id}/workbench、GET/{id}/catalog、GET/POST/DELETE/{id}/reports |
| asset-exchange | `/api/v1/assets`、`/api/v1/subscriptions`、`/api/v1/audit-logs` | 资产 CRUD、订阅、审计日志 |
| open-api-catalog | `/api/v1/apis`、`/api/v1/subscriptions` | API CRUD、订阅、调用、计量、文档、APISIX 配置 |
| industry-templates | `/api/v1/templates` | GET 模板列表、GET/{id}、POST/{id}/deploy、GET/{id}/preview、GET/{id}/deployments、GET/categories |

### 3.3 不匹配的 API 调用

#### 3.3.1 前端调用但后端缺失的端点

| 前端模块 | 调用 URL | HTTP 方法 | 后端是否存在 | 问题 |
|---|---|---|---|---|
| `tenant.ts` | `/tenants/all` | GET | ❌ | TenantController 无 /all 端点，前端用于下拉选择 |
| `workspace.ts` | `/workspaces/all` | GET | ❌ | WorkspaceController 无 /all 端点 |
| `datasource.ts` | `/datasources/{id}/test` | POST | ❌ | DataSourceController 无 test 端点 |
| `job.ts` | `/jobs` (列表) | GET | ❌ | JobController 无 GET 列表端点（仅有 GET/{id}） |
| `job.ts` | `/jobs` (创建) | POST | ❌ | JobController 无 POST 创建端点 |
| `job.ts` | `/jobs/{id}/cancel` | POST | ❌ | JobController 无 cancel 端点 |
| `job.ts` | `/jobs/{id}/logs` | GET | ❌ | Job9 JobController 无 logs 端点 |
| `job.ts` | `/jobs/{id}/status` | GET | ❌ | JobController 无 status 端点 |
| `project.ts` | `/projects/{id}/datasets` | GET | ❌ | ProjectController 无 datasets 子资源 |
| `project.ts` | `/projects/{id}/jobs` | GET | ❌ | ProjectController 无 jobs 子资源 |
| `project.ts` | `/projects/{id}/members` | GET | ❌ | ProjectController 无 members 子资源 |
| `integrate.ts` | `/integrate/connectors` | GET | ❌ | IntegrateController 无 connectors 端点 |
| `integrate.ts` | `/integrate/tasks/{id}/run` | POST | ❌ | IntegrateController 无 run 端点 |
| `integrate.ts` | `/integrate/tasks/{id}/stop` | POST | ❌ | IntegrateController 无 stop 端点 |
| `develop.ts` | `/develop/files` | GET | ❌ | **后端完全缺失 DevelopController** |
| `develop.ts` | `/develop/files/content` | GET | ❌ | 同上 |
| `develop.ts` | `/develop/run` | POST | ❌ | 同上 |
| `develop.ts` | `/develop/schedule` | POST | ❌ | 同上 |
| `develop.ts` | `/develop/dag` | GET | ❌ | 同上 |
| `governance.ts` | `/assets/{id}/schema` | GET | ❌ | AssetController 无 schema 端点 |
| `governance.ts` | `/assets/{id}/quality` | GET | ❌ | AssetController 无 quality 端点 |
| `governance.ts` | `/assets/{id}/permissions` | GET | ❌ | AssetController 无 permissions 端点 |
| `governance.ts` | `/assets/{id}/apply-permission` | POST | ❌ | AssetController 无 apply-permission 端点 |
| `standard.ts` | `/standards/summary` | GET | ❌ | StandardController 无 summary 端点 |
| `quality.ts` | `/quality/rules` (列表) | GET | ❌ | QualityRuleController 无 GET 列表端点 |
| `quality.ts` | `/quality/rules` (创建) | POST | ❌ | QualityRuleController 无 POST 创建端点 |
| `quality.ts` | `/quality/rules/{id}/check` | POST | ❌ | QualityRuleController 无 check 端点 |
| `quality.ts` | `/quality/rules/summary` | GET | ❌ | QualityRuleController 无 summary �8 端点 |
| `sec.ts` | `/sec/approvals` | GET | ❌ | SecController 无 approvals 端点 |
| `sec.ts` | `/sec/approvals/{id}/approve` | POST | ❌ | 同上 |
| `sec.ts` | `/sec/approvals/{id}/reject` | POST | ❌ | 同上 |
| `knowledge.ts` | `/knowledge/rag-strategy` | GET | ❌ | KnowledgeController 无 rag-strategy 端点 |
| `knowledge.ts` | `/knowledge/upload` | POST | ❌ | KnowledgeController 无 upload 端点 |
| `llmops.ts` | `/llmops/models` | GET | ❌ | **后端完全缺失 LLMOpsController** |
| `llmops.ts` | `/llmops/eval-metrics` | GET | ❌ | 同上 |
| `llmops.ts` | `/llmops/finetune` | POST | ❌ | 同上 |
| `llmops.ts` | `/llmops/human-eval` | POST | ❌ | 同上 |
| `gateway.ts` | `/gateway/stats` | GET | ❌ | **后端完全缺失 GatewayController** |
| `gateway.ts` | `/gateway/keys` | GET/POST | ❌ | 同上 |
| `analyze.ts` | `/dashboards` (列表) | GET | ❌ | BiDashboardController 无 GET 列表端点 |
| `analyze.ts` | `/dashboards` (创建) | POST | ❌ | BiDashboardController 无 POST 创建端点 |
| `analyze.ts` | `/dashboards/realtime` | GET | ❌ | BiDashboardController 无 realtime 端点 |
| `ops.ts` | `/ops/overview` | GET | ❌ | query-api 无 /ops/overview 端点 |
| `ops.ts` | `/ops/jobs` | GET | ❌ | query-api 无 /ops/jobs 端点 |
| `ops.ts` | `/ops/alerts` | GET | ❌ | query-api 无 /ops/alerts 端点 |
| `ops.ts` | `/ops/alerts/{id}/handle` | POST | ❌ | 同上 |
| `ops.ts` | `/ops/jobs/{id}/logs` | GET | ❌ | 同上 |
| `ai-assistant.ts` | `/ai-assistant/sessions/{id}/pin` | POST | ❌ | ai-assistant Go 无 pin 端点 |
| `ai-assistant.ts` | `/ai-assistant/sessions/{id}/rename` | POST | ❌ | 同上 |
| `ai-assistant.ts` | `/ai-assistant/messages/{id}/feedback` | POST | ❌ | 同上 |
| `ai-assistant.ts` | `/ai-assistant/example-prompts` | GET | ❌ | 同上 |
| `ai-assistant.ts` | `/ai-assistant/superset/datasources` | GET | ❌ | 同上 |
| `search.ts` | `/search/export` | POST | ❌ | SearchController 无 export 端点 |
| `search.ts` | `/search/history/clear` | POST | ❌ | SearchController 无 history/clear 端点 |
| `search.ts` | `/search/history/{id}/delete` | POST | ❌ | SearchController 无 history/{id}/delete 端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags` (列表) | GET | ❌ | OrchestratorController 无 GET 列表端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags` (提交) | POST | ❌ | OrchestratorController 无 POST 提交端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags/{id}/thoughts` | GET | ❌ | 无 thoughts 端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags/{id}/tool-calls` | GET | ❌ | 无 tool-calls 端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags/{id}/intervention` | GET | ❌ | 无 intervention 端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags/{id}/intervene` | POST | ❌ | 无 intervene 端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags/{id}/checkpoints` | GET | ❌ | 无 checkpoints 端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags/{id}/checkpoint` | POST | ❌ | 无 checkpoint 端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags/{id}/resume` | POST | ❌ | 无 resume 端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags/{id}/executions` | GET | ❌ | 无 executions 端点 |
| `orchestrator-viz.ts` | `/orchestrator/dags/{id}/replay/{execId}` | GET | ❌ | 无 replay 端点 |
| `infra.ts` | `/clusters/{env}/{cid}/nodes` | GET | ❌ | ClusterController 无 nodes 子资源 |
| `infra.ts` | `/clusters/{env}/{cid}/components` | GET | ❌ | ClusterController 无 components 子资源 |
| `infra.ts` | `/clusters/{env}/{cid}/network` | GET/PUT | ❌ | ClusterController 无 network 子资源 |
| `infra.ts` | `/clusters/{env}/{cid}/network/policies` | GET/POST/DELETE | ❌ | 同上 |
| `infra.ts` | `/clusters/{env}/{cid}/network/cnis` | GET | ❌ | 同上 |
| `infra.ts` | `/clusters/{env}/{cid}/storage/*` | GET/POST/DELETE | ❌ | ClusterController 无 storage 子资源 |
| `infra.ts` | `/clusters/{env}/{cid}/hpa` | GET/POST/PUT/DELETE | ❌ | ClusterController 无 hpa 子资源 |
| `infra.ts` | `/clusters/{env}/{cid}/scale/events` | GET | ❌ | 同上 |
| `infra.ts` | `/clusters/{env}/{cid}/scale/summary` | GET | ❌ | 同上 |
| `engine.ts` | `/jobs` (Spark 列表/创建) | GET/POST | ❌ | JobController 在 stream-batch，但前端 engine.ts 调用 `/jobs` 走 encaps-layer :8080，**端口不匹配** |
| `engine.ts` | `/flink/jobs/{id}/checkpoints` | GET | ❌ | 无 Flink 专用 Controller |
| `engine.ts` | `/flink/jobs/{id}/savepoints` | GET | ❌ | 同上 |
| `engine.ts` | `/flink/jobs/{id}/backpressure` | GET | ❌ | 同上 |
| `engine.ts` | `/doris/nodes` | GET | ❌ | 无 Doris 专用 Controller |
| `engine.ts` | `/doris/databases` | GET | ❌ | 同上 |
| `engine.ts` | `/doris/queries` | GET | ❌ | 同上 |
| `engine.ts` | `/kafka/{cid}/brokers` | GET | ❌ | 无 Kafka 专用 Controller |
| `engine.ts` | `/kafka/{cid}/topics` | GET/POST/DELETE | ❌ | 同上 |
| `engine.ts` | `/kafka/{cid}/consumer-groups` | GET | ❌ | 同上 |
| `engine.ts` | `/iotdb/{id}/storage-groups` | GET | ❌ | 无 IoTDB 专用 Controller |
| `engine.ts` | `/iotdb/{id}/devices` | GET | ❌ | 同上 |
| `engine.ts` | `/iotdb/{id}/timeseries` | GET | ❌ | 同上 |

#### 3.3.2 路径冲突问题

| 冲突路径 | 前端模块 1 | 前端模块 2 | 后端服务 1 | 后端服务 2 | 问题 |
|---|---|---|---|---|---|
| `/api/v1/assets` | `governance.ts`（资产治理 CRUD） | `assetMarket.ts`（资产流通市场） | encaps-layer AssetController :8080 | asset-exchange Python :8092 | **同一前端 baseURL `/api/v1` 下两个模块共用 `/assets` 路径，但 Vite proxy `/api/v1/assets` → :8092，导致 governance.ts 调用被错误转发到 real-time-pipeline 而非 encaps-layer** |
| `/api/v1/jobs` | `job.ts`（作业管理） | `dev-sched.ts`（调度编排） | stream-batch JobController :8087 | encaps-layer :8080 | **Vite proxy 无 `/api/v1/jobs` 条目，默认走 :8080，但 JobController 在 :8087** |

#### 3.3.3 HTTP 方法不匹配

| 前端模块 | URL | 前端方法 | 后端方法 | 问题 |
|---|---|---|---|---|
| `sqlworkbench.ts` | `/sql/engines` | POST | GET | 前端 `listEngines` 用 POST，后端 SqlGatewayController 用 GET |

### 3.4 未使用的后端端点

以下后端端点前端未调用：

| 后端服务 | 端点 | 说明 |
|---|---|---|
| encaps-layer SecurityFacadeController | `/api/v1/security/*`（6 个端点） | 安全门面控制器，前端无对应模块 |
| encaps-layer AuthController | `/api/v1/auth/login` | 由 `stores/auth.ts` 直接调用，未走 api/ 模块 |
| sql-gateway RewriteController | `/api/v1/rewrite/*`（14 个端点） | SQL 重写控制器，前端无对应模块 |
| rule-engine RuleController | `/api/v1/rules/*`（7 个端点） | 通用规则控制器，前端 quality.ts 走 `/quality/rules` |
| rule-engine SchedulerController | `/api/v1/scheduler/*`（11 个端点） | 调度控制器，前端 dev-sched.ts 走 `/jobs` |
| rule-engine AgentController | `/api/v1/agents/*`（3 个端点） | Agent 控制器，前端无对应模块 |
| finops DashboardController | `/api/v1/dashboard/*`（3 个端点） | 旧版 Dashboard，前端 analyze.ts 走 `/dashboards` |
| finops BillingController | `/api/v1/dashboard/billing/*`（2 个端点） | 计费，前端 account.ts 走 `/account/billing` |
| finops AllocationController | `/api/v1/allocation/*`（5 个端点） | 分摊，前端无对应模块 |
| finops SuggestionController | `/api/v1/suggestions/*`（2 个端点） | 闲置建议，前端无对应模块 |
| finops BillExportController | `/api/v1/bill/export/*`（2 个端点） | 账单导出，前端无对应模块 |
| finops CostController | `/api/v1/cost/*`（2 个端点） | 成本计算，前端无对应模块 |
| finops PricingController | `/api/v1/pricing/*`（2 个端点） | 定价，前端无对应模块 |
| finops MeteringController | `/api/v1/finops/metering/*`（1 个端点） | 计量，前端无对应模块 |
| finops BillingController（cost-model） | `/api/v1/finops/billing/*`（2 个端点） | 计费，前端无对应模块 |
| governance GovernanceController | `/api/v1/governance/*`（14 个端点） | 实时治理，前端无对应模块 |
| governance CatalogEventListener | `/api/v1/governance/catalog/events` | 事件监听，前端无对应模块 |
| karmada FederatedQueryController | `/api/v1/federated/*`（5 个端点） | 联邦查询，前端无对应模块 |
| knative FunctionController | `/api/v1/invoke`、`/api/v1/health` | 函数调用，前端无对应模块 |
| catalog Go | `/api/v1/catalog/*`（9 个端点） | 数据目录，前端无对应模块 |
| observability query-api | `/platform/api/v1/query` 等 | Prometheus 代理，前端无对应模块 |

## 4. Vite 配置审计

### 4.1 Proxy 配置清单

| Proxy 路径 | 目标端口 | 后端服务 | 状态 |
|---|---|---|---|
| `/api` | :8080 | encaps-layer | ✅ 正常 |
| `/api/v1/ops` | :8090 | observability query-api | ✅ 正常 |
| `/api/v1/cluster` | :8090 | observability query-api | ✅ 正常 |
| `/api/v1/vector` | :8086 | vector-engine | ✅ 正常 |
| `/api/v1/ai-assistant` | :18110 | ai-assistant | ✅ 正常 |
| `/api/v1/dashboards` | :8085 | finops-dashboard | ✅ 正常 |
| `/api/v1/stream-batch` | :8087 | stream-batch-scheduler | ✅ 正常 |
| `/api/v1/sql` | :8088 | sql-gateway | ✅ 正常 |
| `/lineage` | :8089 | lineage-analyzer | ✅ 正常 |
| `/api/v1/quality` | :8091 | rule-engine | ✅ 正常 |
| `/api/v1/assets` | :8092 | real-time-pipeline | ⚠️ 路径冲突 |

### 4.2 Proxy 配置问题

| 问题 | 严重程度 | 说明 |
|---|---|---|
| **`/api/v1/assets` 路径冲突** | P0 | 该 proxy 将所有 `/api/v1/assets` 请求转发到 :8092（real-time-pipeline），但前端 `governance.ts` 也调用 `/api/v1/assets`（期望走 :8080 encaps-layer AssetController），导致 Govern.vue 页面资产 CRUD 功能失效 |
| **缺少 `/api/v1/jobs` proxy** | P1 | 前端 `job.ts` 和 `engine.ts`（Spark/Flink）调用 `/api/v1/jobs`，但无对应 proxy，默认走 :8080 encaps-layer，而 JobController 实际在 :8087 stream-batch-scheduler |
| **缺少 `/api/v1/metadata` proxy** | P1 | 前端 `govern-meta.ts` 调用 `/api/v1/metadata`，无对应 proxy，默认走 :8080，但 CollectorController 在 metadata-collector 独立服务 |
| **缺少 `/api/v1/tags`、`/api/v1/profiles`、`/api/v1/audiences` proxy** | P1 | 前端 `dev-tag.ts` 调用这些路径，无对应 proxy，默认走 :8080，但 TagController 在 tag-engine 独立服务 |
| **缺少 `/api/v1/ml` proxy** | P1 | 前端 `dev-ml.ts` 调用 `/api/v1/ml`，无对应 proxy，默认走 :8080，但 MLController 在 encaps-layer（实际匹配，但语义不明确） |
| **缺少 `/api/v1/templates` proxy** | P1 | 前端 `template.ts` 调用 `/api/v1/templates`，无对应 proxy，默认走 :8080，但 industry-templates 是 Python 服务 |
| **缺少 `/api/v1/business-lines` proxy** | P1 | 前端 `businessPortal.ts` 调用 `/api/v1/business-lines`，无对应 proxy，默认走 :8080，但 business-portal 是 Python 服务 |
| **缺少 `/api/v1/apis` proxy** | P1 | 前端 `apiCatalog.ts` 调用 `/api/v1/apis`，无对应 proxy，默认走 :8080，但 open-api-catalog 是 Python 服务 |
| **缺少 `/api/v1/virtual-tables` proxy** | P1 | 前端 `engine.ts` 调用 `/api/v1/virtual-tables`，无对应 proxy，默认走 :8080，但 VirtualTableController 在 sql-gateway :8088 |
| **缺少 `/api/materialized-views` proxy** | P1 | 前端 `engine.ts` 用 `baseURL: '/api'` 调用 `/materialized-views`，无对应 proxy，默认走 :8080，但 MaterializedViewController 在 flink-cdc 独立服务 |
| **缺少 `/api/v1/clusters` proxy** | P1 | 前端 `infra.ts` 调用 `/api/v1/clusters`，无对应 proxy，默认走 :8080，但 ClusterController 在 infra-orchestrator 独立服务 |
| **缺少 `/api/v1/orchestrator` proxy** | P1 | 前端 `orchestrator-viz.ts` 调用 `/api/v1/orchestrator/dags`，无对应 proxy，默认走 :8080，但 OrchestratorController 在 rule-engine :8091 |
| **缺少 `/api/v1/search` proxy** | P2 | 前端 `search.ts` 调用 `/api/v1/search`，无对应 proxy，默认走 :8080 encaps-layer SearchController（实际匹配） |
| **`/lineage` proxy 未带 `/api/v1` 前缀** | P2 | proxy 路径为 `/lineage` 而非 `/api/v1/lineage`，前端 lineage.ts 需用 `{ baseURL: '' }` 覆盖默认 baseURL，配置不一致 |

### 4-4.3 Proxy rewrite 规则

- **所有 proxy 条目均未配置 `rewrite` 规则**：意味着后端服务需完整接收前端发送的路径（含 `/api/v1` 前缀）。这与后端 Controller 的 `@RequestMapping("/api/v1/...")` 一致，无需 rewrite。
- **`changeOrigin: true`**：所有条目均启用，正确。

## 5. 页面组件质量审计

### 5.1 三态处理检查

| 页面 | loading 处理 | error 处理 | empty 处理 | useApi 使用 | 综合评价 |
|---|---|---|---|---|---|
| Dashboard.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| Workspaces.vue | ✅ | ✅ | ✅ | ✅ | 优 |
| Projects.vue | ✅ v-if="loading" | ✅ | ⚠️ 无 empty | ✅ | 良 |
| Integrate.vue | ✅ | ✅ | ⚠️ | ✅ | 良 |
| Develop.vue | ✅ | ✅ | ⚠️ | ❌ 直接调用 | 良 |
| Sql.vue | ✅ | ✅ | ✅ | ❌ 直接调用 | 良 |
| Govern.vue | ✅ v-if="loading" | ✅ | ⚠️ | ✅ | 良 |
| Standard.vue | ✅ v-if="loading" | ✅ | ⚠️ | ✅ | 良 |
| Quality.vue | ✅ v-if="loading" | ✅ | ⚠️ | ✅ | 良 |
| Lineage.vue | ✅ v-if="loading" | ✅ | ⚠️ | ✅ | 良 |
| DataLineage.vue | ✅ | ✅ | ✅ | ✅ | 优 |
| Sec.vue | ✅ | ✅ | ⚠️ | ✅ | 良 |
| Vector.vue | ✅ v-if="loading" | ✅ | ⚠️ | ✅ | 良 |
| Kb.vue | ✅ v-if="loading" | ✅ | ⚠️ | ✅ | 良 |
| Llmops.vue | ✅ v-if="loading" | ✅ | ⚠️ | ✅ | 良 |
| Gateway.vue | ✅ | ✅ | ⚠️ | ✅ | 良 |
| Analyze.vue | ✅ | ✅ | ⚠️ | ✅ | 良 |
| Ops.vue | ✅ | ✅ | ⚠️ | ✅ | 良 |
| Account.vue | ✅ v-if="loading" | ✅ | ⚠️ | ✅ | 良 |
| Admin.vue | ✅ v-if="loading" | ✅ | ⚠️ | ✅ | 良 |
| TenantManagement.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| ClusterOverview.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| DataSourceManagement.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| JobManagement.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| SchedulerOps.vue | ✅ v-loading | ✅ | ✅ el-empty | ✅ | 优 |
| WorkspaceManagement.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| QuotaManagement.vue | ✅ v-loading | ✅ | ✅ el-empty | ✅ | 优 |
| SqlWorkbench.vue | ✅ | ✅ | ✅ el-empty | ✅ | 优 |
| SearchPortal.vue | ✅ v-if="loading" | ✅ v-if="error" | ✅ | ✅ | 优 |
| DagVisualizer.vue | ✅ | ✅ | ✅ empty-state | ✅ | 优 |
| AiAssistant.vue | ✅ | ✅ | ✅ | ✅ | 优 |
| TemplateMarket.vue | ✅ v-loading | ✅ el-empty | ✅ el-empty | ✅ | 优 |
| BusinessPortal.vue | ✅ | ✅ | ✅ | ✅ | 优 |
| APIMarket.vue | ✅ v-if="loading" | ✅ | ✅ | ✅ | 优 |
| AssetMarket.vue | ✅ v-if="loading" | ✅ v-if="error" | ✅ | ✅ | 优 |
| InfraMachine.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| InfraK8s.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| InfraNet.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| InfraStore.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| InfraSched.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| EngStorage.vue | ✅ v-loading | ✅ | ✅ el-empty | ✅ | 优 |
| EngSpark.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| EngFlink.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| EngDoris.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| EngKafka.vue | ✅ v-loading | ✅ | ✅ el-empty | ✅ | 优 |
| EngIotdb.vue | ✅ v-loading | ✅ | ✅ el-empty | ✅ | 优 |
| EngMmg.vue | ✅ v-loading | ✅ | ✅ el-empty | ✅ | 优 |
| GovernMeta.vue | ✅ v-loading | ✅ | ✅ el-empty | ✅ | 优 |
| DevSched.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |
| DevTag.vue | ✅ v-loading | ✅ | ✅ el-empty | ✅ | 优 |
| DevMl.vue | ✅ v-loading | ✅ | ✅ | ✅ | 优 |

### 5.2 组件结构问题

| 问题 | 影响页面 | 严重程度 | 说明 |
|---|---|---|---|
| Develop.vue 未使用 useApi | Develop.vue | P2 | 直接调用 `runJob`、`submitSchedule`，未走 useApi 三态包装 |
| Sql.vue 未使用 useApi | Sql.vue | P2 | 直接调用 `executeCrossSourceSql`，未走 useApi 三态包装 |
| 部分早期页面缺少 empty 状态 | Projects/Integrate/Develop/Sql/Govern/Standard/Quality/Lineage/Sec/Vector/Kb/Llmops/Gateway/Analyze/Ops/Account/Admin | P2 | 19 个早期页面（批次 1-3）仅有 loading/error 两态，无 empty 提示 |
| 批次 12+ 页面质量优于早期页面 | infra-*/engine-*/govern-meta/dev-* | — | 批次 12+ 新增页面三态处理完整，使用 el-empty 组件，质量明显优于早期页面 |

### 5.3 Composition API 规范检查

- **所有 34 个功能页面均使用 `<script setup lang="ts">`**：✅ 符合 Vue 3 Composition API 规范
- **响应式数据管理**：所有页面使用 `ref`/`reactive`/`computed`，✅ 符合规范
- **API 模块导入**：32/34 页面通过 `import * as xxxApi from '@/api/xxx'` 导入，2 个页面（Develop、Sql）直接导入函数
- **useApi composable 使用率**：32/34 页面使用 useApi（94%），2 个页面直接调用

## 6. 占位页面分析

### 6.1 重要发现

**任务描述与实际代码不符**：2：任务描述称有 16 个占位页面使用 Roadmap.vue，但实际路由文件 `frontend/src/router/index.ts` 中**所有路由均引用实际 Vue 组件**，无任何路由使用 Roadmap.vue。

Roadmap.vue 文件仍存在于 `frontend/src/views/Roadmap.vue`（24 行），但已成为**死代码**，未被任何路由引用。

### 6.2 批次 12 新增的 16 个页面（原占位页面已替换为实际组件）

#### 6.2.1 基础设施层（5 个）

| 路由 path | 组件 | 后端模块 | 后端状态 | 需暴露的 API 端点 |
|---|---|---|---|---|
| `/infra-machine` | InfraMachine.vue | infra-provider-xinchang/private/cloud | ✅ 存在 | 集群 CRUD（已有） |
| `/infra-k8s` | InfraK8s.vue | infra-orchestrator ClusterController | ✅ 存在 | 集群列表/详情（已有） |
| `/infra-net` | InfraNet.vue | infra-orchestrator | ❌ 缺失 | `/clusters/{env}/{cid}/network`、`/network/policies`、`/network/cnis` |
| `/infra-store` | InfraStore.vue | infra-orchestrator | ❌ 缺失 | `/clusters/{env}/{cid}/storage/classes`、`/storage/pvcs`、`/storage/usage` |
| `/infra-sched` | InfraSched.vue | infra-orchestrator | ❌ 缺失 | `/clusters/{env}/{cid}/hpa`、`/scale/events`、`/scale/summary` |

#### 6.2.2 引擎层（7 个）

| 路由 path | 组件 | 后端模块 | 后端状态 | 需暴露的 API 端点 |
|---|---|---|---|---|
| `/eng-storage;storage` | EngStorage.vue | sql-gateway VirtualTableController + flink-cdc MaterializedViewController | ✅ 存在 | 虚拟表 + 物化视图（已有） |
| `/eng-spark` | EngSpark.vue | stream-batch-scheduler JobController | ⚠️ 端口不匹配 | Spark 作业 CRUD（需 proxy 修正） |
| `/eng-flink` | EngFlink.vue | **缺失** | ❌ | `/flink/jobs/*`、checkpoints、savepoints、backpressure |
| `/eng-doris` | EngDoris.vue | **缺失** | ❌ | `/doris/nodes`、`/doris/databases`、`/doris/queries` |
| `/eng-kafka` | EngKafka.vue | **缺失** | ❌ | `/kafka/{cid}/brokers`、`/topics`、`/consumer-groups` |
| `/eng-iotdb` | EngIotdb.vue | **缺失** | ❌ | `/iotdb/{id}/storage-groups`、`/devices`、`/timeseries` |
| `/eng-mmg` | EngMmg.vue | sql-gateway VirtualTableController | ✅ 存在 | 多模型虚拟表（已有） |

#### 6.2.3 治理/开发层（4 个）

| 路由 path | 组件 | 后端模块 | 后端状态 | 需暴露的 API 端点 |
|---|---|---|---|---|
| `/govern-meta` | GovernMeta.vue | governance metadata-collector CollectorController | ✅ 存在 | 元数据采集（已有，需 proxy 修正） |
| `/dev-sched` | DevSched.vue | stream-batch-scheduler + encaps-layer | ✅ 存在 | DAG CRUD + 运行历史（已有） |
| `/dev-tag` | DevTag.vue | tag-engine TagController/ProfileController/AudienceController | ✅ 存在 | 标签/画像/受众（已有，需 proxy 修正） |
| `/dev-ml` | DevMl.vue | encaps-layer MLController | ✅ 存在 | ML 模型/推理服务（已有） |

## 7. 问题汇总与优先级

### P0 - 严重问题（页面无法加载/功能完全失效）

| 序号 | 问题 | 影响范围 | 根因 |
|---:|---|---|---|
| 1 | **`/api/v1/assets` proxy 路径冲突** | Govern.vue（资产治理 CRUD） | Vite proxy `/api/v1/assets` → :8092（real-time-pipeline），导致前端 `governance.ts` 调用 `/api/v1/assets` 被错误转发到 real-time-pipeline 而非 encaps-layer AssetController :8080 |
| 2 | **后端缺失 DevelopController** | Develop.vue（数据开发 Web IDE） | 前端 `develop.ts` 调用 `/develop/files`、`/develop/run`、`/develop/schedule`、`/develop/dag`，后端无对应 Controller |
| 3 | **后端缺失 LLMOpsController** | Llmops.vue（大模型运营） | 前端 `llmops.ts` 调用 `/llmops/models`、`/llmops/eval-metrics`、`/llmops/finetune`、`/llmops/human-eval`，后端无对应 Controller（注意：encaps-layer 有 MLController `/api/v1/ml`，但路径不同） |
| 4 | **后端缺失 GatewayController** | Gateway.vue（大模型网关） | 前端 `gateway.ts` 调用 `/gateway/stats`、`/gateway/keys`，后端无对应 Controller |

### P1 - 重要问题（功能不正常/部分功能缺失）

| 序号 | 问题 | 影响范围 | 根因 |
|---:|---|---|---|
| 5 | **缺少 `/api/v1/jobs` proxy** | JobManagement.vue、EngSpark.vue、EngFlink.vue | 前端调用 `/api/v1/jobs` 默认走 :8080，但 JobController 在 :8087 stream-batch-scheduler |
| 6 | **缺少 `/api/v1/metadata` proxy** | GovernMeta.vue | 前端调用 `/api/v1/metadata` 默认走 :8080，但 CollectorController 在 metadata-collector 独立服务 |
| 7 | **缺少 `/api/v1/tags`、`/profiles`、`/audiences` proxy** | DevTag.vue | 前端调用这些路径默认走 :8080，但 TagController 在 tag-engine 独立服务 |
| 8 | **缺少 `/api/v1/templates` proxy** | TemplateMarket.vue | 前端调用 `/api/v1/templates` 默认走 :8080，但 industry-templates 是 Python 服务 |
| 9 | **缺少 `/api/v1/business-lines` proxy** | BusinessPortal.vue | 前端调用 `/api/v1/business-lines` 默认走 :8080，但 business-portal 是 Python 服务 |
| 10 | **缺少 `/api/v1/apis` proxy** | APIMarket.vue | 前端调用 `/api/v1/apis` 默认走 :8080，但 open-api-catalog 是 Python 服务 |
| 11 | **缺少 `/api/v1/virtual-tables` proxy** | EngStorage.vue、EngMmg.vue | 前端调用 `/api/v1/virtual-tables` 默认走 :8080，但 VirtualTableController 在 sql-gateway :8088 |
| 12 | **缺少 `/api/materialized-views` proxy** | EngStorage.vue | 前端用 `baseURL: '/api'` 调用 `/materialized-views` 默认走 :8080，但 MaterializedViewController 在 flink-cdc 独立服务 |
| 13 | **缺少 `/api/v1/clusters` proxy** | InfraMachine/K8s/Net/Store/Sched.vue | 前端调用 `/api/v1/clusters` 默认走 :8080，但 ClusterController 在 infra-orchestrator 独立服务 |
| 14 | **缺少 `/api/v1/orchestrator` proxy** | DagVisualizer.vue | 前端调用 `/api/v1/orchestrator/dags` 默认走 :8080，但 OrchestratorController 在 rule-engine :8091 |
| 15 | **后端缺失 Flink/Doris/Kafka/IoTDB 专用 Controller** | EngFlink/Doris/Kafka/Iotdb.vue | 前端 `engine.ts` 调用 `/flink/jobs/*`、`/doris/*`、`/kafka/*`、`/iotdb/*`，后端无对应 Controller |
| 16 | **JobController 端点不完整** | JobManagement.vue | 前端 `job.ts` 调用列表/创建/cancel/logs/status，后端 JobController 仅有 GET/{id}、PUT/{id}、DELETE/{id}、POST/{id}/run |
| 17 | **QualityRuleController 端点不完整** | Quality.vue | 前端 `quality.ts` 调用列表/创建/check/summary，后端 QualityRuleController 仅有 GET/{id}、PUT/{id}、DELETE/{id} |
| 18 | **OrchestratorController 端点不完整** | DagVisualizer.vue | 前端 `orchestrator-viz.ts` 调用 18 个端点（含 thoughts/tool-calls/intervention/checkpoints/executions/replay），后端仅有 7 个基础端点 |
| 19 | **ProjectController 缺少子资源端点** | Projects.vue | 前端 `project.ts` 调用 `/{id}/datasets`、`/{id}/jobs`、`/{id}/members`，后端无这些子资源端点 |
| 20 | **AssetController 缺少扩展端点** | Govern.vue | 前端 `governance.ts` 调用 `/{id}/schema`、`/{id}/quality`、`/{id}/permissions`、`/{id}/apply-permission`，后端无这些端点 |
| 21 | **SecController 缺少 approvals 端点** | Sec.vue | 前端 `sec.ts` 调用 `/sec/approvals`、`/sec/approvals/{id}/approve`、`/sec/approvals/{id}/reject`，后端无这些端点 |
| 22 | **KnowledgeController 缺少扩展端点** | Kb.vue | 前端 `knowledge.ts` 调用 `/knowledge/rag-strategy`、`/knowledge/upload`，后端无这些端点 |
| 23 | **IntegrateController 缺少扩展端点** | Integrate.vue | 前端 `integrate.ts` 调用 `/integrate/connectors`、`/integrate/tasks/{id}/run`、`/integrate/tasks/{id}/stop`，后端无这些端点 |
| 24 | **StandardController 缺少 summary 端点** | Standard.vue | 前端 `standard.ts` 调用 `/standards/summary`，后端无此端点 |
| 25 | **ops.ts 多个端点缺失** | Ops.vue | 前端 `ops.ts` 调用 `/ops/overview`、`/ops/jobs`、`/ops/alerts`、`/ops/alerts/{id}/handle`、`/ops/jobs/{id}/logs`，后端 query-api 仅有 `/ops/health/overview` |
| 26 | **ai-assistant 缺少扩展端点** | AiAssistant.vue | 前端调用 `sessions/{id}/pin`、`sessions/{id}/rename`、`messages/{id}/feedback`、`example-prompts`、`superset/datasources`，后端 Go 服务无这些端点 |
| 27 | **search.ts 缺少扩展端点** | SearchPortal.vue | 前端调用 `/search/export`、`/search/history/clear`、`/search/history/{id}/delete`，后端无这些端点 |
| 28 | **BiDashboardController 端点不完整** | Analyze.vue | 前端 `analyze.ts` 调用列表/创建/realtime，后端 BiDashboardController 仅有 GET/{id}、PUT/{id}、DELETE/{id} |
| 29 | **TenantController/WorkspaceController 缺少 /all 端点** | TenantManagement/WorkspaceManagement/QuotaManagement.vue | 前端调用 `/tenants/all`、`/workspaces/all`（用于下拉选择），后端无这些端点 |
| 30 | **DataSourceController 缺少 test 端点** | DataSourceManagement.vue | 前端调用 `/datasources/{id}/test`，后端无此端点 |
| 31 | **ClusterController 缺少 nodes/components/network/storage/hpa 子资源** | InfraK8s/Net/Store/Sched.vue | 前端 `infra.ts` 调用 40 个函数，后端 ClusterController 仅有 8 个基础端点 |

### P2 - 改进建议（体验优化）

| 序号 | 问题 | 影响范围 | 建议 |
|---:|---|---|---|
| 32 | **Roadmap.vue 死代码** | — | 删除 `frontend/src/views/Roadmap.vue`（24 行），未被任何路由引用 |
| 33 | **部分路由缺少 name 属性** | 16 个批次 12+ 路由 | 为 infra-*/eng-*/govern-meta/dev-*/ops-tpl 路由补充 `name` 字段 |
| 34 | **早期路由缺少 meta** | 19 个批次 1-3 路由 | 为 dashboard~admin 路由补充 `meta.title` 与 `meta.icon` |
| 35 | **Develop.vue/Sql.vue 未使用 useApi** | Develop.vue、Sql.vue | 改用 useApi 包装 API 调用，统一三态处理 |
| 36 | **19 个早期页面缺少 empty 状态** | 批次 1-3 页面 | 补充< el-empty 组件或空状态提示 |
| 37 | **`/lineage` proxy 未带 `/api/v1` 前缀** | Lineage.vue/DataLineage.vue | 评估统一为 `/api/v1/lineage` proxy，避免 baseURL 覆盖 |
| 38 | **`sqlworkbench.ts` listEngines 方法用 POST** | SqlWorkbench.vue | 后端 SqlGatewayController `GET/engines`，前端应用 GET |
| 39 | **Hash 路由模式** | 全局 | 评估切换为 History 模式，利于 SEO 与 URL 美观 |
| 40 | **鉴权白名单仅 `/login`** | 全局 | 如有公开页（健康检查、文档），加入 PUBLIC_PATHS |

## 8. 修复建议

### 8.1 P0 修复（紧急）

#### 8.1.1 修复 `/api/v1/assets` 路径冲突

**方案 A（推荐）**：修改前端 `governance.ts` 的 BASE 路径，避免与 `assetMarket.ts` 冲突

```typescript
// frontend/src/api/governance.ts
const BASE = '/governance/assets'  // 原 '/assets'
```

后端 `AssetController` 的 `@RequestMapping` 同步改为 `/api/v1/governance/assets`。

**方案 B**：修改 Vite proxy，将 `/api/v1/assets` 保留给 asset-exchange，新增 `/api/v1/governance/assets` → :8080

#### 8.1.2 新增 DevelopController

在 `platform/encaps-layer` 新增 `DevelopController`，实现：
- `GET /api/v1/develop/files` — 文件树
- `GET /apiF1/develop/files/content` — 读文件
- `POST /api/v1/develop/run` — 运行作业
- `POST /api/v1/develop/schedule` — 提交调度
- `GET /api/v1/develop/dag` — 任务 DAG

#### 8.1.3 新增 LLMOpsController

在 `platform/encaps-layer` 新增 `LLMOpsController`（或复用 MLController 并扩展路径），实现：
- `GET /api/v1/llmops/models`
- `GET /api/v1/llmops/eval-metrics`
- `POST /api/v1/llmops/finetune`
- `POST /api/v1/llmops/human-eval`

#### 8.1.4 新增 GatewayController

在 `platform/encaps-layer` 新增 `GatewayController`，实现：
- `GET /api/v1/gateway/stats`
- `GET /api/v1/gateway/keys`
- `POST /api/v1/gateway/keys`
- `PUT /api/v1/gateway/keys/{id}`
- `DELETE /api/v1/gateway/keys/{id}`

### 8.2 P1 修复（重要）

#### 8.2.1 补充 Vite proxy 条目

在 `frontend/vite.config.ts` 的 `server.proxy` 中新增：

```typescript
'/api/v1/jobs': {
  target2 target: process.env.VITE_STREAM_BATCH_TARGET || 'http://127.0.0.1:8087',
  changeOrigin: true
},
'/api/v1/metadata': {
  target: process.env.VITE_METADATA_TARGET || 'http://127.0.0.1:8093',
  changeOrigin: true
},
'/api/v1/tags': {
  target: process.env.VITE_TAG_ENGINE_TARGET || 'http://127.0.0.1:8094',
  changeOrigin: true
},
'/api/v1/profiles': {
  target: process.env.VITE_TAG_ENGINE_TARGET || 'http://127.0.0.1:8094',
  changeOrigin: true
},
'/api/v1/audiences': {
  target: process.env.VITE_TAG_ENGINE_TARGET || 'http://127.0.0.1:8094',
  changeOrigin: true
},
'/api/v1/templates': {
  target: process.env.VITE_TEMPLATES_TARGET || '"http://127.0.0.1:8095',
  changeOrigin: true
},
'/api/v1/business-lines': {
  target: process.env.VITE_BUSINESS_PORTAL_TARGET || 'http://127.0.0.1:8096',
  changeOrigin: true
},
'/api/v1/apis': {
  target: process.env.VITE_API_CATALOG_TARGET || 'http://127.0.0.1:8097',
  changeOrigin: true
},
'/api/v1/virtual-tables': {
  target: process.env.VITE_SQL_GATEWAY_TARGET || 'http://127.0.0.1:8088',
  changeOrigin: true
},
'/api/materialized-views': {
  target: process.env.VITE_FLINK_CDC_TARGET || 'http://127.0.0.1:8098',
  changeOrigin: true
},
'/api/v1/clusters': {
  target: process.env.VITE_INFRA_ORCHESTRATOR_TARGET || 'http://127.0.0.1:8099',
  changeOrigin: true
},
'/api/v1/orchestrator': {
  target: process.env.VITE_RULE_ENGINE_TARGET || 'http://127.0.0.1:8091',
  changeOrigin: true
}
```

#### 8.2.2 补充后端 Controller 缺失端点

按 P1 问题清单（序号 15-31），在各后端 Controller 中补充前端调用但后端缺失的端点。优先级：
1. JobController 补充列表/创建/cancel/logs/status
2. QualityRuleController 补充列表/创建/check/summary
3. ProjectController 补充 datasets/jobs/members 子资源
4. AssetController 补充 schema/quality/permissions/apply-permission
5. ClusterController 补充 nodes/components/network/storage/hpa 子资源
6. OrchestratorController 补充 thoughts/tool-calls/intervention/checkpoints/executions/replay
7. 新增 Flink/Doris/Kafka/IoTDB 专用 Controller

### 8.3 P2 修复（优化）

1. 删除 `frontend/src/views/Roadmap.vue` 死代码
2. 为批次 12+ 路由补充 `name` 字段
3. 为批次# 批次 1-3 路由补充 `meta.title` 与 `meta.icon`
4. Develop.vue/Sql.vue 改用 useApi 包装
5. 19 个早期页面补充 empty 状态
6. 修正 `sqlworkbench.ts` listEngines 方法为 GET
7. 评估切换 History 路由模式

## 9. 审计结论

### 9.1 总体评价

- **前端质量**：批次 4+ 页面（TenantManagement、ClusterOverview 等 15 个）质量优秀，三态处理完整，useApi 使用规范；批次 1-3 页面（19 个）质量良好但缺少 empty 状态。
- **后端覆盖**：encaps-layer 作为统一 API 网关，覆盖了大部分前端模块，但仍有 4 个 Controller 完全缺失（Develop/LLMOps/Gateway + 引擎专用）。
- **Vite proxy**：11 个 proxy 条目覆盖! 覆盖了主要后端服务，但缺少 12 个 proxy 条目导致多个页面请求被错误转发到 :8080。
- **API 对接**：约 270 个前端 API 函数中，约 80 个（30%）调用的后端端点缺失或不匹配。

### 9.2 关键问题数量

| 优先级 | 数量 | 说明 |
|---|---:|---|
| P0 严重 | 4 | 路径冲突 + 3 个后端 Controller 缺失 |
| P1 重要 | 27 | proxy 缺失 + 端点不完整 + 引擎 Controller 缺失 |
| P2 改进 | 9 | 死代码 + 路由配置 + 三态处理 + 体验优化 |
| **合计** | **40** | — |

### 9.3 修复优先级建议

1. **立即修复**（P0）：4 个问题，预计 2-3 人日
2. **本周修复**（P1 proxy 相关）：12 个 proxy 缺失问题，预计 1 人日（仅改 vite.config.ts）
3. **下周修复**（P1 端点补充）：15 个端点不完整问题，预计 5-10 人日
4. **迭代优化**（P2）：9 个改进建议，预计 2-3 人日

---

**审计报告生成完毕。审计人：前端审计工程师。审计时间：2026-08-16。**
