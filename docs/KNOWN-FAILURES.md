# 已知失败与待决项清单

> 最后更新: 2026-09-29 | 状态: RC（Release Candidate）| 清零期限: v2.2.0

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
| **生产化待决项** | **41 项**（2026-09-29 新增 #24–#39；其中 #24–#27、#31–#38 已在本分支修复待 CI/nightly 核验，且 #37/#38 已本地实跑通过；#28–#30、#39 为裁决项） | 📋 见第六节（具备条件再做，先记载在案） | v2.2.0 / GA |

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
>
> **2026-09-29 更新**：本节新增 #24–#32（CI 红项分诊结论），并在 #20 追加收敛明细、#23 标记已解决。
> 新增各项中 6 项已在本分支修复，标注"待 CI 核验"；3 项（#28 CodeQL 误报、#29 Trivy fs、#30 Kind Smoke 镜像源）
> 属**裁决项**，需产品/架构决定处置口径，详见各项"解锁条件"列。
>
> **2026-09-29 续二**：新增 #33–#37（K3s burn-in 三链根因 + e2e 跨域 2 失败重写），均已在本分支修复、待 CI/nightly 核验（#37 已本地实测）；
> 并在 #20 追加续二实测、#32 补 open-api-catalog 的 kind 复核证据。
>
> **2026-09-29 续三**：① **#32 批次引发的新红（已修）**——4 chart + 4 k3s manifest 的注释 token `<SVC>_PORT=tcp://IP:PORT` 命中 `scripts/profile-render-check.sh:38` 的 `<.*>` 占位符正则，Local E2E "Profile Render Check" 在 d69770a4/0dbb1cb3 两推连续红（run 36462896041/36473626429，fail-fast 于 xinchuang profile）；已改写 8 处为 `服务名_PORT`，本地全脚本四 profile 全 PASS。步骤 2 的 CI 兜底证据：nightly "Chart Render & Schema Smoke" 在 PR#328 合并预览 a3ca1c4 跑 `chart-render-check.sh` 得 `PASS=97 FAIL=0`（同 chart 集、同 kubeconform v0.6.7 参数）；本地步骤 2 一度报 retail/transportation 失败，经查为**本机 schema 拉取网络故障**（报错为 `failed parsing schema ... wsarecv` 而非校验失败），离线以真实 v1.29.0 schema 复验两 chart 全部 `is valid`。② **burn-in 实测（run 36473626436/a3ca1c4）**：链路 1/3/5 全过（#34/#36 获验证：`test_encaps_health` 通过、链路5 全 9 例通过含 hook 断言；`/error` 掩盖已消除——返回真实 200/400 而非 403）；链路2 仅余 `test_create_cluster`（响应 400——read 超时已消失，待按后端校验定位）；链路4 余 5 例 `status=200` 但字段全 `None`（security/* 契约错配，待定位）。
>
> **2026-09-29 续四**：定位并修复续三两处遗留（均为**测试侧契约错配**，服务代码无需改动，双双本地实跑核验）：
> ① **链路4 五例 `status=200` 但字段全 `None` 的根因 = encaps-layer 全局响应信封**——`ApiResponseAdvice`（`c797f600a`，2026-08-17"P0阻塞项清除-接口规范统一"）把 `/api/v1/**` 全部 controller 裸返回包装为 `{code,message,data,timestamp,success}`，业务字段在 `data` 内；链路4 断言在顶层取 `enabled`/`masked`/`allowed`/审计列表 → 恒 `None`（本地实测 `GET /api/v1/security/mask/phone` → `{"code":0,"message":"OK","data":{"type":"PHONE","masked":"138****5678"},"timestamp":...,"success":true}`）。修复见 #38；本地 8/8 通过（`8 passed, 1 warning in 11.62s`）。
> ② **链路2 `test_create_cluster` 400 的根因 = 载荷未过 DTO 校验，编排 DAG 未执行**——`ClusterCreateRequest` 的 `@NotBlank`（k8sVersion/podCidr/serviceCidr）与 `@NotEmpty`（nodes）、Jackson 原始类型拒绝 null（实测 `Cannot map 'null' into type boolean/int`：skeEnabled、NodeSpec 五整型）使一干"文档视角可选"的字段实为 **JSON 必填**；400 发生在编排 DAG 之前（events 恒空即此证），且 `tenantId` 的服务端注入在校验**之后**。修复：载荷补全 8 字段（skeEnabled/k8sVersion/podCidr/serviceCidr/nodes[{role,count,cpuCores,memoryGb,diskGb}]）。本地 jar 实测 `HTTP 500 {"phase":"FAILED","events":["provider-selected:infra-provider-xinchang","request-transformed","provider-create-error"]}`（provider Pod 恒 ImagePullBackOff，fail-closed 符合环境约束）；pytest `1 passed, 9 deselected`。
> ③ 顺带发现**前端缺陷（未修，登记待裁决见 #39）**：`frontend/src/views/infra/InfraK8s.vue:573` 提交 `workers`/cpu/memory/disk 且缺 `tenantId`/`skeEnabled`，与 DTO（`nodes`/cpuCores/memoryGb/diskGb）不匹配，UI 建集群必 400。
> ④ CI 核验：Local E2E 在 `7ee8ea87`（run 36477352131）"Profile Render Check" = **success**（#32 批次注释修复获 CI 验证）。

| # | 事项 | 影响 | 解锁条件 | 现状 |
|---|------|------|----------|------|
| 1 | **APISIX 前缀冲突（根因：同一业务域被两个服务各自实现）**。已按前端实际调用取证裁定 4 条：`/tenants`→encaps-layer、`/llmops`→encaps-layer（仅它实现 `/inference-services`）、`/templates`→industry-templates（仅它有 `/{id}/deploy`、`/preview`、`/deployments`、`/categories`）、`/models`→ml-platform。**仍待裁 2 条**：`/dashboards`（business-portal 与 finops 两侧都实现了前端全部 6 个调用，无证据可判）、粗前缀 `/api/v1`（catalog/llm-gateway/observability/vector-engine 等共享） | 未裁决期间 `/dashboards` 与 `/api/v1` 下联邦类前缀生产不可达 | 产品定"分析看板 vs 成本看板"是否拆前缀；粗前缀按二级路径细化后登记进 `PREFIX_OWNER_OVERRIDES` | `scripts/gen-apisix-routes.py --report` 列出、`--check` 防漂移 |
| 1b | **门面重复实现（架构债）**：`encaps-layer` 的 `TemplateController` 用自有 `TemplateRepository`、`LLMOpsService` 用自有 4 个仓储，与 `industry-templates`/`llmops`/`ml-platform` 各自建表实现同一业务域 | 数据分裂：同一模板/模型在两处各有一份，路由选定后另一方成孤儿数据 | 定权威实现方并迁移，或删除门面侧复制实现（仅保留代理转发） | 与第 1 条同源 |
| 1c | ml-platform 无 `/models/{modelId}/versions` 子资源 | 前端"模型版本列表"必 404 | 补该端点，或改由模型详情返回版本 | `frontend/src/api/dev-ml.ts` 注释已指向本条 |
| 2 | 8 个组件**没有任何 Helm Chart**：`encaps-tenant`、`encaps-data`、`encaps-gateway`、`common-security`、`data-standard`、`master-data`、`operations-api`、`real-time-pipeline` | 这些服务生产环境无法部署，其 11 个 API 前缀前端调不到 | 补 Chart + 纳入 umbrella；或确认为"库/内部组件"并从对外口径剔除 | `check-db-migration-coverage.py` 与 `gen-apisix-routes.py` 均会暴露 |
| 3 | 15 个 JPA 模块尚未接入建表迁移 | prod `ddl-auto: validate` 下这些服务连库即失败 | 逐个跑 `bash scripts/gen-db-baseline.sh <模块>` + 配 Flyway（见 docs/数据库迁移指南.md） | 已登记 `docs/db-migration-backlog.yaml`，CI 闸门禁止新增缺口 |
| 4 | 集群级 exporter 未部署：node-exporter、kube-state-metrics、dcgm-exporter | 磁盘/节点/Pod 重启类告警不生效；**计费 GPU 维度恒为 0 且不会 fail-loud**（其余四维度正常，故不触发出账拒绝）——已知静默零值 | 部署对应 exporter；GPU 维度在部署前应在账单备注中标注"未计量" | 相关规则移入 `platform/observability/rules/pending/`，指标登记为 pending |
| 5 | Alertmanager 只有配置文件（`platform/observability/alertmanager/*.yml`），无 Chart、无真实投递演练 | 告警"能触发不代表能送达"；P0 电话/短信网关未联调 | 补 Chart + 用真实 SMTP/IM webhook 演练一次并留证据 | 通知地址已全部外置为环境变量，无硬编码 |
| 6 | **治理闭环"断链"的真实位置（更正我此前的误判）**：原描述"无事件编排、靠人工串联"不准确 —— `real-time-pipeline` 里事件编排**已完整实现**（`CatalogEventListener.java:145` 定时轮询 → `：180 processEventAsync` → `：187 metadataCollector.collect` → `：197 orchestrator.onMetadataCollected`）。真实问题是**闭环只在该进程的私有重实现里自洽，三个独立治理服务全挂在环外**：① 血缘双写两处 —— pipeline 用自带 `NebulaLineageGraphClient`，而 lineage-analyzer 的 `LineageGraphWriter.java:43-46` 默认 `nebula.enabled:false` 走 H2/JPA，故 pipeline 算出的血缘前端查不到（`/upstream`、`/downstream`、`/impact` 都走服务侧）；② `POST /api/v1/lineage/events`（`LineageController.java:175`）与 `POST /api/v1/governance/catalog/events`（`CatalogEventListener.java:107`）**两个入口都已存在却零生产调用方**；③ 质量规则两份 —— `StreamingQualityRuleEngine.java:43` 用进程内 `ruleRegistry`，rule-engine 的 `/api/v1/orchestrator/dags` 全仓零 HTTP 调用方；④ metadata-collector 写完 catalog 即止（`MetadataWriterService.java:61-68`）不发事件，故非 Iceberg 数据源永远没有自动血缘；⑤ Java 侧无任何 Kafka 生产者/消费者（命中 0） | 血缘页面看不到自动血缘（只能手粘 SQL 现场分析）；在规则引擎里配的质量规则不作用于任何自动评估 | 见 `docs/治理闭环修复草案.md` 第 4 节四阶段方案；阶段 3 需先裁决 Q1（质量规则权威实现归属） | **待裁决，未开工**。复现命令见草案第 6 节 |
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
| 18 | ~~Serverless 运行时骨架已删但集成测试仍在跑~~ —— **已解决，且我最初的定性是错的**：`e1ccd9da`（2026-09-12"骨架冻结"）是把 `platform/knative/runtimes/` **移到** `design/planned/knative/runtimes/`（未删除、仍在版本控制内），`test_serverless_runtime.py` 只是路径没跟着改 | 该模块 35 个用例中 21 例 `FileNotFoundError`（模块 docstring 自述应"未部署时自动跳过"，实为抛异常） | — | 已把 `RUNTIMES_DIR` 指向新位置（保留 platform/ 兜底 + 目录缺失时模块级 skip）：本地 **31 passed / 3 skipped**；唯一 1 例 error 是我用 `--noconftest` 跑导致 `--run-scale-to-zero` 选项未注册，CI 带 conftest 时为 skip |
| 19 | **集成测试 token 不带角色声明**（已修）：两处 conftest 签发的 JWT 缺 `realm_access`，而 encaps-layer `JwtAuthFilter` 只认该声明、缺失即兜底 ROLE_USER | 14 例 403（租户 CRUD、跨服务链路、sql-gateway 路由、e2e 主链路等） | — | `tests/integration/conftest.py` 与 `docker/conftest.py` 的签发函数加 `roles` 参数（**默认仍只给 USER**，保住"越权应被拒"类断言），新增 `admin_token`/`api_admin_client` 夹具，13 个用例显式切管理员客户端；`/actuator/prometheus` 一例改为带凭证访问（匿名 403 是预期保护，没有放宽断言）。**本机起不了 compose 全栈，最终结论待下一次 CI 实跑** |
| 20 | **Integration Test 红项已分诊并收敛一半（#1246 → #1248 实测：189 红 → 83 红；errors 138 → 41，passed 568 → 650）**。**已修四类**：①组件路径漂移 66 error（`test_finetuning.py:50`、`test_finetuning_loop.py:61,68` 仍指 `platform/model-finetuning`、`platform/registry`，组件已移 `design/planned/`；空目录进 `sys.path` 后 `from app.main import app` 静默导到 evaluation 的 app 包，才报出 `EVAL_DEV_MODE/JWT_SECRET`、`module 'main' has no attribute 'app'` 这类无关错）；②fixture 名错配 33 处 `F821`（我自己 `199a4f10` 把参数改成 `api_admin_client` 却漏改函数体，致 12 个用例从未执行）；③mock 写死端口撞车 14 error（mock 用 18093/18094 与 compose 发布的 business-portal/asset-exchange 宿主端口相同 → session fixture `EADDRINUSE` 拖垮整模块，改 `bind(0)`）；④e2e 缺键 8 error（`E2E_BASE_URLS` 无 `karmada`/`observability` 键但 fixture 按下标取值 → KeyError，补 18101/18102 与健康路径）。#1248 复核：这四类在真机已全部消失。**剩余 83 项定性**：A) k3s 四链路 **39** —— 报错已明确为 `K3s 服务 X 未发现（kubectl 不可用或 Service 不存在）`，且 `conftest.py:566,590` 写明 **T-05 CI 严格模式：部署失败不加 skip**；实测 `deploy/k3s/manifests/` 引用约 **20 个 `sq/*:0.1.0` 镜像而集成 job 一个都不构建**（compose 建的是 18 个 `shuqing/*`），故"在 IT job 里加 kind"不可行（30 分钟上限、pytest 已占 13 分钟），需独立 job；B) perf **16** —— `success_count=0/error_count=10000` 且有 429，是 `RateLimitFilter` 拦截而非性能不达标；但实测发现独立 `Performance Test` job 跑的是 `tests/performance/run_benchmark.py`**（无 compose 步骤，属离线基准）**且带 `continue-on-error`，即 IT 这 16 项是全仓唯一打真实部署的性能证据，关限流等于改变其测量对象，需裁决；C) asset-exchange **10** —— **根因是一行测试常量写错，已修复并本地实测 27 passed / 0 failed**：`test_asset_exchange.py:66` 原写 `SUBS = /api/v1/subscriptions`，而产品侧订阅资源前缀是 **`/asset-subscriptions`**（`subscriptions.py:42`）。不匹配任何路由 → FastAPI 默认体 `{"detail":"Not Found"}`（判据：业务 404 是 `{error,message}` 自定义体，二者可区分）。实测对照：`GET /api/v1/subscriptions` → 404 Not Found，`GET /api/v1/asset-subscriptions` → 200 `[]`，`POST /api/v1/assets/x/subscribe` → `资产不存在: x`（说明 subscribe 路由本身在）。**过程性更正（我在此项上连续错过三版定性，留此备忘防再错）**：v1 "未实现的第二契约"→ 错，按资产 settle 早已实现（`assets.py:582` → `settlement_service.py:46`）；v2 "allocations router 未接线"→ 错，`/allocate` 与 `/allocations` 都在（`assets.py:630,675`）；v3 才靠"真实请求探测 + 本地复现同一批失败"定位到前缀错配。教训：**路由枚举式 introspect 在懒注册下会给出假阴性**（`_IncludedRouter` 无 `path`），只能用真实请求判定；D) **403 授权 7 项**（`test_get_tenant`/`test_list_tenants`/`test_update_tenant`/`test_quota_validation_and_list`/`test_prometheus_metrics_endpoint`/`test_sql_gateway` 2 项）—— 这是修掉 NameError 后**首次真正执行才暴露的信号**，已排除两种解释：`TenantController` 类级要求 `SUPER_ADMIN`，而 `JwtAuthFilter.java:144` 确实把 `realm_access.roles` 映射为 `ROLE_*`，且这些用例已使用带 `SUPER_ADMIN` 的 admin 客户端 → 属 token 装配链真问题，需容器日志定位；E) 其余零散 5 项（`assert 500 == 200` ×3、409 ×1、`ReadTimeout(18090)` ×1、`KeyError: 'id'` ×1） | Integration Test 腿仍红，但已"可归因" | k3s 与 perf 需 job 结构/测量语义决策；C 需产品裁决；D/E 待按容器日志继续查 | `main` 上该腿历史一直是 `skipped`（从未真正执行）；本轮把红项从不可归因收敛到 5 类可执行结论。**2026-09-29 续（run 36378306992 实测：32 failed / 663 passed / 144 skipped / 38 errors）**：①k3s 39 项已按上述结论**移出本 job**（ci.yml 加 `--ignore=k3s`，交由 nightly-e2e K3s burn-in 专用腿）；②perf 16 项同样 `--ignore=perf`（独立 Performance Test job 才是其测量场，本 job 的 429 属 `RateLimitFilter` 语义）；③剩余 15 项逐一对症：`test_docker_encaps.py` 3 例切 `api_admin_client`、`test_docker_encaps_tenant.py` 6 例（403 语义断言修正 + `numeric_tenant_token` + workspace 容错）、`test_realtime_governance.py` 3 例改为服务不在 compose 栈时 skip、e2e 3 例（`_unwrap_response` 兼容裸列表 + 资产类型 `DATASET`→`table`）；④Java 侧配套：`GlobalExceptionHandler` 新增 `ResponseStatusException` 透传（修 409 误映射）、`AccountController`/`ProjectController` 抛 `MissingTenantContextException`（403 语义）、`WorkspaceService` 加 `app.k8s.mock-enabled`（无集群时短路 k8s 调用）。**待下一次 CI 实跑核验**。**2026-09-29 续二（run 36457429958 与 36462895746 实测：均 `2 failed / 656 passed / 151 skipped`）**：上一批修复全部收效（32 failed → 2 failed），全 job 仅余 e2e 跨域 2 例；两 run 的 2 例失败签名逐字一致（`质量评分_0.0_<_60` 与 SubscribeRequest 缺字段，见日志 11484/11487 与 11360/11363），属预存非回归，其根因与修复见 #37 |
| 21 | ~~okhttp 3.14.0 未抬版（需裁决）~~ —— **已解决，且不需要在"删 Nebula"与"长期红"之间二选一**：CVE-2021-0341 的修复版只在 4.9.2+（GitHub Advisory 实测受影响范围 `< 4.9.2`，**3.x 全线无修复版**；`com.vesoft:client` 最新 3.x 就是已在用的 3.8.4，也没有"升图库客户端即修好"的路）。但 okhttp 在 Nebula 之路上是**死代码**：全 jar 仅 `com/facebook/thrift/transport/{THttp2Client,OkHttp3Util}` 两个类引用它，只由 `SyncConnection.getProtocolWithTlsHttp2()/getProtocolForHttp2()` 使用，受 `useHttp2` 开关控制且默认 false（构造器 `iconst_0`），只能经 `NebulaPoolConfig.setUseHttp2(true)` 打开——本仓 governance 代码与配置对 http2 的引用数为 0。故采用**根 pom 对 `com.vesoft:client` 排除 okhttp**（保留 Nebula），非抬版、非排除功能 | 无：图库 thrift 连接路径实测不依赖 okhttp | 已在根 pom `dependencyManagement` 集中排除；`NebulaGraphClient.java:88-95` 的实际调用路径保持原样 | **实测三重验证**：①`mvn dependency:tree -Dincludes=okhttp` 在两模块为空（对照组 `infra-provider-cloud` 正常打印 4.12.0，证明筛选器有效）；②模块测试类路径 okhttp/okio 条目数=0、vesoft=1；③在该类路径上执行真实 `NebulaPool.init()`，栈走到 `SyncConnection.open(SyncConnection.java:139)` 仅报 `ConnectException: Connection refused`、无 `NoClassDefFoundError`，`useHttp2=false`、init 返回 false（按设计降级）。两模块 `mvn test` 全绿（lineage-analyzer 含新增守卫 3 例、real-time-pipeline 87 例），Checkstyle exit=0。并新增 `NebulaWithoutOkhttpTest` 锁住该前提 |
| 22 | **`_reusable-trivy-scan.yml` 是死文件但在每次 push 抢跑**：它只有 `on: workflow_call` 且自身注释第 4 行写明"预留——当前未被任何 workflow 引用"（`grep -rln _reusable-trivy-scan .github/workflows/` 只命中它自己），GitHub 却在每个 push 上为其创建 run，**0 秒失败、0 个 job**（实测 run 36262959972 / 36259723089 / 36255615266 等 8 次全部如此），Actions 列表因此每推一次多一个红叉 | 不阻断任何门禁（Quality Gate 只聚合 CI/Security/CodeQL/SonarQube 四个名字，不含它），但**污染状态判读**——我自己和台账里的"CI 变红"都需要先排除它。真实扫描在 `ci.yml` 的 Trivy job 里，一直是它在起作用 | 三选一：①删除该文件（当前无人引用）；②加 `if: false` 之类的显式停用；③真正让 `ci.yml`/`security.yml` 复用它以消除三份重复（工作量大，属 T-10 未完成的收尾） | 已确认与本仓产品代码无关，纯 CI 侧噪声；不影响 #1246 的 Trivy 结论（那条只剩 okhttp 一项，见 #21） |
| 23 | ~~**`POST /api/v1/sql/routes` 在任何环境都不可达（真产品缺陷，不是测试噪音）**~~ —— **已解决（`cccb0c5c`，2026-09-29 核验）**：原结论为 `SqlGatewayController.java:113` 要求 `@PreAuthorize("hasRole('SQL_GATEWAY_WRITER')")`，但该角色全仓仅此一处、realm 词表无定义，写端点必然 403。修复取裁决路径①：`design/deploy/keycloak/sq-realm-roles.json` 新增 `SQL_GATEWAY_WRITER` 角色并纳入平台管理员授予集，`@PreAuthorize` 语义保持不变 | 已消除：`test_sql_gateway.py::test_add_route` / `::test_sql_execution_flow` 的 403 预期可通过（待下一次 CI 实跑确认） | — | `cccb0c5c`（2026-09-29）；修复前证据：`SqlGatewayController.java:113` 单点命中、`sq-realm-roles.json:13,21,29` 无该角色 |
| 24 | **QuotaController 要求 `ADMIN` 角色，但 realm 词表无 `ADMIN`（#23 同类缺陷第二处）**：`QuotaController.java:55` 原写 `@PreAuthorize("hasRole('ADMIN')")`；Keycloak realm 角色为 `SUPER_ADMIN`/`TENANT_ADMIN`/`USER`，无 `ADMIN`，故配额写端点对任何凭证必然 403（集成测试 `test_quota_validation_and_list` 的 403 即此项） | 租户配额无法通过 API 配置 | 已修复：改为 `hasRole('ADMIN') or hasRole('SUPER_ADMIN')`，与租户管理端点口径对齐 | 已修，**待 CI 核验**（`fix/p0-production-blockers`）；证据：`QuotaController.java:55` |
| 25 | **Docker Build Verify 红：3 个 Dockerfile 缺 common-security 预装**。`tag-engine`、`karmada/federated-query`、`governance/real-time-pipeline` 三个 Dockerfile 在 `go-offline` 离线构建前未先安装本仓 `common-security` 模块，Maven 报 `com.levango7.dataenginebdp:common-security:jar:0.1.0-SNAPSHOT (absent)`（run 36378306992 job 108788568621，L3425） | 镜像构建失败，3 个服务无法产出镜像 | 已修复：按 `sql-gateway`/`rule-engine` 既有 Dockerfile 模式补预装步骤 | 已修，本地三个镜像构建实测 `TAG_ENGINE_EXIT=0` / `FEDERATED_EXIT=0` / `RTP_EXIT=0`，**待 CI 核验** |
| 26 | **Playwright E2E 红：13 例跨 4 个 spec 文件失败**（run 36378306993 job 108788568716）：`i18n-switch.spec.ts` L32/49/69/95/110、`navigation.spec.ts` L20/116/138/154、`search.spec.ts` L21、`standards.spec.ts` L20/38/59 —— 选择器/断言与现行 DOM 及 i18n 键漂移 | nightly E2E 门禁红，前端回归不可判读 | 已修复 4 个 spec：按现行 DOM 结构/i18n 键校正选择器与断言 | 已修，**待 nightly CI 核验** |
| 27 | **nightly 连续 8 次红（35534687472…36349065678）的结构性归因**：三红结构 = Chart Render（脚本 `set -e` 下 grep 无命中即退出，属脚本缺陷，已在 `f8d673d1` 修复）+ K3s Suite（构建上下文路径漂移，修复在本分支待核验）+ Playwright E2E（见 #26）。即"三红"并非三个独立产品缺陷，其中一个为 CI 脚本缺陷、两个为测试资产漂移 | nightly 长期红致信号疲劳，真实回归被淹没 | 逐项修复后以 nightly 实跑复核 | 脚本缺陷已修（`f8d673d1`）；K3s/Playwright 修复**待 nightly 核验** |
| 28 | **CodeQL 告警 7798/7799（`py/call-to-non-callable`，`platform/batch-pipeline/batch_pipeline/stages/_dispatch.py:68,70`）经复核为误报**：两处为函数引用传递（call site 传入 callable），非调用空值 | 告警噪声，干扰 CodeQL 真信号判读 | **裁决项**：①在 GitHub 侧 dismiss（理由 false positive）并留痕；②或改写成 CodeQL 可识别的形式（如显式包装函数）消除告警。二者均可，倾向①（改动零风险、留痕可审计） | 待裁决，未动代码。**2026-09-29 复核（check-run 109047756326 全部 57 条注记的线级归属）**：57 = **13 条落在本 PR 新增行 + 44 条与新增行无关（含全部 4 条 failure 级）** —— 4 条 failure 的创建时间均早于本 PR（alerts API 实测：`_dispatch.py` 7798/7799 = 2026-09-03 且该文件**不在本 PR diff 内**、`metrics_docs.py:36` py/polynomial-redos = 2026-08-26、query-api `auth.go` ×3 = 2026-08-07/08），即 GitHub 把整次分析的新告警都归到超大 PR 上属归属噪声，7798/7799 误报判定不因此改变。13 条 PR 行内 notice：9 条为 industry-templates 各模板 DAG 的 Airflow `t1 >> t2 >> t3` 依赖声明（CodeQL "Statement has no effect"，非缺陷、留置）；4 条本可清理项已修（`test_api.py:24` 同模块 import/import-from、`test_spark_iceberg.py:44` 未用导入、`services/__init__.py:149` 未用局部变量、`count-source-lines.py:30` 空 except）；另 2 条由本 PR 真实引入（`test_realtime_governance.py` fixture 混合 return + 空 except，线级工具因锚点行漂移漏计、经 `git diff -U0` 证实为 PR 新增行所致）也已修 —— 合计**本 PR 引入的 15 条 notice 已清 6、留置 9**（留置项均属 Airflow 惯用法，不建议为 notice 级别改业务语义） |
| 29 | **Trivy fs 扫出 78 MEDIUM（17 个唯一包-CVE 组合，run 36378306945 job 108788567998，产物 10951668978，exit 1 于日志 L463）**：均为依赖版本问题，非本仓代码缺陷 | Security 工作流红；78 条中多数属同一批传递依赖 | **裁决项**（三选一）：①逐批抬版（工作量最大、最彻底）；②按 severity 收窄门禁口径（如仅 `HIGH,CRITICAL` 阻断，MEDIUM 出报告不阻断）；③`.trivyignore` 逐条豁免并附理由（须防盗用） | 待裁决；事实清单：17 个唯一组合 |
| 30 | **Kind Smoke Test（job 108788910547）拉不到镜像**：`unexpected status from HEAD request to https://docker.m.daocloud.io/v2/sq-sql-gateway/manifests/0.1.0: 403 Forbidden`，另一源 `harbor.shuqing.io` 不可达；Chart 硬编码 `docker.m.daocloud.io/sq-*:0.1.0`。该 job 带 `continue-on-error`，故工作流仍绿 | 冒烟测试未真正验证集群部署成功；"绿"是假绿 | **裁决项**：①把 Chart 镜像源改为 CI 内可推送的本地 registry（kind load 镜像）；②或换用可用的公共镜像源并钉 digest；③或最简：把 `continue-on-error` 去掉使失败可见，先如实红 | 待裁决，未动配置 |
| 31 | **Markdown Lint 本轮为"潜在红"（job 因 `needs: integration-test` 被 skip，从未真正执行；首跑必红）**：实测 README.md exit 1、CONVENTIONS.md exit 1、`docs/**/*.md` 2212 违例 / 73 文件（默认规则集） | 一旦 IT 转绿，Markdown Lint 将成为下一块红牌（顺序性假绿掩盖） | 已修复：新增 `.markdownlint.json`（MD013 中文行宽/MD029 跨小节连续编号/MD033 内联 HTML/MD034 裸 URL/MD036 粗体当标题/MD040 无语言围栏——均按本仓文档惯例豁免；MD024 `siblings_only`；MD050 星号风格）+ `--fix` 66 文件 + 8 处手工修复（README、治理闭环修复草案、升级指南、审计报告、 remediation 方案组A、图样规范、docs/README、api-reference） | 已修，本地三条 lint 命令全部 exit 0，**待 CI 核验** |
| 32 | **K3s burn-in 组件 CrashLoop 根因（一类碰撞 + 一个独立项）**：① **K8s Service Link 端口注入碰撞（6 组件同型）** —— kubelet 会向 Pod 注入同命名空间各 Service 的 `<SVC>_PORT=tcp://IP:PORT` 链接变量；Python 组件（nl2sql/asset-exchange/industry-templates/llmops）的 pydantic-settings `env_prefix` 恰等于自身 Service 名 → `NL2SQL_PORT` 等被按整数解析而启动崩（burn-in 实测 run 36457429792/job 109047224953：门禁报 `未在期限内 Ready: nl2sql knowledge-engine`，nl2sql/KE/industry-templates 三 Pod 均 CrashLoopBackOff（重启 6 次）；nl2sql 容器日志 `ValidationError: port ... input_value='tcp://10.43.161.101:8093'`；本地对 4 个 Python 组件逐一注入同型值复现成功）。Go 组件（catalog/vector-engine）读 `os.Getenv("CATALOG_PORT")`/`VECTOR_ENGINE_PORT`（`platform/catalog/main.go:40,117`、`platform/vector-engine/main.go:47,76`、`internal/config/config.go:66`）后 `":"+port` 传 ListenAndServe，注入值使地址非法 → `log.Fatalf`（此前被 ImagePullBackOff 掩盖未暴露）；open-api-catalog 前缀 `OPENAPI_CATALOG_` 与注入名 `OPEN_API_CATALOG_PORT` 不同名，本地实测**不碰撞**（已剔除出修复范围）。② knowledge-engine 属独立根因：默认 storeType=nebula 强制要求 `NEBULA_USER`，无 Nebula 的 burn-in 栈启动即崩（日志 `nebulaUser 不能为空...`） | burn-in 链路组件大面积起不来，"K3s Suite" 长期红且不可归因；该碰撞类同样会击中任何按组件名设 env_prefix 的部署 | 已修复：7 个 k3s manifest（6 补 `enableServiceLinks: false` + KE 补 `KE_STORETYPE/KE_EXTRACTORTYPE=mock`，与 tests/integration/conftest.py 既有取值一致，生产仍走 Chart 的 nebula 凭证 Secret 路径）+ 6 个生产 Chart 同补 `enableServiceLinks: false`（组件间寻址均走 Service DNS/显式 URL，全仓无代码依赖链接变量；独立安装 Release=Chart 名会碰撞、umbrella 因 release 前缀侥幸避开 —— 统一关闭注入抹平两路径差异） | 已修，**待下次 nightly K3s 实跑核验**；Chart 侧 `helm lint/template` 全绿（纯插入），manifest 侧结构校验通过；约 16 个 ImagePullBackOff Pod 属 #30 镜像源口径，不在本条范围。**2026-09-29 复核补证**：open-api-catalog 的排除结论经 kind 实证维持——对 Service `open-api-catalog` 实测注入名为 `OPEN_API_CATALOG_PORT`（非 `OPENAPI_CATALOG_PORT`），且 pydantic 实测不解析该值（port 保持默认 8090、不崩）；全仓亦无名为 `openapi-catalog` 的 Service（chart 渲染为 `<release>-open-api-catalog`）。其 manifest 仅留注释说明，不设 `enableServiceLinks`。**2026-09-29 续三**：本批次写入的 `<SVC>_PORT=...` 注释 token 触发 `profile-render-check.sh:38` 的 `<.*>` 占位符正则（Local E2E 连续两推红：36462896041/36473626429），已改写为 `服务名_PORT`（4 chart + 4 manifest），本地四 profile 全 PASS |
| 33 | **`/error` 未放行 ⇒ 真实 4xx/5xx 被改写为 403（Spring Security 6 ERROR dispatch 级联）**。机制：`sendError()` 以 ERROR dispatch 重入过滤链，`JwtAuthFilter`（OncePerRequestFilter）默认跳过 ERROR dispatch 且已清空 SecurityContext → `anyRequest().authenticated()` 对 `/error` 判未认证 → 真实错误码被 AuthorizationFilter 一律改写为 403。burn-in 实测（run 36462895895 报告）：chain2 `POST /api/v1/clusters` 载荷 `environment:"CLOUD"`（非法枚举；合法值为 XINCHANG/BAREMETAL/CLOUD_HUAWEI/CLOUD_ALI/CLOUD_TENCENT/PRIVATE_VSPHERE/PRIVATE_OPENSTACK）本应 400，实测 `status=403, phase=None` | 全平台错误语义失真：4xx/5xx 被写成 403，排障与断言均被误导（chain2 create_cluster 即被此掩盖） | 已修复：全仓恰 6 个 `SecurityConfig.java`（find 实测，即全部安全链）补 `.requestMatchers("/error").permitAll()` —— `common-security` 公共链 + 5 个自定义链（data-standard / encaps-layer / governance/lineage-analyzer / karmada/federated-query / master-data） | 已修，本地实测（tag-engine 与 encaps-layer 重建镜像 404/400 不再被改写），**待 CI/nightly 核验** |
| 34 | **encaps-layer 缺 `/api/v1/health` 端点（chain4 全例 setup 超时的直接根因）**。burn-in 实测（run 36462895895 报告）：`Failed: K3s 服务 encaps-layer 健康检查超时（http://10.43.219.3:8080）`，8 例全部 ERROR at setup。k3s conftest 对该组件的健康路径为 `/api/v1/health`（tests/integration/k3s/conftest.py:67、205），该模块此前仅有 actuator 健康端点；请求落 404（并经 #33 遮盖为 403），就绪探测永不通过 | session 级 fixture 失败 ⇒ 链路4 全 8 例无法执行 | 已修复：新增 `HealthController`（encaps-layer/.../controller/HealthController.java，`GET /api/v1/health` → `{status:UP, component, version}`） | 已修，重建镜像本地实测匿名 200；**2026-09-29 续三（burn-in 实测）**：链路4 `test_encaps_health` 通过，端点获验证 |
| 35 | **infra-orchestrator provider URL 端口错配（chain2 `test_list_clusters` 读超时的根因）**。CI 实测（run 36462895895）：`请求异常: HTTPConnectionPool(host='10.43.38.235', port=8085): Read timed out. (read timeout=10)`。根因：`application.yml` provider 默认端口 8090-8093，而 k3s Provider Service 实际端口为 xinchang 8081 / baremetal 8080 / cloud 8084 / private 8084，集群内无 8090-8093 监听 → WebClient 连接黑洞拖满客户端 10s 读超时。修复：`deploy/k3s/manifests/infra-orchestrator.yaml` 补 4 个 `*_PROVIDER_URL` env 覆盖（代码侧 `listAllClusters` 逐环境 catch 异常返回 200，见 SupplyOrchestrator.java:304-314；无端点 Service 走 kube-proxy REJECT 快速失败）。测试侧：`test_create_cluster` 载荷 `CLOUD`→`XINCHANG`（合法枚举），断言改为验证编排 DAG 已执行（phase 为 SUCCEEDED/FAILED + events 含 provider-selected，容忍 500 fail-closed）——CI 仅构建 5 个链路镜像（nightly-e2e.yml），provider Pod 恒 ImagePullBackOff，真实创建在本环境不可达属环境约束 | chain2 2 例红（list_clusters 超时 + create_cluster 403 掩盖） | 已修复：manifest 端口对齐 + 测试语义对齐环境约束 | 已修；**2026-09-29 续三（burn-in 实测）**：read 超时已消失，链路2 仅余 `test_create_cluster`（响应 400、phase/events 空），待按后端校验定位；**2026-09-29 续四**：400 根因定位 = DTO 必填语义（见续四②与 #39①），已修复载荷——补 `skeEnabled`/`k8sVersion`/`podCidr`/`serviceCidr`/`nodes`（含 `role/count/cpuCores/memoryGb/diskGb`）；本地 jar 实测 DAG 已执行（500 + `provider-selected`+`request-transformed`+`provider-create-error`，provider Pod 恒 ImagePullBackOff 属环境约束）、pytest `1 passed` |
| 36 | **chain5 行业 Chart 两处修复**：① **ConfigMap data key 含 "/" 被 API Server 拒绝**——CI 实测（run 36462895895）：`ConfigMap "energy-template-assets" is invalid: [data[ddl/002_energy_usage.sql]: ... regex used for validation is '[-._a-zA-Z0-9]+' ...]`（12 键全列）。② **helm 对 post-install hook Job 阻塞等待**——即使不加 `--wait`，helm 3.14 本地实测 `Error: failed post-install: timed out waiting for the condition`；导入 Job 依赖 Doris/DS/Superset/Keycloak 等外部目标，隔离环境不可能完成；且 hook-delete-policy hook-succeeded 会删除成功 Job，"job exists" 类断言在成功路径同样不成立 | chain5 1 failed + 4 errors，行业模板无法真实安装 | 已修复：① 9 个行业 chart 的 `configmap-assets.tpl` key 由 `$path` 改为 `/`→`_` 替换版，同时 `import-job.tpl` volume 补 `items` 映射还原目录结构（key=ddl_x.sql → path=ddl/x.sql）；② 生命周期测试改 `--no-hooks`，Job 定义改由 `helm template` 渲染断言（`test_import_job_rendered_as_hook`：kind: Job + post-install 注解 + 名称） | 已修，本地 kind 实测 9/9 chart 通过（真实 install/uninstall + 挂载内容读回 `/templates/ddl/001_device_telemetry.sql`）；**2026-09-29 续三（burn-in 实测）**：链路5 全 9 例通过（含 `test_import_job_rendered_as_hook`），修复获验证 |
| 37 | **e2e 跨域 2 失败（run 36462895746 与 36457429958 同签名，`2 failed / 656 passed / 151 skipped`，预存非回归）**：① `test_asset_registration_to_exchange`——注册载荷缺 `qualityScore`，兼容路由 `POST /api/v1/assets` 的质量门槛（asset_service.py 的 list_asset：<60 拒收）拒掉默认 0.0，CI 实测 `{"error":"质量评分_0.0_<_60，不可上架"}`；修好质量分后本地又实测出第二层：兼容路由已是 register+publish 等价（注册后即 LISTED），再调 `POST /{id}/publish` 被 `asset_service.publish` 以 "资产当前状态 listed 不可上架"（422）拒绝（仅接受 DRAFT/PENDING_AUDIT/OFFLINE）。② `test_open_api_subscription_to_billing`——CI 实测订阅载荷缺 `subscriberTenantId`/`quotaExpect`（422）；更深层断链：CI 目录必为空（容器空库启动、docker 用例走 TestClient 不落库、compose 未注入 OPEN_API_CATALOG_URL），原硬编码 `data-query-api` 在服务端不存在（apply_subscription 首步 get_api 404），且 `/api/v1/usage`、`/api/v1/billing` 两端点全仓不存在 | Integration Test 最后 2 个红项 | 已修复：① 载荷补 `qualityScore:85.0`、枚举校正为 `table`、删除冗余 publish 步骤（加注释说明兼容路由语义）；② 重写为 自注册并发布（DRAFT→REVIEWING→APPROVED→RUNNING）→ 订阅（契约齐全）→ 审批发放 AK/SK → 真实调用 `POST /apis/{id}/call`（Mock upstream 返 200、断言 statusCode=200）→ finops 真实端点 `GET /api/v1/finops/billing/tenant` | 已修，两例均本地实测通过（真实 HTTP+sqlite 全链路），**待 CI 核验** |
| 38 | **链路4 五例 `status=200` 但断言字段全 `None` 的根因 = encaps-layer 全局 ApiResponseAdvice 信封（续三遗留，已修）**：`ApiResponseAdvice.java`（commit `c797f600a`）对 `/api/v1/**` 全部 controller 裸返回统一包装为 `{code,message,data,timestamp,success}`（`@JsonInclude(NON_NULL)`），业务字段在 `data`；链路4 断言在顶层取 `enabled`/`masked`/`allowed`/审计列表 → 恒 `None`。属测试侧契约错配：服务行为符合该模块统一规范（其自身前端与调用方均按信封消费，非服务缺陷） | 链路4 余 5 例红（含 `test_mask_then_audit` 复合例） | 已修复：`test_chain4_security_crypto.py` 新增 `_unwrap()`（dict 且含 `code`+`data` → 取 `data`，否则原样返回）并统一用于全部 8 例断言 | 已修，本地 8/8 实测通过（`8 passed, 1 warning in 11.62s`），**待 nightly 核验**；附注：信封覆盖 encaps-layer **全部** `/api/v1/**` 端点，其他测试/客户端接入该模块需同口径解包 |
| 39 | **infra-orchestrator 建集群接口"文档可选字段"实为 JSON 必填（DTO 校验语义）+ 前端载荷不匹配（两处：一处裁决、一处待修）**：① `ClusterCreateRequest` 用 `@Builder.Default` 标注默认值的字段（`skeEnabled`、NodeSpec 五整型）在 Jackson 反序列化缺席时报 400（本地实测 `Cannot map 'null' into type boolean/int`，平台经 `spring-boot-jackson2` 走 Jackson 2 默认语义）；`@NotBlank`（k8sVersion/podCidr/serviceCidr）与 `@NotEmpty`（nodes）在 `@Valid` 阶段先行（`ClusterController.createCluster` → `@Valid @RequestBody`），而 `tenantId` 的服务端注入（`request.setTenantId(requireTenant())`）在校验**之后** → 以最小载荷调用（前端/文档视角"只传必填"）必 400 且不产生任何编排事件。② `frontend/src/views/infra/InfraK8s.vue:573` 提交 `{environment, provider, clusterName, k8sVersion, podCidr, serviceCidr, workers:[{role,count,cpu,memory,disk}]}`：`workers`≠`nodes`、`cpu/memory/disk`≠`cpuCores/memoryGb/diskGb`、缺 `tenantId`/`skeEnabled` → `nodes` 缺失触发 `@NotEmpty`，UI 建集群必 400 | ① 契约歧义：客户端无法从文档判断真实必填集，校验报错为 Jackson 技术性信息而非业务语义；② 前端"创建集群"实际不可用 | **裁决/待修项**：① 建议 DTO 对有默认值字段改用可空包装类型或放开原始类型 null 策略，或将 `tenantId` 注入前移到校验前/文档明确必填集；② 前端按 DTO 修正字段名并补 `tenantId`/`skeEnabled`。两者均未在本分支改动（本轮仅按完整载荷修复 k3s 链路2 测试，见 #35 续四注记） | 待裁决，未动代码；证据：`ClusterCreateRequest.java`（字段注解）、`ClusterController.java`（`@Valid` 与注入顺序）、`InfraK8s.vue:573`、本地 jar 实测日志 |

---

## 变更日志

| 日期 | 变更 | 操作人 |
|------|------|--------|
| 2026-09-15 | 初始创建，清单化50个已知失败用例 | AI 审查流水线 |
| 2026-09-15 | Java 编译错误5个已修复清零 | AI 审查流水线 |
| 2026-09-26 | vue-tsc 类型错误全部清零（Drawer/Modal 改函数式 `:ref`）；IT 红项分诊（#20）；okhttp 排除（#21）；Serverless 路径修正（#18） | AI 审查流水线 |
| 2026-09-29 | 头部日期与计数刷新；新增 #24–#31（CI 红项分诊：QuotaController 角色、Dockerfile 预装、Playwright 13 例、nightly 结构性归因、CodeQL 误报、Trivy fs、Kind Smoke、Markdown Lint）；#23 标记已解决；#20 追加 2026-09-29 收敛明细 | AI 审查流水线 |
| 2026-09-29 | 同日续：新增 #32（K3s Service Link 端口注入碰撞：6 组件同型、7 manifest + 6 Chart 修复，待 nightly 核验）；#28 追加 57 条注记线级归属复核（本 PR 引入 15 条 notice 已清 6 / 留置 9）；头部计数 33→34 | AI 审查流水线 |
| 2026-09-29 | 同日续二：新增 #33–#37（K3s burn-in 三链根因：/error 403 掩盖全仓 6 链修复、encaps-layer 补 /api/v1/health、orchestrator provider URL 错配；chain5 ConfigMap 非法 key + hook 阻塞语义；e2e 跨域 2 失败重写并本地实测通过）；#20 追加续二实测（32 failed → 2 failed）；#32 补 open-api-catalog 的 kind 复核证据（不碰撞结论维持）；头部计数 34→39 | AI 审查流水线 |
| 2026-09-29 | 同日续三：修复 #32 批次注释 token `<SVC>` 触发占位符检查导致的 Local E2E 回归（8 文件改 `服务名_PORT`，本地四 profile 全 PASS，步骤 2 以 CI chart-render `PASS=97` + 离线 schema 复验兜底）；补记 K3s burn-in 实测（链路 1/3/5 全过；#34/#36 获验证；链路2 余 `test_create_cluster` 400、链路4 余 security 契约 5 例待诊） | AI 审查流水线 |
| 2026-09-29 | 同日续四：定位并修复续三遗留两处测试侧契约错配（链路4 解 ApiResponseAdvice 信封统一 `_unwrap`；链路2 补全 ClusterCreateRequest 必填载荷），双双本地实跑通过（8/8 与 1 passed）；新增 #38（信封契约，已修）、#39（DTO"可选实为必填"语义 + 前端 `workers`/`nodes` 不匹配，裁决项）；Local E2E Profile Render Check 在 7ee8ea87 获 CI 验证；头部计数 39→41 | AI 审查流水线 |
