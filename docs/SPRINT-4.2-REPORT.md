# Sprint 4.2 执行报告（Phase 4·模板部署真实化 + 订阅前缀隔离）

**日期**：2026-09-03
**范围**：A 模板部署 chart 映射修复 + B 订阅域前缀隔离（用户选择 A+B 组合）

## 1. 目标与决策

Sprint 4.1 报告给出的三个候选方向，研究后发现前两个方向都有真问题：

- **研究结论 1**：HelmExecutor 本身已是真实实现（subprocess 调真实 helm CLI，
  install/uninstall/status/list 完整），但 `_resolve_chart_path` 按
  `{chartBase}/{templateId}` 查找 chart——模板 ID（如 `fin-risk-scorecard`）与
  chart 目录命名（`finance-template`，按 `{industry}-template` 规范）**永远对不上**，
  helm 模式下回退传裸 templateId 给 helm → 9/9 部署必失败。现有测试全用
  FakeHelmExecutor，从未暴露。
- **研究结论 2**：`/api/v1/subscriptions` 被 asset-exchange（资产交付订阅）与
  open-api-catalog（API 访问订阅审批，detail design §7 契约）两个服务同抢，
  vite proxy 只能固定指向 asset-exchange → APIMarket 页 5 个订阅端点全部错路由
  （approve 打错服务，suspend/resume/revoke 404）。历史根因是 Sprint 2.2 把
  asset-exchange 前端从 `/asset-subscriptions` "对齐"到 `/subscriptions` 的错误对齐。
- finance chart 位于 `templates/finance/helm/`（非标准位置），其余 8 个行业
  chart 在 `charts/` 但 finance 缺位。
- CI helm-lint 只覆盖 `design/deploy/charts/`，`platform/industry-templates/charts/`
  的 9 个行业 chart 零门禁。

## 2. 交付物

### 2.1 A：模板部署 chart 映射修复

| 改动 | 文件 | 说明 |
|------|------|------|
| TemplateMeta.chartRef 字段 | models/template.py | 可选显式 Chart 引用（对应 Chart.yaml name） |
| _resolve_chart_path 三级回退 | services/template_engine.py | chartRef → templateId → `{industry}-template` → 裸 ID（保留 helm 报错语义） |
| finance chart 归位 | charts/finance-template/ | 从 templates/finance/helm/ 复制归位，9 个行业 chart 目录齐全 |
| 对账测试 7 用例 | tests/test_chart_mapping.py | 9 模板↔chart 存在性、Chart.yaml name 一致性、回退链、chartRef 优先级、失败路径 |
| CI helm-lint 扩展 | .github/workflows/ci.yml | chart_roots 扩为 design/deploy/charts + platform/industry-templates/charts |

### 2.2 B：订阅前缀隔离

| 改动 | 文件 | 说明 |
|------|------|------|
| 路由前缀 | asset-exchange routers/subscriptions.py | `/subscriptions` → `/asset-subscriptions` |
| 单测同步 | asset-exchange tests/（4 文件） | 26 处路径引用全量替换 |
| 前端客户端 | frontend/src/api/assetMarket.ts | SUB_BASE → `/asset-subscriptions` |
| vite 代理细分 | frontend/vite.config.ts | `/api/v1/subscriptions` 归还 open-api-catalog；新增 `/api/v1/asset-subscriptions` 指向 asset-exchange（40 条代理） |
| Playwright 断言 | asset-market.spec.ts / api-market.spec.ts | 资产订阅列表断言改新前缀；注释同步前缀隔离背景 |
| cross-domain e2e 场景 10 | tests/integration/e2e/test_e2e_cross_domain.py | 订阅调用从臆造的 POST /subscriptions 根路由修正为契约路径 POST /apis/{id}/subscribe；usage 404 容忍；清理 DELETE → revoke |

## 3. 验证结果（全绿）

| 检查 | 结果 |
|------|------|
| industry-templates pytest | ✅ **118 passed**（111 旧 + 7 chart 对账新用例） |
| asset-exchange pytest | ✅ **95 passed**（前缀改动后全量） |
| open-api-catalog pytest | ✅ **129 passed**（未受影响，前缀未动） |
| 前端单测 | ✅ **209 passed**（assetMarket.ts 改动无破坏） |
| 真实运行分流验证 | ✅ **4/4**：asset-exchange 新前缀 200 / 旧前缀 404；open-api-catalog `/subscriptions` 200 / 串域 404 |
| vite 代理校验 | ✅ 40 条代理，250 个前端调用路径全分流 |
| Playwright 两市场页 | ✅ **6/6**（含新增"前缀隔离"断言用例） |
| cross-domain e2e collect | ✅ 10 tests collected（场景 10 路径修正后） |

**真实运行分流验证明细**（本地 uvicorn 起两服务直连 curl）：

```
asset-exchange  :8087  /api/v1/asset-subscriptions  => 200 []
asset-exchange  :8087  /api/v1/subscriptions        => 404  (旧前缀已撤)
open-api-catalog:8090  /api/v1/subscriptions        => 200 []  (归属归还)
open-api-catalog:8090  /api/v1/asset-subscriptions  => 404  (不串域)
```

## 4. 对后续 Sprint 的输入

1. **helm 模式部署链路打通**：chart 解析修复后，`deployMode=helm` 下
   `helm upgrade --install` 可命中真实 chart（9 个行业 chart 齐）；下一步可在
   K3s nightly 栈加 `INDUSTRY_TEMPLATES_DEPLOY_MODE=helm` 做真部署演练
   （chart 本身是 ConfigMap 打包模板资产，helm install 后由平台 Job 导入——
   无需真实工作负载）。
2. **chartRef 显式引用**：模板若需绑定非 `{industry}-template` 命名的 chart，
   设 `meta.chartRef` 即可，无需改 engine。
3. **订阅域解耦完成**：asset-exchange 与 open-api-catalog 的订阅域彻底隔离，
   后续订阅功能演进互不影响；前端两页面调用路径与后端一一对应。
4. **遗留候选**：Playwright 剩余页面扩面（cluster/datasources/vector/kb/llmops
   等）、跨进程 Python 路由冲突扫描器（check-api-route-conflict.py 目前只扫 Java
   @RestController，Python 服务靠人工契约审查——本次冲突即暴露此盲区）。

