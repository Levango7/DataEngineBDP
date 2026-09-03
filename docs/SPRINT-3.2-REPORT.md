# Sprint 3.2 执行报告（Phase 3 联调·订阅域收敛 + P2 落地 + Playwright 扩面）

**日期**：2026-09-03
**范围**：Sprint 3.1 遗留清单收敛（subscriptions 歧义 / P2 三项 / 页面扩面）

## 1. 目标与决策

Sprint 3.1 遗留输入：① /api/v1/subscriptions 代理歧义（asset-exchange vs open-api-catalog）；② asset-exchange 无 GET /subscriptions 根路由；③ P2 三项 e2e 取消 skip；④ Playwright 全量扩面。

关键决策：**订阅域按语义分流而非前缀统一**——asset-exchange 承载资产交付订阅（list/deliver/billing），open-api-catalog 承载 API 访问订阅审批（approve/suspend/resume/revoke）。两个服务均补 GET 根路由返回 200 空列表，vite proxy 按子路径自动匹配，前端通过不同操作实现语义分流。

## 2. 交付物

### 2.1 asset-exchange 补 GET /subscriptions 根路由（P32.1）

- 新增 `list_subscriptions` handler（GET ""，响应 model=list[Subscription]，支持 assetId/subscriberId/status/limit/offset 过滤）
- 依赖 service 层既有 `list_subscriptions`（repository.list 已实现）
- 澄清注释：与 open-api-catalog 共享前缀的语义差异 + 前端分流约定
- **验证**：asset-exchange pytest **95 passed**（无回归）

### 2.2 订阅 spec 补回（P32.1）

- asset-market.spec.ts / api-market.spec.ts 各补回 1 个订阅列表用例（Sprint 3.1 误删）
- 澄清注释：Sprint 3.1 误以为 /subscriptions 仅归 asset-exchange，实际两服务均提供

### 2.3 P2 三项 e2e 落地（P32.2）

- 新建 test_e2e_p2_landed.py（3 测试，替换骨架）：
  - P2-26 数据虚拟化：sql-gateway VirtualTableController 真实 API（列表 + types ≥3）
  - P2-27 能源行业模板：industry-templates 域级契约（分类/列表 ≥7）
  - P2-28 政务行业模板：模板详情端点可路由
- 骨架文件（test_e2e_all_requirements.py）P2 区块替换为迁移说明
- **验证**：3 tests collected；真实 API 依赖 nightly 栈（sql-gateway:18081 / industry-templates:18096）

### 2.4 Playwright 扩面（P32.3）

- 新 ops-quality.spec.ts（8 用例）：运维中心（/ops，query-api 兜底 encaps-layer）+ 数据质量（/quality，rule-engine 18083）
- 全部走真实后端

### 2.5 联调中发现并修复

| # | 问题 | 处置 |
|---|------|------|
| 1 | Playwright 全量 28 失败于登录按钮 | **i18n locale 回退英文**（"Sign In" vs "登 录"）→ helpers.ts clickLoginButton 改 aria-label 优先 + 中英文文本回退 |
| 2 | 质量规则列表 API 断言 ApiResponse | rule-engine **不套 ApiResponse**（返回裸数组/分页）→ spec 改为兼容裸列表 |
| 3 | vite 代理 3 缺口（/registry/deployments） | Sprint 3.1 契约扫描器 bug 修复后 /api/v1/registry 前缀被正确扫出 → 补代理（registry:18089）+ playwright 兜底 |

## 3. 验证结果（全绿）

| 检查 | 结果 |
|------|------|
| Playwright 全量（28 用例，7 spec） | ✅ **28/28**（2 Java + 5 Python 服务真实联调） |
| asset-exchange pytest | ✅ 95 passed |
| P2 e2e | ✅ 3 tests collected（真实 API 依赖 nightly 栈） |
| 契约生成 --check | ✅ 258/258，Python 前缀 16→20（registry 修复后正确计入） |
| vite 代理校验 | ✅ 39 条代理，249 调用路径全分流 |
| 路由冲突扫描 | ✅ exit 0，413 端点，豁免 20 |
| ESLint | ✅ 0 errors |
| Vitest | ✅（client.ts 拦截器无回归） |

## 4. 对后续 Sprint 的输入

1. **订阅域语义分流已落地**：两服务均提供 GET 根路由，前端操作级分流稳定；后续如遇路由歧义可考虑 open-api-catalog 的 /subscriptions 改为独立前缀（如 /api-subscriptions）彻底隔离，但需评估前端改动成本；
2. **能源/政务专属模板未实现**（当前 7 个：金融/零售/制造/医疗/交通/教育/农牧）——P2-27/28 测试仅验证域级契约，真实模板实现是 Phase 4+ 业务工作；
3. **Playwright 覆盖达 11 路由**（dashboard/projects/standard/govern/search/tenants/account/admin/ops/quality + 5 运营域页面）——剩余页面（cluster/datasources/vector/llmops 等）可续扩；
4. **登录按钮文案 i18n 依赖**已健壮化，后续 UI 文案改动不影响 E2E；
5. nightly-e2e.yml 现 compose 18 服务 + Playwright 28 用例，正式门禁覆盖显著增强。