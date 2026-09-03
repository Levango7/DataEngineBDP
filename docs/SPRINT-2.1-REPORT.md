# Sprint 2.1 执行报告

**日期**：2026-09-02
**范围**：前后端 API 契约打通（Phase 2「打通」首个 Sprint）

## 1. 调研结论

| 维度 | 结论 |
|------|------|
| `/api/v1/tenants` 双 Controller | encaps-layer(8080) 与 encaps-tenant(8081) 各注册同前缀 5 条路由；生产为**跨进程独立部署**（不同 JVM，永不相遇），当前架构无同 context 冲突；风险仅存在于未来模块聚合/单体化场景 |
| 路由冲突全量扫描 | 413 个 (verb, path) 端点：12 个微服务各自注册 `/api/v1/health`（预期）；唯一同前缀复用即上述 tenants 系列 5 条 |
| 前后端 API 契约 | 前端 36 个 api/*.ts 文件、260 条调用；后端 64 个类级 `@RequestMapping` 前缀；**匹配 211 / 未匹配 49**（81% 已打通） |
| 守卫机制 | encaps-layer TenantController 已有 `@ConditionalOnProperty(app.tenant.controller.enabled)`（上一阶段加入），本 Sprint 补验证测试 |

## 2. 交付物

| 任务 | 文件 | 变更 |
|------|------|------|
| 2.1.1 | scripts/check-api-route-conflict.py | 路由冲突静态扫描器：`KNOWN_CROSS_PROCESS_ROUTES` 豁免清单（跨进程同前缀白名单，`::warning::` 报告不阻断），未知冲突退出码 1 阻断 |
| 2.1.2 | scripts/gen-api-contract.py | 契约生成器：解析 `const BASE` 常量 + 模板字符串 + `${BASE}` 替换；`--check` 模式；强制 LF 输出（跨平台 CI 稳定） |
| 2.1.2 | docs/api-contract.md | 自动生成契约文档：36 个前端文件 ↔ 64 个后端前缀对照表，每条调用标注可路由性 |
| 2.1.3 | platform/encaps-layer/.../TenantControllerRouteTest.java | 守卫验证测试（@Nested × 2 个 Spring context）：默认启用 → handler 已注册；`enabled=false` → handler 不注册 |
| 2.1.4 | .github/workflows/ci.yml | bash-check job 新增 2 个门禁 step：路由冲突扫描（阻断）+ 契约生成漂移校验（阻断） |
| 2.1.4 | .gitattributes | `docs/api-contract.md` 与两个 scripts/*.py 强制 LF |

## 3. 关键发现

### 3.1 49 条未匹配调用的根因分类（Sprint 2.x 待收敛清单）

**A. 后端 Controller 缺失（30 条，前端先行、后端未建）**

| 前缀 | 条数 | 归属域 | 备注 |
|------|------|--------|------|
| `/ai-assistant/*` | 13 | 智能层 | 无任何 Controller；含会话管理/反馈/示例 prompt |
| `/business-lines/*` | 7 | 商用运营 | 无 Controller、无 `BusinessLine` 类 |
| `/subscriptions/*` | 5 | API 市场 | apiCatalog.ts 的订阅审批流，无后端 |
| `/ops/*` | 5 | 产品运营 | Sprint 1.3 刚挂的「运维中心」菜单，后端空缺 |
| `/asset-subscriptions/*` | 2 | 资产市场 | 订阅交付/计费，无后端 |
| `/vector/*` | 2 | 智能层 | 向量检索 UI 已备，无 REST 层 |

**B. 前后端路径不匹配（12 条，命名不一致需对齐）**

| 前端调用 | 后端实际 | 备注 |
|----------|----------|------|
| `/assets`（4 条） | `/api/v1/governance/assets` | 后端已注释说明：避免与 Python asset-exchange 的 `/api/v1/assets` 冲突而改前缀 |
| `/cluster/*`（4 条） | `/api/v1/clusters`（复数） | 且现有 clusters 是**供应编排**（create/scale），非前端要的**监控**（nodes/pods/components） |
| `/materialized-views/*`（4 条） | `/api/materialized-views` | flink-cdc 模块，前缀少了 `/v1` |

**C. 误报（1 条）**：streamBatch.ts 中 `get(url)` 直接传变量，非 API 路径字面量。

**D. 直连外部（其余）**：`/ai-assistant` 部分端点或走流式直连（待 Sprint 2.2 确认网关路由）。

### 3.2 跨进程同前缀的豁免设计

TenantController 双注册属**已知跨进程复用**，处理方式：
1. 脚本白名单 `KNOWN_CROSS_PROCESS_ROUTES` 精确匹配 (verb, path)，输出 `::warning::` 可见但不阻断 CI；
2. encaps-layer 侧守卫 `@ConditionalOnProperty` 保证未来若合并部署可配置关闭；
3. 新增未知冲突仍会阻断 CI，新增豁免必须改脚本留痕（可审计）。

### 3.3 测试技术要点（Spring Boot 4 / Spring 7 适配）

- `RequestMappingInfo.getPatternsCondition()` 在 PathPatternParser（Boot 4 默认）下返回 null → 必须用 `getPathPatternsCondition().getPatternValues()`；
- actuator 引入第二个 `RequestMappingHandlerMapping` bean（`controllerEndpointHandlerMapping`）→ 注入需 `@Qualifier("requestMappingHandlerMapping")`；
- 无 handler 的路径在 MockMvc 全链路下会走 `NoResourceFoundException`（Boot 4 行为）被 GlobalExceptionHandler 兜底成 500，不适合断言"路由不存在" → 改用 HandlerMapping 直查法。
- **顺带发现的独立问题**：GlobalExceptionHandler 未把 `NoResourceFoundException` 映射为 404（未知路径返回 500）。建议 Sprint 2.2 修复（预期外路径应 404 而非 500）。

## 4. 验证

| 检查 | 结果 |
|------|------|
| 冲突扫描（豁免版） | `✓ 413 个端点无未知冲突；豁免 5 处；/api/v1/health 12 微服务注册`，退出码 0 |
| 契约生成 | `匹配 211 / 未匹配 49`，两次生成结果稳定（CI 漂移校验可用） |
| TenantControllerRouteTest | `Tests run: 2, Failures: 0, Errors: 0`（默认启用 ✓ / 显式关闭 ✓），`mvn test` 退出码 0 |
| ci.yml | `yaml.safe_load` 解析通过；bash-check job 新增 2 step 就位 |
| 行尾 | 契约文档 LF 输出 + .gitattributes 三条 eol=lf 规则，跨平台漂移校验稳定 |

## 5. 对 Sprint 2.2 的输入

1. **收敛 B 类（12 条）**：对齐 `/assets` → `/api/v1/governance/assets`（前端改）、`/materialized-views` 加 `/api/v1`（后端改或网关 rewrite）、`/cluster` 监控端点新建；
2. **补建 A 类高优先**：`/ops`（运维中心菜单已上线无后端）、`/business-lines`（商用运营主链路）建议优先；`/ai-assistant` 13 条需先定架构（独立服务 or 挂 encaps-layer）；
3. **修复 GlobalExceptionHandler 的 404 映射**（见 3.3）；
4. encaps-tenant 未纳入 docker-compose/集成测试（docker-compose.yml 只起 encaps-layer 18080）—— 若该服务为交付物，需补部署与集成测试；
5. 契约门禁落地后，前后端任何一方改路由都会被 CI 漂移校验拦截，倒逼同步更新契约文档。
