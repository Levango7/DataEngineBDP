## 背景：扩栈①的裁决与"副产品"

并行会话（pi）的 #385 把 5 个栈外服务进 nightly compose 并逐条修掉 4 个真实契约/路径问题，但**两次全量 dispatch 都在栈启动阶段失败**（run `37919622584` 等），结论：**本仓 nightly 的标准 runner 承载不了 15+5 服务**（我本机 Docker 29.8.1 实测同类三服务本身全 healthy ⇒ 障碍是 CI 资源，不是服务/契约）。#385 因此被作者关闭。

**排查产出的真问题与断言策略不依赖"服务进栈"，本 PR 独立落地：**

## 改动

**产品/契约修复（4 处，含测试）**

| 文件 | 问题 | 修法 |
|---|---|---|
| `platform/ai-assistant/internal/api/handler.go` (+`auth_test.go`) | `/sessions` 返回 `{"sessions":[...]}`，而前端契约是 `listSessions(): ChatSession[]` ⇒ 页面 `[...sessions]` 抛 not iterable → ErrorBoundary | 返回**裸数组** + 回归测试 `TestListSessions_ReturnsBareArray` |
| `platform/observability/query-api/.../cluster.go` (+`cluster_test.go`) | 无 k8s 的栈里 `/api/v1/cluster/*` 因 kubeconfig 缺失直接不可用 | 新增 **显式** `QUERY_API_K8S_MOCK=true` 空数据 mock（默认仍走真实 kubeconfig，不静默 mock）+ 测试 |
| `frontend/vite.config.ts` | `/lineage` 转发未 strip：服务已移除 context-path、实供 `/api/v1/lineage/...` ⇒ 转发成双层路径 404 | 代理加 `rewrite` strip 首段 `/lineage`（前端契约保持 `/lineage/api/v1/...`） |
| `frontend/tests/e2e/vector.spec.ts` | 断言 `Array.isArray(json.data)`，而 vector-engine 返回**裸数组** | 断言改 `Array.isArray(json)`（对齐 `api/vector.ts` 契约） |

**断言策略（与扩栈解耦，11 处）**

- 11 个 spec 的栈外 API 断言：无条件 `test.skip(true,…)` → **"响应 404 才跳过"**。服务一旦可用（本地 dev / 未来 runner 扩容 / 生产演练）自动恢复断言；**半坏（500/契约不符）不会被静默吞掉**。
- `infra-machine.spec.ts` 例外**保留 skip**，理由按实跑真因重写：`GET /api/v1/clusters/{env}` 对不可达/禁用 provider 抛错（→400/异常）而聚合 `/clusters` 会逐 env catch ⇒ 单环境列表的容错语义需产品决策。

## 验证

- `go test ./internal/api/...`（ai-assistant）、`go test ./internal/handler/...`（query-api）均 **ok**
- `npx playwright test --list` → `324 tests in 51 files`（全部 spec 可解析）
- `prettier --check` / `eslint` 对改动文件干净
- 台账 #57 行已记录：扩栈①的实测否决 + 本批副产品清单

## 不含

- compose 扩栈服务块 / nightly 扩栈步 / 8 例解 skip（被 runner 资源否决）——若未来 runner 规格变化，只需重加 compose+workflow，**本 PR 的条件式断言会被自动"激活"，无需再改 spec**。
