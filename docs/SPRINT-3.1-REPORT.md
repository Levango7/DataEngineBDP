# Sprint 3.1 执行报告（Phase 3 联调）

**日期**：2026-09-03
**范围**：Phase 3「联调」首个 Sprint——栈真实化 + 页面覆盖

## 1. 目标与决策

用户确认范围：**栈真实化 + 页面覆盖**（优先补齐 nightly compose 栈 + Playwright 页面覆盖），Python 四服务入栈。

前置发现（3.1.0 调研）：
- nightly 栈 14 服务 vs vite 代理**不匹配**：Sprint 2.2 新增 12 个细粒度代理指向的原生端口（8081/8083/8082 等）在 compose 中是宿主机映射端口（18090/18083/18082），nightly Playwright 会 404；
- vite 代理**默认端口多处错误**（历史单机逐个跑从未暴露）；
- P2 三项 e2e 标注 skip「Phase 3 实现」；
- e2e/conftest 与 compose 端口规划冲突（karmada/observability 预留 18090/18093）。

## 2. 交付物

### 2.1 vite.config.ts 端口修正（8 处真实错误）

| 代理前缀 | 原默认（错误） | 修正为（真实） |
|---------|--------------|--------------|
| /api/v1/metadata | :8093 | **:8084**（metadata-collector） |
| /api/v1/tags·profiles·audiences | :8094 | **:8080**（tag-engine，与 encaps-layer 同端口需 env 错开） |
| /api/v1/templates | :8095 | **:8091**（industry-templates） |
| /api/v1/business-lines | :8096 | **:8088**（business-portal） |
| /api/v1/apis | :8097 | **:8090**（open-api-catalog，与 query-api 同端口需 env 错开） |
| /api/v1/orchestrator·quality | :8091 | **:8083**（rule-engine） |
| /api/v1/clusters | :8099 | **:8085**（infra-orchestrator） |
| /lineage | :8089 | **:8086**（lineage-analyzer） |

另：新增 `/api/v1/models` 代理（dev-ml.ts 的 /models/models，归属 Python ml-platform/llmops 域，Sprint 2.2 遗留缺口）；`server.host='127.0.0.1'` 强制 IPv4 绑定。

### 2.2 compose 补 Python 四服务（18 服务）

| 服务 | 容器端口 | 宿主机 | 前端页面 |
|------|---------|--------|---------|
| asset-exchange | 8087 | **18094** | 数据资产流通 /ops-flow |
| business-portal | 8088 | **18093** | 业务线门户 /ops-portal |
| open-api-catalog | 8090 | **18095** | 开放 API /ops-api |
| industry-templates | 8091 | **18096** | 行业应用模板 /ops-tpl |

### 2.3 Playwright 栈适配 + 5 个新 spec（19 用例）

playwright.config.ts：webServer 注入全套 `VITE_*_TARGET`，**栈外服务（encaps-data/gateway/vector/ai/stream-batch/models）统一兜底 encaps-layer 18080**（stub Controller 提供契约响应）。

新 spec（全部真实后端联调通过）：
- encaps-tenant.spec.ts（租户管理/账户/运营后台，JWT 保护含 401 用例）
- asset-market.spec.ts / business-portal.spec.ts / api-market.spec.ts / template-market.spec.ts（Python 域匿名可达）

### 2.4 client.ts X-Tenant-Id 注入

business-portal 在 AUTH_MODE=none 下强制从 `X-Tenant-Id` header 读租户（与 asset-exchange 匿名放行行为不一致）。修复：client.ts 拦截器对 `/business-lines` 域条件注入 X-Tenant-Id（取登录 user.tenantId，兜底 platform-admin），不影响 encaps-layer 多租户校验。

### 2.5 e2e/conftest.py 端口卫生

BASE_URLS/HEALTH_PATHS 新增 encaps_tenant(18090)/business_portal(18093)，karmada/observability 原预留端口让位并注释说明（未入栈服务待入栈时重分配）。compose ↔ conftest 宿主机端口单一真相源。

### 2.6 gen-api-contract.py 两处 bug 修复

1. **PY_ROUTER_RE/PY_ROUTE_RE 行首锚定**：registry 在工厂函数内定义 router（缩进），`^\w` 漏匹配导致 /registry 前缀未扫描 → 允许行首空白；
2. **prefix 双重拼接**：registry 的 prefix 已含 `/api/v1`，`PY_API_PREFIX + prefix` 得 `/api/v1/api/v1/registry` → prefix 以 `/api/` 开头时直接用。

## 3. 联调发现的问题（真实环境验证收获）

| # | 问题 | 根因 | 处置 |
|---|------|------|------|
| 1 | Playwright webServer 120s 超时 | Windows 上 vite 默认绑 `::1`（IPv6），playwright 用 127.0.0.1 探测失败 | vite `server.host='127.0.0.1'` |
| 2 | business-lines 页面/API 400「缺少租户身份」 | business-portal AUTH_MODE=none 下仍强制 X-Tenant-Id | client.ts 条件注入 + spec 带 header |
| 3 | asset-exchange 本地进程被杀 | `Select-Object -First N` 截断管道致 SIGPIPE | 本地启动改 Start-Process 日志重定向 |
| 4 | /api/v1/subscriptions 代理歧义 | asset-exchange 与 open-api-catalog 共享该前缀，vite 无法分流 | **记录后续**（api-market spec 移除订阅用例） |
| 5 | asset-exchange 无 GET /subscriptions 根路由 | 后端仅子路由（/{id}/delivery-status、/{id}/billing） | **记录后续**（前端 listSubscriptions 404，spec 移除该用例） |
| 6 | 契约 3 条未匹配 | 扫描器 2 bug（见 2.6） | 修复后恢复 258/258 |

## 4. 验证结果（全绿）

| 检查 | 结果 |
|------|------|
| Playwright 5 新 spec | ✅ **19/19 通过**（真实 2 Java + 4 Python 服务联调） |
| 契约生成 --check | ✅ 258/258，0 未匹配（修复 2 bug 后恢复） |
| 路由冲突扫描 | ✅ exit 0，413 端点，豁免 20 |
| vite 代理校验 | ✅ 38 条代理，249 调用路径全分流 |
| Vitest | ✅ 203/203（client.ts 拦截器改动无回归） |
| ESLint | ✅ 0 errors（3 个可 --fix 的 prettier warning） |
| vite build | ✅ 39s |

## 5. 对 Sprint 3.2 的输入

1. **/api/v1/subscriptions 代理歧义**：两个服务共享前缀，需定归属（建议 open-api-catalog 订阅审批走独立前缀或 asset-exchange 与 open-api-catalog 二选一承接）；
2. **asset-exchange 补 GET /subscriptions 根路由**（对齐 assetMarket.ts listSubscriptions）；
3. **P2 三项 e2e 取消 skip**：数据虚拟化（sql-gateway 承载）、能源/政务模板（industry-templates 已入栈）可落地；
4. **Playwright 全量扩面**：现有 6 路由 + 新 5 = 11 路由已覆盖，剩余页面（ops/search/standard/quality 等）可分批补 spec；
5. 本地联调经验固化：Python 服务启动勿用管道截断；vite 绑定 IPv4；契约扫描器对缩进 router/自含前缀的鲁棒性已验证。
