# 数据引擎大数据平台（DataEngineBDP）ROADMAP

> 版本：2026-09-04
> 状态：V2.0 GA 已发布，进入 V2.1 持续硬化期
> 说明：梳理已达成里程碑（M 编号）与待办里程碑（P 编号），作为迭代规划与验收基线。
> 每个待办里程碑给出：目标、验收标准（可测）、预估工作量、前置依赖。

---

## 一、已完成里程碑（历史基线）

| 里程碑 | 日期 | 范围 | 验收/产出 |
|--------|------|------|-----------|
| **M1** V2.0 分析与设计 | 2026-08-06 | PRD + 架构 + 6 领域详细设计 + 实施计划 | 11336 行设计文档，v2.0.0-rc |
| **M2** V2.0 Mock 清零 | 2026-08-06 | 5 个 P0/P1 组件 SIMULATED/Mock 替换为真实实现 | 255+ 用例全通过，13 个跨组件接口一致性问题清零（tag v2.0.0-interface-fix） |
| **M3** V2.0 Phase 1a | 2026-08-07 | 24 任务 + 5 批次，编排/调度/NL2SQL/物化视图/AI 助手 | 175819 行代码，4 高风险项验证（K3s 部署+集成测试+NL2SQL 评测+性能基准） |
| **M4** V2.0 Phase 2+GA | 2026-08-08 | 18 任务 220 人天，微调→评测→部署闭环，等保三级整改，发布路径 | T049 V2.0 GA 发布（tag v2.0.0） |
| **M5** 质量审计与整改 | 2026-08-08 | 6 维度全面评估，P0/P1/P2 分批整改 | 12/12 整改验证项打勾 |
| **M6** 单元测试实化 + CI 硬化 | 2026-08-09~11 | 真实单测替代 mock 计分；checkstyle/PMD/spotbugs；Trivy 安全扫描 | 130 前端测试阻塞 CI，6 个 lint/test 失败 job 归零 |
| **M7** Spring Boot 4.1.1 升级 | 2026-09-01 | 3.2.6→4.1.1 大版本升级 | 全量测试通过，覆盖率门禁 35/15→40/18 实化 |
| **M8** 数据质量/治理补强 | 2026-08-31~09-01 | 质量规则模板库（六维×11 模板）、标准落地校验、连接器真实探测、门禁基线 | SLA 基线实测，覆盖率爬坡，四项遗留问题落地 |
| **M9** i18n 词条化 + UI 硬化 | 2026-09-02~04 | 38 视图 page-level 词条化、暗色令牌化、vue-i18n 框架级双语 | 209 vitest 通过，Playwright zh-CN 稳定，vite IPv4 绑定 |
| **M10** Sprint 2.2~4.2（联调） | 2026-09-03 | 集成栈 14→18 服务、P2 e2e 真实跑通、能源/政务模板 7→9、chart 映射修复、订阅前缀隔离 | 118/95/129/209 单测全绿，4/4 分流验证，vite 40 条代理 250 路径全分流 |

**当前 HEAD**：`516b6b62` feat(i18n): DevMl 三个子组件词条化（2026-09-04）

---

## 二、待办里程碑（按依赖与价值排序）

### P1 · 前端全量 E2E 页面覆盖（Playwright 扩面）
**目标**：把 Playwright 从"核心页面"扩展到全量业务页。
**背景**：当前 spec 覆盖约 14 页（auth/navigation/projects/standards/govern/search/api-format/assets/ops-quality/template/asset/api/business-portal/encaps-tenant），仍有 10+ 页无 E2E。
**待覆盖页面**：`/cluster` `/datasources` `/vector` `/kb` `/llmops` `/gateway` `/sec` `/lineage` `/data-lineage` `/sql-workbench` `/workspace-management` `/quota-management`。
**验收**：
- 每页 ≥3 用例（页面加载 + 标题/关键控件断言 + 1 条主 API 可达）
- 全量 Playwright 在 nightly 栈上全绿
- 新 spec 纳入 CI 门禁（失败阻断）
**工作量**：约 6~8 人日
**前置**：M10（本地 4 服务栈可复用）

### P2 · 行业模板 helm 真部署演练（K3s）
**目标**：`INDUSTRY_TEMPLATES_DEPLOY_MODE=helm` 走真实 helm install 完成端到端演练。
**背景**：Sprint 4.2 已修 chart 映射（chartRef 三级回退）+ 9 个行业 chart 归位，但 helm 路径从未在真实 K8s 集群上跑通（现有测试全用 FakeHelmExecutor / 本地直启 mock）。
**验收**：
- K3s nightly 栈拉起，9 个行业模板至少各 1 次 `helm upgrade --install` 成功
- chart 产物（ConfigMap 打包模板资产）被平台 Job 成功导入
- `helm list` / `helm status` / 卸载闭环验证
- 新增 1 个 K3s 集成测试（真 helm 调用）
**工作量**：约 3~4 人日（含 K3s 环境稳定性排障）
**前置**：P1（若先扩面则 infra 更稳）；独立可并行

### P3 · 跨语言路由冲突扫描器
**目标**：把 `check-api-route-conflict.py` 从"仅 Java @RestController"扩展到 Python FastAPI / Go gin 路由，自动化发现跨服务前缀冲突。
**背景**：Sprint 4.2 的订阅前缀冲突（asset-exchange vs open-api-catalog 同抢 `/api/v1/subscriptions`）暴露了该盲区——Python 服务靠人工契约审查。
**验收**：
- 扫描覆盖所有 Python `APIRouter(prefix=...)` 与 Go `gin.RouterGroup` 声明
- 输出跨进程同前缀冲突报告（含 verb+path），复用 KNOWN_CROSS_PROCESS_ROUTES 豁免机制
- 纳入 CI 门禁（新增冲突阻断）
- 用已知的 subscriptions 冲突作为回归样例（修复后应仅剩豁免项）
**工作量**：约 2~3 人日
**前置**：无（独立）

### P4 · ROADMAP 落地为自动化追踪
**目标**：把本文件的 P 里程碑与 CI/nightly 门禁关联，每个里程碑有可运行的验收脚本/Job。
**背景**：当前门禁（contract 校验/路由扫描/vite 校验/helm-lint/smoke-local）散落在各 workflow，里程碑验收靠人工对照。
**验收**：
- 每个已达成里程碑（M1~M10）有对应回归 Job（已在 CI 或 nightly 中可一键触发）
- 每个待办里程碑（P1~P3）立项时有独立 job/标签
- 里程碑清单可从 CI 侧读（如 workflow_dispatch 下拉）
**工作量**：约 1~2 人日
**前置**：P1~P3 至少完成 1 个后落地更有意义

### P5 · 前端"待接入"占位清理
**目标**：把侧边栏/页面上标记为"待接入"/pending 的交互接入真实后端。
**背景**：V2.0 阶段为诚实曾把一批假交互标记为 pending（sidebar honesty，11 页 real + 16 placeholder）；i18n 扫尾后建议逐页接入。
**验收**：
- 全仓库搜索 `待接入|pending` 占位标记，列出清单
- 优先级最高的一批（有真实后端契约的）接入真实 API，删除占位
- 接入页纳入 P1 的 E2E 覆盖
**工作量**：约 4~6 人日（分批）
**前置**：P1（接入后需要 E2E 兜底）

---

## 三、长期架构方向（V2.1+）

| 方向 | 说明 |
|------|------|
| 多集群/边缘调度 | 在 Karmada 基础上做跨集群工作负载调度的真实链路验证 |
| 信创适配深化 | `platform/infra-provider-xinchang` 已验证骨架，补信创硬件探针与合规自动化 |
| 数据服务变现闭环 | asset-exchange 订阅 + open-api-catalog 审批 + FinOps 计量计费的端到端分账真实验证 |
| 可观测性整合 | 把 OpenTelemetry 导出（sql-gateway 等已埋点）接到 Tempo/Grafana 完成全链路追踪看板 |

---

## 四、变更记录

| 日期 | 变更 |
|------|------|
| 2026-09-04 | 首次补全：基于 git 历史梳理 M1~M10 已达成里程碑，规划 P1~P5 待办里程碑 |