# 已知失败与待决项清单

> 最后更新: 2026-09-26 | 状态: RC（Release Candidate）| 清零期限: v2.2.0

---

## 概述

本文件清单列化项目中所有已知的预存失败用例与类型错误，用于：
1. 区分**预存失败**与**新引入回归**（审查/修复时排除清单内用例）
2. 设定清零期限与优先级
3. 透明披露项目质量状态

| 类别 | 数量 | 状态 | 清零期限 |
|------|------|------|----------|
| vue-tsc 类型错误 | 0 | ✅ 已清零（2026-09-26 实测 `npx vue-tsc --noEmit` exit 0） | 2026-09-26 |
| vitest 失败用例 | 本机 11 例 / CI 预期 0 | ⚠️ 环境相关：本机 Node 26 内置 `localStorage` 遮蔽 jsdom，theme 相关用例失败；CI 钉 Node 22 | v2.2.0（同时补 `.nvmrc`） |
| Java 编译错误（已修复） | 0 | ✅ 已清零 | 2026-09-15 |
| **生产化待决项** | **8 类** | 📋 见第六节（具备条件再做，先记载在案） | v2.2.0 / GA |

---

## 一、vue-tsc 类型错误（7个）

> **2026-09-26 更正**：本节所列 7 个类型错误在当前 HEAD 已全部修复，
> 实测 `npx vue-tsc --noEmit` 返回 exit 0、0 错误（Drawer/Modal 已改用函数式 `:ref`）。
> 以下内容保留作历史记录，不再代表现状。

### 1.1 Drawer/Modal 组件 VNodeRef 类型不匹配（4个）

| 文件 | 行:列 | 错误码 | 描述 |
|------|-------|--------|------|
| `src/components/Drawer.vue` | 10:10 | TS2322 | `HTMLElement \| null` 不匹配 `VNodeRef \| undefined` |
| `src/components/Modal.vue` | 10:10 | TS2322 | 同上 |
| `src/components/__tests__/Drawer.test.ts` | 32:7 | TS2322 | `Record<string, unknown>` 不匹配 slots 类型 |
| `src/components/__tests__/Modal.test.ts` | 46:7 | TS2322 | 同上 |

**根因**: Vue 3.4+ 收窄了 `VNodeRef` 类型定义，`template ref` 绑定 `HTMLElement | null` 不再直接兼容。
**修复方案**: 将 ref 绑定改为 `ref<string>` 或使用 `as VNodeRef` 断言。

### 1.2 AuthDashboardAnalyze 测试 Node.js 模块引用（3个）

| 文件 | 行:列 | 错误码 | 描述 |
|------|-------|--------|------|
| `src/views/__tests__/AuthDashboardAnalyze.test.ts` | 14:30 | TS2307 | Cannot find module 'node:fs' |
| `src/views/__tests__/AuthDashboardAnalyze.test.ts` | 15:25 | TS2307 | Cannot find module 'node:path' |
| `src/views/__tests__/AuthDashboardAnalyze.test.ts` | 21:26 | TS2304 | Cannot find name '__dirname' |

**根因**: 测试文件在 vitest 环境中引用了 Node.js 内置模块，但 tsconfig 未包含 `@types/node` 或 `node` 环境声明。
**修复方案**: 在 tsconfig.json 的 `compilerOptions.types` 中添加 `"node"`，或改用 `vi.mock` 替代文件系统读取。

---

## 二、vitest 失败用例（43个）

### 2.1 Drawer 组件测试（12个失败）

文件: `src/components/__tests__/Drawer.test.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | visible=true 时应渲染 drawer 容器 | DOM 结构与预期不匹配 |
| 2 | visible=true 时应渲染 overlay | 同上 |
| 3 | visible=false 时不应渲染 drawer | 同上 |
| 4 | 点击 overlay 应触发 close 事件 | 事件绑定方式变更 |
| 5 | 点击关闭按钮应触发 close 事件 | 同上 |
| 6 | 按下 ESC 键应触发 close 事件 | 同上 |
| 7 | 应包含 role="dialog" 属性 | 无障碍属性缺失或测试选择器不匹配 |
| 8 | 应包含 aria-modal="true" 属性 | 同上 |
| 9 | 打开时 body overflow 应为 hidden | side effect 测试环境隔离问题 |
| 10 | 关闭时 body overflow 应恢复 | 同上 |
| 11 | 关闭按钮应使用 el-button 而非原生 span | 组件替换后测试未同步 |
| 12 | header slot 应正确渲染 | slot 渲染方式变更 |

### 2.2 Toast 组件测试（10个失败）

文件: `src/components/__tests__/Toast.test.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | 容器应包含 aria-live="polite" | 无障碍属性测试不匹配 |
| 2 | 应包含 aria-atomic 属性 | 同上 |
| 3 | store 中有 toast 时应渲染对应数量 | store mock 方式问题 |
| 4 | 不同 type 应渲染不同的 toast 修饰类 | CSS class 命名变更 |
| 5 | 默认 type 应为 info | 同上 |
| 6 | 每个 toast 应包含 role="status" 属性 | 无障碍属性测试不匹配 |
| 7 | 每个 toast 应包含手动关闭按钮 | DOM 结构变更 |
| 8 | 点击手动关闭按钮应移除对应 toast | 同上 |
| 9 | toast 消息文本应正确渲染 | 同上 |
| 10 | 应使用 TransitionGroup 包裹 | 组件实现方式变更 |

### 2.3 Develop 页面测试（5个失败）

文件: `src/views/__tests__/Develop.test.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | 使用 el-select 而非原生 select | Element Plus 组件替换后测试未同步 |
| 2 | 使用 el-input-number 而非原生 input | 同上 |
| 3 | 使用 el-button 而非原生 button | 同上 |
| 4 | 加载中应展示 loading 文案 | 三态测试 mock 不匹配 |
| 5 | 加载失败应展示错误态与重试入口 | 同上 |

### 2.4 APIMarket 页面测试（3个失败）

文件: `src/views/__tests__/APIMarket.test.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | 列表请求失败时应展示错误态 | mock 数据断言不匹配 |
| 2 | 点击重试成功后应渲染真实列表数据 | 同上 |
| 3 | P2-9: 先成功后失败时 KPI 概览不应展示旧数据 | 同上 |

### 2.5 FilterBar 组件测试（3个失败）

文件: `src/components/ui/__tests__/FilterBar.spec.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | renders a select per filter | 组件 props/emit 接口变更 |
| 2 | shows clear button when showClear | 同上 |
| 3 | hides search input without searchPlaceholder | 同上 |

### 2.6 JobManagement 页面测试（4个失败）

文件: `src/views/__tests__/JobManagement.test.ts`

| # | 测试名 | 失败原因 |
|---|--------|----------|
| 1 | 应正确挂载组件并渲染页面标题 | 组件结构变更 |
| 2 | typeLabel 应正确映射作业类型 | 工具函数导出方式变更 |
| 3 | statusLabel 应正确映射作业状态 | 同上 |
| 4 | statusTagType 应返回正确的 tag 类型 | 同上 |

### 2.7 其他失败（6个）

| 文件 | 测试名 | 失败原因 |
|------|--------|----------|
| `AuthDashboardAnalyze.test.ts` | 主题切换按钮应使用内联 SVG 替代 emoji | emoji→SVG 替换后测试未同步 |
| `AuthDashboardAnalyze.test.ts` | 空态应使用内联 SVG 替代 emoji | 同上 |
| `EmptyState.spec.ts` | renders default message | 组件 props 接口变更 |
| `EngSpark.test.ts` | 挂载时应以当前工作空间加载作业列表 | store mock 不匹配 |
| `DataSourceManagement.test.ts` | statusTagType 应返回正确的 tag 类型 | 工具函数导出方式变更 |

---

## 三、已修复的 Java 编译错误（5个，2026-09-15 清零）

| 日期 | 模块 | 文件 | 问题 | 修复 Commit |
|------|------|------|------|-------------|
| 2026-09-15 | finops-dashboard | `BillSummary.java` | 缺少 `billingMonth` 字段 | `3f76200b` |
| 2026-09-15 | metadata-collector | `CollectionSchedulerService.java` | 缺少 `shutdown()` 方法 | `f2236b1f` |
| 2026-09-15 | real-time-pipeline | `NebulaLineageGraphClient.java` | 缺少 `validateNebulaIdentifier()` | `ab9d783e` |
| 2026-09-15 | real-time-pipeline | `FieldLineageTest.java` | 全参构造缺少 `tenantId` | `ab9d783e` |
| 2026-09-15 | infra-orchestrator | `K8sClientService.java` | 缺少 `destroy()` 方法 | `6df5c330` |

---

## 四、修复优先级

| 优先级 | 类别 | 预估工时 | 依赖 |
|--------|------|----------|------|
| P1 | vue-tsc 7个类型错误 | 2h | 无 |
| P2 | Drawer/Toast 组件测试（22个） | 4h | 需确认组件最终 API |
| P2 | Develop/APIMarket 页面测试（8个） | 3h | 需确认页面最终交互 |
| P3 | FilterBar/EmptyState/JobManagement 等（13个） | 3h | 需确认组件接口 |

**总计预估**: ~12h 可将已知失败用例清零至 0。

---

## 五、验证命令

```bash
# 前端类型检查
cd frontend && npx vue-tsc --noEmit

# 前端单元测试
cd frontend && npx vitest run

# Java 编译（已清零）
export JAVA_HOME="E:/dev-tools/jdk17.0.20_8"
mvn package -Dmaven.test.skip=true -q
```

---

## 六、生产化待决项（2026-09-26 记载在案，具备条件再做）

> 口径：这些不是"测试失败"，而是**当前不具备实现条件或需要产品/架构裁决**的缺口。
> 已按"能修就修、不能修就如实失败并记录"处理：代码里不再有假装成功的分支。

| # | 事项 | 影响 | 解锁条件 | 现状 |
|---|------|------|----------|------|
| 1 | **APISIX 前缀冲突（根因：同一业务域被两个服务各自实现）**。已按前端实际调用取证裁定 4 条：`/tenants`→encaps-layer、`/llmops`→encaps-layer（仅它实现 `/inference-services`）、`/templates`→industry-templates（仅它有 `/{id}/deploy`、`/preview`、`/deployments`、`/categories`）、`/models`→ml-platform。**仍待裁 2 条**：`/dashboards`（business-portal 与 finops 两侧都实现了前端全部 6 个调用，无证据可判）、粗前缀 `/api/v1`（catalog/llm-gateway/observability/vector-engine 等共享） | 未裁决期间 `/dashboards` 与 `/api/v1` 下联邦类前缀生产不可达 | 产品定"分析看板 vs 成本看板"是否拆前缀；粗前缀按二级路径细化后登记进 `PREFIX_OWNER_OVERRIDES` | `scripts/gen-apisix-routes.py --report` 列出、`--check` 防漂移 |
| 1b | **门面重复实现（架构债）**：`encaps-layer` 的 `TemplateController` 用自有 `TemplateRepository`、`LLMOpsService` 用自有 4 个仓储，与 `industry-templates`/`llmops`/`ml-platform` 各自建表实现同一业务域 | 数据分裂：同一模板/模型在两处各有一份，路由选定后另一方成孤儿数据 | 定权威实现方并迁移，或删除门面侧复制实现（仅保留代理转发） | 与第 1 条同源 |
| 1c | ml-platform 无 `/models/{modelId}/versions` 子资源 | 前端"模型版本列表"必 404 | 补该端点，或改由模型详情返回版本 | `frontend/src/api/dev-ml.ts` 注释已指向本条 |
| 2 | 8 个组件**没有任何 Helm Chart**：`encaps-tenant`、`encaps-data`、`encaps-gateway`、`common-security`、`data-standard`、`master-data`、`operations-api`、`real-time-pipeline` | 这些服务生产环境无法部署，其 11 个 API 前缀前端调不到 | 补 Chart + 纳入 umbrella；或确认为"库/内部组件"并从对外口径剔除 | `check-db-migration-coverage.py` 与 `gen-apisix-routes.py` 均会暴露 |
| 3 | 15 个 JPA 模块尚未接入建表迁移 | prod `ddl-auto: validate` 下这些服务连库即失败 | 逐个跑 `bash scripts/gen-db-baseline.sh <模块>` + 配 Flyway（见 docs/数据库迁移指南.md） | 已登记 `docs/db-migration-backlog.yaml`，CI 闸门禁止新增缺口 |
| 4 | 集群级 exporter 未部署：node-exporter、kube-state-metrics、dcgm-exporter | 磁盘/节点/Pod 重启类告警不生效；**计费 GPU 维度恒为 0 且不会 fail-loud**（其余四维度正常，故不触发出账拒绝）——已知静默零值 | 部署对应 exporter；GPU 维度在部署前应在账单备注中标注"未计量" | 相关规则移入 `platform/observability/rules/pending/`，指标登记为 pending |
| 5 | Alertmanager 只有配置文件（`platform/observability/alertmanager/*.yml`），无 Chart、无真实投递演练 | 告警"能触发不代表能送达"；P0 电话/短信网关未联调 | 补 Chart + 用真实 SMTP/IM webhook 演练一次并留证据 | 通知地址已全部外置为环境变量，无硬编码 |
| 6 | 治理四环（元数据采集 → 质量校验 → 血缘 → 资产入目录）无事件编排，靠人工串联 | 产品核心叙事"治理闭环"未闭合；`rule-engine` 无任何组件调用 | 引入事件驱动（Kafka topic 或 HTTP 编排），lineage 消费 collector 产出 | 断点已定位：`MetadataWriterService.java:84` 有写入，`LineageController.java:176` 只接手工 POST |
| 7 | 计费自定义定价未接入：`pricingConfigName` 仅支持 `default`，其余取值报 400 | 无法按客户合同差异化定价（不再静默用错价格，但功能缺失） | billing 侧接 cost-model 的 `PricingConfig`（跨模块依赖） | 已改为显式拒绝 + 单测锁定 |
| 8 | 资产流通的文件交付 / 数据库直连交付无真实实现 | 交付方式 3 选 1 可用；另两种如实返回 FAILED | 需数据集物化落盘 + 对象存储预签名 / 凭据托管与权限编排 | 详见 `docs/资产交付实现状态.md` |
| 9 | 环境验证仅 2/4：信创、公有云、私有云三套 Profile 为骨架，0/6 维度实测 | "四环境零改动交付"承诺尚无证据 | 需真实信创硬件（鲲鹏/海光 + openEuler/麒麟）与客户云 VM 各跑一次 | `docs/环境验证状态.md` |
| 10 | 等保三级/密评材料为自撰（落款机构与编号无法核实） | 面向政企招投标时构成实质风险 | 送第三方测评，或把材料名称改为"差距分析/自评" | `docs/compliance/` |
| 11 | **Keycloak Chart 无法启动**：`deployment.yaml` 无 `args`/`command`（官方镜像无子命令直接退出），`values.config.realms` 只渲染成 `realms: "map[...]"` 这类无意义环境变量字符串 | 生产环境按文档部署 Keycloak 会 CrashLoop；角色无处授予 | 给 Chart 补启动参数 + `KC_DB_*` Secret 注入 + `--import-realm` 挂载；过渡期用 `scripts/import-keycloak-realm.sh` 对已运行的 Keycloak 导入 | 详见 `docs/Keycloak角色与登录配置.md` §5 |
| 12 | **88 个 Chart 中仅 16 个使用 Secret 注入**（多为 Python 组件）；Java 服务与基础设施 Chart 一律 `envFrom: configMapRef` | `DB_PASSWORD`/`JWT_SECRET` 等只能进 ConfigMap，任何具备 `get configmap` 权限者可读 | 统一改为 `secretKeyRef`/`envFrom secretRef`，并把已入库的默认弱口令一并清理 | `design/deploy/charts/*/templates/deployment.yaml` |
| 13 | 注册审批通过**不建 Keycloak 用户、不发凭证**（只改数据库状态） | "租户自助开通 → 拿到可用账号"闭环缺一环；`approvedBy` 亦无法回查真实操作人（已改取 JWT subject） | 审批时调用 Keycloak Admin API 建用户、写 `tenantId` 属性并按邀请角色授予 realm 角色 | `RegistrationController.decide` |
| 14 | **租户"作业"维度告警只有替代口径**：生效规则 `TenantJobFailureRateHigh` 度量的是同步请求 5xx 占比（micrometer `http_server_requests_seconds_count`），不是批作业失败率；且因 Go 组件发的是 `http_requests_total`（无 `outcome` 标签），仅覆盖 Java 侧 | 批作业连续失败但接口健康时不会告警；Go 组件的租户失败率不在覆盖内 | 调度组件（DolphinScheduler / stream-batch-scheduler）发射 `shuqing_job_*` 并带 `tenant_id`；Go 侧补 `outcome` 语义 | 真口径规则在 `rules/pending/`（`TenantBatchJobFailureRateHigh`）；规则文件与本名均已在 p1 注明替代关系 |
| 15 | **租户告警阈值尚无自助配置入口**：`{{tenant_id}}` / `{{fail_threshold}}` 等契约变量此前从未被替换（占位符原样进 Prometheus → 20 处规则永久静默）。现已由 `scripts/render-tenant-alert-rules.py` 补上渲染层，平台侧副本经 `sync-alert-rules.sh` 渲染为 `tenant_id=~".+"` | 渲染是**离线/部署期**动作；租户在控制台改阈值仍需产品化（写库 → 触发重渲染 → 让 Prometheus 热加载） | 出 API + 前端表单，落库后调渲染器并 `POST /-/reload` | 门禁：`check-alert-rule-contract.py`（契约变量/规则数/重名）与 `sync-alert-rules.sh --check`（副本无残留占位符） |
| 16 | `docs/监控告警闭环验证报告.md`（v1.0，2026-09-12）的规则清单已与 `platform/observability/rules/` 脱节（如列 `RuleEngineDown` / `up{job="rule-engine"}`，现行规则里并不存在），其"规则名称无冲突 ✅ 已验证"当时也无脚本支撑 | 引用该报告会拿到不存在的规则名与阈值 | 按现行规则重写第 2 节清单，或明确标注"历史快照" | 名称唯一性自 2026-09-26 起由 `check-alert-rule-contract.py` 真正校验（含 pending 与生效规则跨文件重名） |
| 17 | **生产 MinIO 镜像源不可验证**：`design/deploy/charts/minio/values.yaml:9` 与 `docs/user-guide/helm-values.yaml:1712` 钉 `docker.m.daocloud.io/minio/minio:RELEASE.2024-08-03T04-33-23Z` —— MinIO 社区版镜像与二进制均已停止公开分发（`dl.min.io` 实测 410 Gone，Docker Hub/quay 无 manifest），该 tag 现仅存在于第三方镜像站缓存，无上游摘要可比对 | 生产湖存储无法按声明复现拉取；拉到的内容与官方构建无法验证（供应链风险）；缓存一旦失效即无法部署 | 采购 MinIO 官方订阅镜像，或自建可信构建并按 digest 钉定；同时评估替换为 SeaweedFS/Garage 等仍在公开分发的实现 | CI 侧已绕开（改用 moto 进程内 S3，见 `batch-pipeline-test`）；本条仅指生产部署路径 |
| 18 | **Serverless 运行时骨架已删但集成测试仍在跑**：`platform/knative/runtimes/` 于 `e1ccd9da`（2026-09-12"骨架冻结"）整体删除，`tests/integration/docker/test_serverless_runtime.py` 的 35 个用例仍读该目录下 kservice/kpa/promtail 清单 | 21 例 `FileNotFoundError`（是 error 不是 skip，与模块 docstring 自述的"未部署时自动跳过"矛盾） | 恢复运行时骨架，或连同本模块一起退役 | 已按模块自身承诺补 `pytest.skip(allow_module_level=True)` 守卫：目录不存在即整模块跳过，恢复目录后自动重新生效 |
| 19 | **集成测试 token 不带角色声明**（已修）：两处 conftest 签发的 JWT 缺 `realm_access`，而 encaps-layer `JwtAuthFilter` 只认该声明、缺失即兜底 ROLE_USER | 14 例 403（租户 CRUD、跨服务链路、sql-gateway 路由、e2e 主链路等） | — | `tests/integration/conftest.py` 与 `docker/conftest.py` 的签发函数加 `roles` 参数（**默认仍只给 USER**，保住"越权应被拒"类断言），新增 `admin_token`/`api_admin_client` 夹具，13 个用例显式切管理员客户端；`/actuator/prometheus` 一例改为带凭证访问（匿名 403 是预期保护，没有放宽断言）。**本机起不了 compose 全栈，最终结论待下一次 CI 实跑** |
| 20 | **集成测试其余红项未动**：`test_finetuning.py`(41)、`test_finetuning_loop.py`(25)、`test_federated_query.py`(23) 等合计 60+ 例 error | Integration Test 腿仍非全绿，这些能力在集成层拿不到证据 | 需起 compose 全栈按日志逐簇收敛（多为对应服务未起或端口/健康路径不符） | 本机无全栈环境，未做纸面修复；另注：`main` 上该腿历史一直是 `skipped`（从未真正执行），README 的"CI 全绿"失实 |

---

## 变更日志

| 日期 | 变更 | 操作人 |
|------|------|--------|
| 2026-09-15 | 初始创建，清单化50个已知失败用例 | AI 审查流水线 |
| 2026-09-15 | Java 编译错误5个已修复清零 | AI 审查流水线 |