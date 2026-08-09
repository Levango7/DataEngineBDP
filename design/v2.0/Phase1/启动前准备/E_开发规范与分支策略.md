# 数据引擎大数据平台 V2.0 Phase 1a · 开发规范与分支策略

> 版本：v1.0
> 文档状态：生效
> 编写日期：2026-08-06
> 文档负责人：Phase1a 启动前准备工程师（任务 148）
> 适用范围：V2.0 Phase 1a（2026-09-01 ~ 2027-04-22）全部 24 个任务、29 人团队、6 个领域组的代码协作
> 上游输入：
> - `design/v2.0/Phase1/Phase1_详细执行计划.md`（§7.1 代码审查要求 / §7.2 测试覆盖 / §7.3 CI/CD / §7.4 验收标准 / §7.5 质量门禁）
> - `CONTRIBUTING.md`（已有提交规范与分支策略基础）
> - `CONVENTIONS.md`（命名与约定单一事实来源）
> 下游输出：分支策略、PR 模板、代码规范、Review 流程、质量门禁、提交规范

---

## 第1章 分支策略选择与理由

### 1.1 候选策略对比

Phase 1a 面临 29 人团队并行开发 24 个任务、subagent 并行上限 5 个、关键路径 149 人天、V1.0 向后兼容硬约束等场景，需在 Feature 分支策略与 Trunk-based 开发之间做出选择。

表：E-01 分支策略对比对照表

| 维度 | Feature 分支策略 | Trunk-based 开发 |
| --- | --- | --- |
| 核心机制 | 每个任务/子任务创建 feature 分支，完成后 PR 合并到 main | 所有人直接提交到 main，用 feature flag 控制未完成功能 |
| 团队规模适配 | 适合 10~50 人中型团队，分支隔离性好 | 适合 ≤10 人小团队或高度成熟 CI/CD 的团队 |
| 并行开发支持 | 24 任务各自分支，互不干扰 | 24 任务同主干，需大量 feature flag 与抽象分支 |
| 代码 review | PR 强制 review，每条变更可追溯 | 短分支 + 频繁合并，review 节奏快但单次范围小 |
| 集成冲突 | 合并时集中解决冲突 | 持续小步集成，冲突分散但频次高 |
| V1.0 兼容保障 | 分支隔离未完成功能，main 始终可发布 | 依赖 feature flag 严控未完成功能暴露 |
| subagent 协作 | subagent 各自分支，PR 串行 review | subagent 同主干提交，需强约定提交粒度 |
| 关键路径任务 | T008/T009/T005/T010/T011 长任务分支独立，不污染主干 | 60 人天的 T010 长期在主干半成品，风险高 |
| 回滚成本 | 分支丢弃或 PR revert，影响可控 | 主干回滚牵连多任务，需 feature flag 关闭 |
| 工具链成熟度 | GitHub PR / Branch Protection 原生支持 | 需额外 feature flag 框架（OpenFeature / Unleash） |

### 1.2 选择结论

**采用 Feature 分支策略**，并叠加三层分支（main / develop / feature）以兼顾集成稳定性与发布可控性。

### 1.3 选择理由

1. **团队规模适配**：29 人团队 + 6 领域组并行，Feature 分支天然隔离领域组变更，避免主干被半成品污染。Trunk-based 在 29 人规模下 feature flag 管理成本陡增（24 任务 × 平均 3 flag ≈ 72 个 flag），易出现 flag 遗漏导致未完成功能暴露。
2. **关键路径长任务保护**：T010（NL2SQL 核心引擎，60 人天）若走 Trunk-based，主干将长期承载半成品代码，违反"main 始终可发布"原则。Feature 分支让 T010 在独立分支迭代 60 天，main 不受影响。
3. **V1.0 向后兼容硬约束**：V1.0 客户端零改动可继续工作是 PRD 明确要求。Feature 分支隔离破坏性变更，PR 合并时由 Reviewer 显式审核兼容性，比 feature flag 关闭更可靠（flag 误开即破坏兼容）。
4. **subagent 并行上限 5 个**：5 个 subagent 各持一个 feature 分支，PR 串行 review，节奏可控。Trunk-based 下 5 个 subagent 同主干提交，提交粒度与顺序约定难落地。
5. **回滚成本可控**：批次门禁不通过时，丢弃对应 feature 分支或 revert 单个 PR 即可，不影响其他领域组。Trunk-based 主干回滚会牵连同期合并的其他任务。
6. **工具链原生支持**：GitHub Branch Protection + Required Reviews + Status Check 原生支持 Feature 分支策略，无需额外引入 feature flag 框架。

### 1.4 分支模型

表：E-02 分支模型参数说明表

| 分支 | 用途 | 命名规范 | 保护策略 | 合并来源 |
| --- | --- | --- | --- | --- |
| `main` | 生产分支，始终可发布，对应已通过 Phase 1 出口门禁的版本 | `main` | 强保护：禁止直接 push，需 2 名 Reviewer + CI 全绿 + 首席架构师批准 | `release/*` / `hotfix/*` |
| `develop` | 集成分支，承载最新开发成果，每日自动部署到 dev 环境 | `develop` | 强保护：禁止直接 push，需 1 名 Reviewer + CI 全绿 | `feature/*` / `fix/*` / `hotfix/*` |
| `feature/*` | 功能开发分支，对应单个任务或子任务 | `feature/<TaskID>-<short-desc>` | 无强制保护，owner 可 push | 从 `develop` 拉出 |
| `fix/*` | 缺陷修复分支 | `fix/<TaskID>-<short-desc>` | 无强制保护 | 从 `develop` 拉出 |
| `release/*` | 发布准备分支，用于里程碑发布前 stabilization | `release/v<x.y.z>` | 强保护：仅允许 bug fix 提交 | 从 `develop` 拉出 |
| `hotfix/*` | 生产紧急修复分支 | `hotfix/<TaskID>-<short-desc>` | 无强制保护 | 从 `main` 拉出 |

### 1.5 分支命名示例

```
feature/T008-multimodal-slicer
feature/T010a-nl2sql-core-engine
feature/T010b-nl2sql-multi-turn-clarify
fix/T014-flink-cdc-debezium-format
release/v2.0.0-alpha-1
hotfix/T022-crypto-spi-factory-sm2
```

### 1.6 分支流转规则

图：E-01 分支流转流程图

```
  ┌─────────────┐    PR (1 reviewer + CI)    ┌─────────────┐
  │  feature/*  │ ─────────────────────────▶ │  develop    │
  └─────────────┘                            └─────────────┘
        ▲                                          │
        │ from develop                             │ Release PR
        │                                          ▼
        │                                    ┌─────────────┐
        │                                    │  release/*  │
        │                                    └─────────────┘
        │                                          │
        │                                          │ merge + tag
        │                                          ▼
        │                                    ┌─────────────┐
        │       hotfix merge back            │    main     │
        │ ────────────────────────────────── │             │
        └────────────────────────────────────┤             │
                                             └─────────────┘
                                                    ▲
                                                    │ from main
                                                    │
                                              ┌─────────────┐
                                              │  hotfix/*   │
                                              └─────────────┘
```

1. 从 `develop` 拉出 `feature/*` 或 `fix/*` 分支开发，命名包含任务 ID。
2. 开发完成后向 `develop` 发起 Pull Request，触发 PR 合并门禁（§5.1）。
3. `develop` 定期（每批次完成或里程碑达成）向 `main` 发起 Release PR，合并后打 tag 发布。
4. 生产紧急修复从 `main` 拉出 `hotfix/*`，修复后同时合并回 `main` 与 `develop`。
5. **禁止**直接 push 到 `main` / `develop` / `release/*`，必须经 PR。

### 1.7 分支生命周期与清理

- `feature/*` / `fix/*`：合并后立即删除，避免分支堆积。GitHub 配置 "Automatically delete head branches"。
- `release/*`：发布完成后保留 30 天用于 hotfix，之后删除。
- `hotfix/*`：合并后立即删除。
- 长期分支仅保留 `main` 与 `develop` 两条。

---

## 第2章 PR 模板说明

### 2.1 模板文件

PR 模板已创建于 `.github/pull_request_template.md`，GitHub 在创建 PR 时自动加载。

### 2.2 模板章节结构

表：E-03 PR 模板章节参数说明表

| 章节 | 必填 | 用途 | 规范依据 |
| --- | --- | --- | --- |
| 变更描述（What & Why） | 是 | 阐明做了什么、为什么做，聚焦动机与价值 | Conventional Commits body |
| 关联任务 | 是 | 关联任务 ID、批次、领域组、Issue、前置 PR | Phase1_详细执行计划 §3 批次排期 |
| 变更类型 | 是 | feat / fix / refactor / perf / docs / test / chore / ci / build / style / revert | Conventional Commits type |
| 影响范围 | 是 | 勾选受影响组件，便于 Reviewer 分配与影响面评估 | §4.2 Reviewer 分配规则 |
| 测试方式 | 是 | 单元 / 集成 / E2E / 性能 / 手工，附命令与结果 | Phase1_详细执行计划 §7.2 |
| 验收标准检查清单 | 是 | 关联 §7.4 验收标准序号，逐项附证据 | Phase1_详细执行计划 §7.4 |
| 代码审查自检清单 | 是 | 提交前 9 项自检，全部勾选方可请求 Reviewer | CONTRIBUTING.md PR 流程 |
| Breaking changes | 是 | 显式声明破坏性变更与迁移方案；无则勾选"不包含" | V1.0 向后兼容约束 |
| 依赖变更 | 否 | 新增 / 升级 / 移除直接依赖说明 | CONTRIBUTING.md 提交前自检 |
| Reviewer 指定建议 | 是 | 按 §4.2 规则推荐 Reviewer，架构变更需 2 名含首席架构师 | §4.2 Reviewer 分配规则 |
| 截图 / 日志 | 否 | UI 变更附截图，部署变更附日志，性能变更附基线对比 | — |
| 部署影响 | 否 | Helm Chart / 数据库迁移 / 配置项 / 灰度策略 | §5.2 批次完成门禁 |
| 风险与回滚 | 是 | 风险等级与回滚方式 | §5.4 Phase 1 出口门禁 |

### 2.3 PR 标题规范

PR 标题与首条提交消息格式一致，遵循 Conventional Commits：

```
<type>(<scope>): <subject>
```

示例：

```
feat(encaps-layer): 新增工作空间 CRUD 与 K8s Namespace 翻译
fix(ske): 修复 Cilium socketLB.mode 非法值导致集群拉起失败
perf(sql-gateway): Trino 代理连接池化，P95 从 1.2s 降至 0.4s
```

### 2.4 合并方式

- **默认 Squash Merge**：保持 `main` / `develop` 历史线性，每个 PR 对应一个提交。
- **大特性 Rebase Merge**：当 PR 包含多个语义独立的提交且需保留提交粒度时使用，需 Reviewer 同意。
- **禁止 Create Merge Commit**：避免产生 merge commit 污染主干历史。

---

## 第3章 各语言代码规范要点

### 3.1 通用规范（全语言适用）

- 缩进使用空格，不使用 Tab。
- 文件末尾保留一个空行。
- 行尾去除多余空格。
- 文件编码 UTF-8，换行符 LF。
- 不提交 IDE 配置文件（`.idea` / `.vscode`），通过 `.gitignore` 排除。
- 命名遵循 `CONVENTIONS.md`：套餐 `base` / `standard` / `flagship`，工作空间 `ws-<name>`，模块 49，SKE v0.1。

### 3.2 Java 规范

**基线**：阿里巴巴 Java 开发手册 + Spring Boot 3.2 最佳实践。

表：E-04 Java 规范要点参数说明表

| 维度 | 规范 | 示例 |
| --- | --- | --- |
| 命名-类 | PascalCase，后缀语义化 | `TenantServiceImpl`、`OrchestrationService`、`CryptoSpiFactory` |
| 命名-方法/变量 | camelCase，动词开头 | `createTenant`、`tenantId`、`routeSql` |
| 命名-常量 | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT`、`DEFAULT_PAGE_SIZE` |
| 命名-包 | 全小写，单层单词 | `com.levango7.dataenginebdp.encaps.tenant` |
| 命名-枚举 | UPPER_SNAKE_CASE | `TenantStatus.ACTIVE`、`CryptoProfile.GM` |
| 异常处理 | 业务异常抛 `BusinessException`，系统异常抛 `SystemException`，禁止裸抛 `RuntimeException` / `Throwable` | `throw new BusinessException("TENANT_NOT_FOUND", "租户不存在: " + id);` |
| 异常处理-捕获 | 不吞异常，catch 块必须日志或重新抛出；禁止 `catch (Exception e) {}` | — |
| 日志规范 | 使用 SLF4J + Logback；禁止 `System.out.println`；日志含 traceId / tenantId 上下文 | `log.info("[tid={}] create tenant: {}", MDC.get("tid"), tenant);` |
| 日志级别 | ERROR 系统故障 / WARN 业务异常 / INFO 关键路径 / DEBUG 调试 / TRACE 细粒度 | — |
| 空值处理 | 使用 `Optional<T>` 表达可能缺失的返回值；参数非空校验用 `Objects.requireNonNull` / Spring `@NonNull` | `public Optional<Tenant> findById(String id)` |
| 空值-入参 | Service 层 public 方法入参用 `@Valid` + JSR-303 注解校验 | `@NotBlank String tenantId` |
| Spring Boot | 优先构造器注入（`@RequiredArgsConstructor`），禁止字段注入 `@Autowired` | — |
| Spring Boot-事务 | `@Transactional` 仅在 Service 层，rollbackFor 默认包含 RuntimeException，业务异常需显式 rollbackFor | `@Transactional(rollbackFor = BusinessException.class)` |
| 并发 | 优先 `CompletableFuture` / `Reactor`，共享状态用 `ConcurrentHashMap`，禁止 `synchronized` 在 Service 层 | — |
| 测试 | JUnit 5 + Mockito + AssertJ；测试名 `should<期望>When<条件>` | `shouldThrowWhenTenantNotFound` |
| 覆盖率 | 行 ≥80%，关键路径任务（T005/T010/T012）≥90%，分支 ≥70% | — |

**静态检查**：SpotBugs + PMD + Checkstyle（阿里巴巴规则集）+ SonarQube，CI 强制通过。

### 3.3 Go 规范

**基线**：Effective Go + 项目特定规范。

表：E-05 Go 规范要点参数说明表

| 维度 | 规范 | 示例 |
| --- | --- | --- |
| 命名-包 | 全小写单单词，无下划线 / 大写 | `catalog`、`handler`、`store` |
| 命名-导出标识符 | PascalCase，必须有注释且以标识符名称开头 | `// Catalog represents metadata catalog.` |
| 命名-未导出标识符 | camelCase | `tenantID`、`routeTable` |
| 命名-接口 | 单方法接口名以 -er 后缀 | `type Router interface { Route(...) }` |
| 错误处理 | 不忽略 error 返回值；使用 `errors.Is` / `errors.As` 判断类型；自定义错误用 `fmt.Errorf` + `%w` 包装 | `if err != nil { return fmt.Errorf("query tenant: %w", err) }` |
| 错误处理-panic | 仅在不可恢复的初始化阶段 panic，业务路径禁止 panic | — |
| context 传递 | 所有可能阻塞的 public 方法首参为 `context.Context`，禁止用 `nil` context | `func (s *Service) Find(ctx context.Context, id string) (*Tenant, error)` |
| context-取消 | 长任务监听 `ctx.Done()` 主动退出；禁止用 `context.Background()` 在 HTTP handler 中 | — |
| goroutine 安全 | 共享状态用 channel 或 `sync.Mutex` / `sync.RWMutex`；禁止 goroutine 泄漏（必须有退出路径） | — |
| goroutine-启动 | 禁止在库代码中无节制 `go f()`，必须有 worker pool 或并发度限制 | — |
| 错误处理-包级错误 | 公开错误用 `var ErrXxx = errors.New(...)` 哨兵错误，或自定义 error 类型 | `var ErrTenantNotFound = errors.New("tenant not found")` |
| 测试 | 标准库 `testing` + `testify`；测试名 `Test<函数>_<条件>` | `TestFindTenant_NotFound` |
| 覆盖率 | 行 ≥80%，关键路径任务 ≥90%，`go test -coverprofile` 生成 | — |

**静态检查**：`gofmt -s -w .` + `go vet ./...` + `golangci-lint`（启用 errcheck / gocritic / revive / staticcheck / unused），CI 强制通过。

### 3.4 Python 规范

**基线**：PEP 8 + 项目 `.pylintrc` + 类型注解。

表：E-06 Python 规范要点参数说明表

| 维度 | 规范 | 示例 |
| --- | --- | --- |
| 命名-模块/包 | 全小写下划线分隔 | `nl2sql_engine`、`multimodal_slicer` |
| 命名-类 | PascalCase | `MultiModalSlicer`、`HybridRetriever` |
| 命名-函数/变量 | snake_case | `def build_sql_from_nl(nl: str) -> str:` |
| 命名-常量 | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT = 3` |
| 类型注解 | 全量类型注解，`mypy --strict` 通过；禁止 `Any`，必要时用 `object` 后做类型收窄 | `def slice(doc: Document) -> list[Chunk]:` |
| 类型-Protocol | 依赖抽象用 `typing.Protocol` 而非具体类 | — |
| 异步编程 | I/O 密集用 `async def`，CPU 密集用 `def` + 线程池；禁止在 async 函数中调用阻塞 I/O | `async def retrieve(query: str) -> list[Doc]:` |
| 异步-并发 | 优先 `asyncio.gather` 并发，控制并发度用 `asyncio.Semaphore` | — |
| 异常处理 | 自定义异常继承 `Exception`，不继承 `BaseException`；捕获具体异常不捕获裸 `Exception` | `class NL2SQLParseError(Exception): pass` |
| 异常处理-raise | 业务异常带错误码与上下文 | `raise NL2SQLParseError(code="INVALID_SQL", detail=sql)` |
| 日志 | 使用 `logging` 或 `structlog`，含 trace_id / tenant_id 绑定 | `logger.info("nl2sql_parse", tenant_id=tid, sql=sql)` |
| docstring | 函数 / 类必须有 docstring，Google 风格 | `"""Slice multimodal document into chunks. Args: doc: Input document. Returns: List of chunks."""` |
| 测试 | `pytest` + `pytest-asyncio`；测试名 `test_<期望>_when_<条件>` | `test_raise_when_sql_invalid` |
| 覆盖率 | 行 ≥80%，关键路径任务（T008/T009/T010）≥90%，`pytest --cov` 生成 | — |

**静态检查**：`black` + `isort` + `ruff`（替代 flake8 / pylint）+ `mypy --strict`，CI 强制通过。

### 3.5 前端规范（Vue3 + TypeScript）

**基线**：Vue3 Composition API + TypeScript strict 模式。

表：E-07 前端规范要点参数说明表

| 维度 | 规范 | 示例 |
| --- | --- | --- |
| 命名-组件 | PascalCase，文件名与组件名一致 | `TenantManagement.vue` → `TenantManagement` |
| 命名-组合式函数 | camelCase，以 `use` 开头 | `useApi`、`useTenant`、`useAuth` |
| 命名-Store | camelCase，以 `use` 开头 + `Store` 后缀 | `useAuthStore`、`useTenantStore` |
| 命名-props/emits | camelCase 声明，kebab-case 模板传递 | `props: { tenantId: String }` → `<comp :tenant-id="..." />` |
| TypeScript | `strict: true`；禁止 `any`，必要时用 `unknown` 后做类型收窄；启用 `noUncheckedIndexedAccess` | — |
| 组合式 API | 统一使用 `<script setup lang="ts">`；禁止 Options API（新代码） | — |
| 状态管理 | Pinia，禁止 Vuex；Store 定义使用 setup 语法 | `const useAuthStore = defineStore('auth', () => { ... })` |
| 路由 | Vue Router 4，路由懒加载 `() => import(...)`；路由名 PascalCase | `{ path: '/tenant', component: () => import('@/views/TenantManagement.vue') }` |
| 样式 | 优先 scoped CSS + CSS 变量；禁止全局样式污染；设计 token 统一在 `src/styles/tokens.css` | — |
| API 调用 | 统一通过 `src/api/` 封装 HTTP 客户端 + 拦截器；禁止组件内直接 `fetch` / `axios` | — |
| 错误处理 | 全局错误边界 + 局部 `try/catch`；用户友好错误提示 | — |
| 测试 | `vitest` + `@vue/test-utils`；组件测试覆盖渲染 / 交互 / props / emits | — |
| 覆盖率 | 行 ≥80%，关键路径任务（T007/T011）≥90% | — |

**静态检查**：`npm run lint`（ESLint + Vue Plugin）+ `npm run type-check`（vue-tsc），CI 强制通过。

### 3.6 各语言 CI 检查矩阵

表：E-08 各语言 CI 检查矩阵对照表

| 语言 | 格式化 | 静态检查 | 单元测试 | 覆盖率 | 构建产物 |
| --- | --- | --- | --- | --- | --- |
| Java | Spotless (palantir-java-format) | SpotBugs + PMD + Checkstyle + SonarQube | JUnit 5 + Mockito | JaCoCo (≥80% / 关键 ≥90%) | Maven → Docker 镜像 |
| Go | `gofmt -s` | `go vet` + `golangci-lint` | `go test` | `go test -cover` (≥80% / 关键 ≥90%) | `go build` → Docker 镜像 |
| Python | `black` + `isort` | `ruff` + `mypy --strict` | `pytest` | `pytest --cov` (≥80% / 关键 ≥90%) | `pip install` → Docker 镜像 |
| 前端 | ESLint --fix | ESLint + vue-tsc | `vitest` | `vitest --coverage` (≥80% / 关键 ≥90%) | `npm run build` → 静态资源 |

---

## 第4章 Review 流程

### 4.1 Review 检查项

表：E-09 Review 检查项参数说明表

| 检查维度 | 检查内容 | 责任人 | 触发条件 |
| --- | --- | --- | --- |
| 架构合规 | 符合 V2.0 架构设计文档的模块边界与接口契约；不引入跨层直接依赖 | 首席架构师 | 涉及跨模块接口 / 公共 API 变更的 PR |
| 代码规范 | Java 遵循阿里巴巴 Java 开发手册；Go 遵循 Effective Go；Python 遵循 PEP 8 + .pylintrc；前端遵循 §3.5 | 各领域组主责人 | 每个 PR |
| 安全审查 | 加解密 / 鉴权 / 脱敏代码由安全合规工程师审查；SQL 注入 / XSS / CSRF 防护；密钥不硬编码 | 安全合规工程师 A | T020 / T021 / T022 / T023 相关 PR 及任何涉及鉴权 / 加密 / 脱敏的变更 |
| 性能审查 | 关键路径任务（T008 / T009 / T005 / T010 / T011）的算法复杂度与延迟优化；P95 达标 | AI 架构师 | 关键路径任务 PR |
| Mock 清零审查 | 真实实现路径已激活，默认配置不走 Mock；Mock 残留需在 PR 描述说明 | 各领域组主责人 | 涉及 Mock 清零的 PR |
| 测试覆盖审查 | 单元测试覆盖率 ≥80%（关键路径 ≥90%），分支覆盖率 ≥70%；测试覆盖正常 / 边界 / 异常路径 | 测试工程师 | 每个 PR |
| V1.0 兼容审查 | V1.0 API 向后兼容；Breaking changes 已显式声明并附迁移方案 | 首席架构师 | 涉及公共 API / 数据库 Schema / 配置项变更的 PR |
| 文档同步审查 | 公开 API 有 Javadoc / docstring / TSDoc；设计文档与代码一致 | 各领域组主责人 | 涉及接口变更的 PR |

### 4.2 Reviewer 分配规则

按领域组分配主责 Reviewer，确保 Reviewer 熟悉该领域上下文。

表：E-10 Reviewer 分配规则对照表

| 领域组 | 主责 Reviewer | 备选 Reviewer | 升级 Review 触发 |
| --- | --- | --- | --- |
| 云原生组（T001/T002/T003/T004） | 云原生架构师 | DevOps 工程师 A | 涉及 Service Mesh / ArgoCD 全局配置 |
| AI 组（T005~T011） | AI 架构师 | Python 工程师 A / Go 工程师 A | 关键路径任务 T008/T009/T005/T010/T011 |
| 数据联邦组（T012/T013） | 首席架构师 | Java 工程师 C | Calcite 优化器规则变更 |
| 实时数仓组（T014~T017） | Java 工程师 E | Java 工程师 F | Flink CDC / Iceberg V2 / Doris 物化视图核心算法 |
| 行业模板组（T018/T019） | Java 工程师 G | DevOps 工程师 D | 金融模板 DDL / DAG 结构变更 |
| 安全合规组（T000/T020~T023） | 安全合规工程师 A | 安全合规工程师 B + Java 工程师 I | 等保控制项 / 国密算法 / SecurityFacade |
| 横切支持（前端 / 测试 / 集成） | 前端工程师 A / 测试工程师 | 前端工程师 B | 控制台信息架构 / E2E 框架变更 |

**升级 Review 规则**：

1. 涉及跨模块接口变更：必须 2 名 Reviewer，其中 1 名为首席架构师。
2. 涉及公共 API 变更（HTTP / gRPC / CLI）：必须 2 名 Reviewer，含领域组主责 + 首席架构师。
3. 关键路径任务（T008/T009/T005/T010/T011）：必须 2 名 Reviewer，含 AI 架构师。
4. 安全敏感代码（鉴权 / 加密 / 脱敏 / 等保）：必须 2 名 Reviewer，含安全合规工程师 A。
5. 数据库 Schema 变更：必须 2 名 Reviewer，含首席架构师 + 领域组主责。

### 4.3 Review SLA

表：E-11 Review SLA 参数说明表

| PR 类型 | 首次响应 SLA | 完成 Review SLA | 超时处理 |
| --- | --- | --- | --- |
| 普通 PR | 24 小时内首次响应 | 48 小时内完成 | 自动转备选 Reviewer，主责人周会说明 |
| 关键路径任务 PR（T008/T009/T005/T010/T011） | 12 小时内首次响应 | 24 小时内完成 | 立即转 AI 架构师 + 备选，项目经理预警 |
| 安全敏感 PR（T020~T023 / 鉴权 / 加密） | 12 小时内首次响应 | 24 小时内完成 | 立即转安全合规工程师 A + B，首席架构师预警 |
| Hotfix PR | 4 小时内首次响应 | 8 小时内完成 | 立即升级首席架构师，每日站会跟踪 |

### 4.4 Review 流程

图：E-02 Review 流程示意图

```
PR 提交
   │
   ▼
CI 自动检查（代码检查 + 单元测试 + 覆盖率）
   │
   ├── 失败 ──▶ 作者修复 ──▶ 重新触发 CI
   │
   ▼ 通过
作者按 §4.2 指定 Reviewer（GitHub "Reviewers" 字段）
   │
   ▼
Reviewer 审查（§4.1 检查项）
   │
   ├── Request changes ──▶ 作者修复 ──▶ 重新请求 review
   │
   ▼ Approve
升级 Review 判定
   │
   ├── 需升级（跨模块 / 公共 API / 关键路径 / 安全 / Schema）
   │        │
   │        ▼
   │     第二名 Reviewer（首席架构师 / AI 架构师 / 安全合规工程师 A）
   │        │
   │        ├── Request changes ──▶ 作者修复
   │        │
   │        ▼ Approve
   │
   ▼
PR 合并门禁全绿（§5.1） ──▶ Squash Merge 到 develop
   │
   ▼
分支自动删除
```

### 4.5 Review 评论规范

- **必须**：评论指明具体文件 + 行号 + 问题类型（must fix / should fix / nit / question / praise）。
- **禁止**：泛化评论（"代码风格不好" / "建议优化"）。
- **must fix**：阻塞合并的问题（规范违反 / 逻辑错误 / 安全漏洞 / 测试缺失）。
- **should fix**：建议修改但不阻塞（可读性 / 性能小优化）。
- **nit**：微小改进（命名 / 注释）。
- **praise**：表扬良好实践（鼓励正向反馈）。

---

## 第5章 质量门禁

基于 Phase1_详细执行计划 §7.5，建立四级质量门禁。所有门禁为硬门禁，不通过即阻塞。

### 5.1 PR 合并门禁

**触发时机**：PR 合并到 `develop` / `main` / `release/*` 时。

表：E-12 PR 合并门禁参数说明表

| 门禁项 | 工具 | 标准 | 不通过处理 |
| --- | --- | --- | --- |
| 代码检查 | SonarQube + SpotBugs/PMD/Checkstyle + golangci-lint + ruff + ESLint | 0 Blocker / 0 Critical / 0 Major；复杂度 / 重复率达标 | 阻塞合并，作者修复 |
| 单元测试 | JUnit 5 / pytest / go test / vitest | 全部通过 | 阻塞合并，作者修复 |
| 覆盖率 | JaCoCo / pytest-cov / go test -cover / vitest --coverage | 行 ≥80%（关键路径 ≥90%），分支 ≥70% | 阻塞合并，作者补测试 |
| 代码审查 | GitHub Review | 至少 1 名 Reviewer Approve（升级 Review 需 2 名） | 阻塞合并，等待 Review |
| 提交规范 | commitlint | 符合 Conventional Commits | 阻塞合并，作者修正提交消息 |
| V1.0 兼容 | 兼容性测试（V1.0 测试套件回归） | V1.0 客户端零改动可继续工作 | 阻塞合并，作者调整 |

### 5.2 批次完成门禁

**触发时机**：每个批次（批次 1~5）全部任务完成时。

表：E-13 批次完成门禁参数说明表

| 门禁项 | 标准 | 不通过处理 |
| --- | --- | --- |
| 集成测试 | 该批次所有任务的产出物与上下游集成测试通过（Testcontainers + httpx） | 阻塞下一批次启动，主责人修复 |
| 验收标准检查清单 | Phase1_详细执行计划 §7.4 中该批次对应验收项全部通过 | 阻塞下一批次启动，主责人修复 |
| Mock 清零 | 该批次涉及组件的 Mock 已清零，真实实现路径激活 | 阻塞下一批次启动，主责人清零 |
| 批次完成报告 | 各领域组主责人提交批次完成报告（成果 / 验收 / 问题） | 阻塞下一批次启动，主责人补报告 |

### 5.3 里程碑门禁

**触发时机**：每个里程碑（M1-Base / M2-Core / M3-Engine / M4-AI / M5-Alpha）达成时。

表：E-14 里程碑门禁参数说明表

| 门禁项 | 标准 | 不通过处理 |
| --- | --- | --- |
| 交付物验收 | 该里程碑所有交付物通过验收（功能 / 性能 / 安全 / 兼容） | 阻塞下一里程碑，首席架构师决策 |
| 性能基准 | 性能指标不退化（与基线对比：NL2SQL ≥90% / RAG P95 ≤2s / 入仓 P95 ≤5s / 联邦 P95 ≤10s） | 阻塞下一里程碑，性能问题修复 |
| 里程碑评审报告 | 首席架构师提交里程碑评审报告 | 阻塞下一里程碑，补评审 |

### 5.4 Phase 1 出口门禁

**触发时机**：M5-Alpha 里程碑达成，V2.0-alpha 发布前。

表：E-15 Phase 1 出口门禁参数说明表

| 门禁项 | 标准 | 不通过处理 |
| --- | --- | --- |
| 验收标准全通过 | Phase1_详细执行计划 §7.4 全部 24 项验收标准通过 | 阻塞 V2.0-alpha 发布，整体修复 |
| P0 需求 E2E | 11 项 P0 需求端到端测试全通过 | 阻塞发布，整体修复 |
| 性能指标达标 | NL2SQL ≥90% / RAG P95 ≤2s / 入仓 P95 ≤5s / 联邦 P95 ≤10s | 阻塞发布，性能优化 |
| 等保三级可测评 | 等保三级 6 类控制项全部落地，密评控制项落地 | 阻塞发布，安全整改 |
| V1.0 兼容 | V1.0 测试套件全回归通过 | 阻塞发布，兼容性修复 |
| Phase 1 完成报告 | 首席架构师提交 Phase 1 完成报告 | 阻塞发布，补报告 |

---

## 第6章 提交规范

### 6.1 Conventional Commits 格式

本项目遵循 [Conventional Commits 1.0.0](https://www.conventionalcommits.org/zh-hans/v1.0.0/) 规范。

```
<type>(<scope>): <subject>

<body>

<footer>
```

### 6.2 type 取值

表：E-16 Conventional Commits type 参数说明表

| type | 含义 | 是否触发 Release |
| --- | --- | --- |
| `feat` | 新功能 | 是（minor） |
| `fix` | Bug 修复 | 是（patch） |
| `refactor` | 重构（既非新增功能也非修复缺陷） | 否 |
| `perf` | 性能优化 | 是（patch） |
| `docs` | 文档变更 | 否 |
| `test` | 测试相关 | 否 |
| `chore` | 构建 / 工具 / 依赖变更 | 否 |
| `ci` | CI/CD 相关 | 否 |
| `build` | 构建系统或外部依赖变更 | 否 |
| `style` | 代码格式（不影响功能） | 否 |
| `revert` | 回滚某次提交 | 是（对应被回滚提交的级别） |

### 6.3 scope 取值

scope 标注受影响的组件或模块，与项目目录结构对齐。

表：E-17 scope 取值对照表

| scope | 对应目录 | 说明 |
| --- | --- | --- |
| `encaps-layer` | `platform/encaps-layer/` | 封装层 |
| `sql-gateway` | `platform/sql-gateway/` | 统一 SQL 网关 |
| `catalog` | `platform/catalog/` | 自研 Catalog |
| `rule-engine` | `platform/rule-engine/` | 自研规则引擎 |
| `dqctl` | `platform/dqctl/` | dqctl CLI |
| `llmops` | `platform/llmops/` | LLMOps |
| `ml-platform` | `platform/ml-platform/` | ML 平台 |
| `frontend` | `frontend/` | 前端控制台 |
| `ske` | `ske/` | 自研 SKE 集群 |
| `chart` | `design/deploy/charts/` | Helm Chart |
| `values` | `design/deploy/values/` | Helm values |
| `poc` | `scripts/poc/` | PoC 脚本 |
| `tests` | `tests/` | 集成 / E2E 测试 |
| `docs` | `docs/` / `design/` | 文档 |
| `ci` | `.github/workflows/` | CI/CD 流水线 |

### 6.4 subject 规范

- 使用简体中文或英文祈使句。
- 不超过 50 个字符。
- 结尾不加句号。
- 描述"做了什么"而非"做了什么变更"。

### 6.5 body 规范

- 每行不超过 72 个字符。
- 解释"为什么"（动机），而非"做了什么"（diff 已说明）。
- 可分点列出关键变更。

### 6.6 footer 规范

- 关联 Issue：`Closes #42` / `Refs #43`。
- Breaking changes：`BREAKING CHANGE: <说明>`。
- 多人协作：`Co-authored-by: name <email>`。

### 6.7 提交消息示例

```
feat(encaps-layer): 新增工作空间 CRUD 与 K8s Namespace 翻译

- WorkspaceController 提供 create/list/get/update/delete 端点
- WorkspaceService 翻译为 K8s Namespace + ResourceQuota + NetworkPolicy
- 补齐 12 个单元测试覆盖正常与异常路径

Closes #42
```

```
fix(ske): 修复 Cilium socketLB.mode 非法值导致集群拉起失败

将 socketLB.mode 从 "always" 改为 "police"（合法值）。
修复后 kind 集群可正常拉起，Cilium 不再报错。
```

```
perf(sql-gateway): Trino 代理连接池化，P95 从 1.2s 降至 0.4s

引入 WebClient 连接池（maxConnections=50, pendingAcquireTimeout=2s），
替代每次新建连接。基准测试显示 P95 从 1.2s 降至 0.4s。

Closes #58
```

```
feat(catalog)!: 重构 Table 元数据 Schema，移除 deprecated 字段

BREAKING CHANGE: TableSchema 移除 columns 字段，改用 fields 字段。
V1.0 客户端需升级至 v1.1+ 或使用兼容适配器。
迁移方案见 docs/migration/v2.0-catalog-schema.md。
```

### 6.8 提交粒度

- **一个提交一个语义**：一个提交只做一件事，不混合多个无关变更。
- **小步提交**：单提交行数建议 ≤400 行（不含生成代码 / 锁文件），超过需在 body 说明理由。
- **可编译可测试**：每个提交必须可编译且单元测试通过，禁止"中间态"提交。
- **禁止自动生成提交消息**：禁止 `git commit -m "fix"` / `git commit -m "update"` 等无意义消息。

### 6.9 提交前自检

- [ ] 提交消息符合 Conventional Commits 规范
- [ ] 本地构建通过
- [ ] 单元测试通过且覆盖率不下降
- [ ] 代码已格式化
- [ ] 新增公开 API 有文档
- [ ] 不引入新的直接依赖（如必须，在 PR 描述说明）

---

## 第7章 与已有规范的关系

### 7.1 与 CONTRIBUTING.md 的关系

本文件是 `CONTRIBUTING.md` 在 Phase 1a 场景下的**细化与强化**：

- `CONTRIBUTING.md` 定义通用贡献流程，本文件针对 29 人团队 / 24 任务 / 6 领域组细化 Reviewer 分配、Review SLA、升级 Review 规则。
- `CONTRIBUTING.md` 已定义的分支策略、提交规范、代码规范在本文件中沿用，本文件补充 Phase 1a 特有的质量门禁与验收标准关联。
- 两者冲突时，**本文件优先**（Phase 1a 执行期）。

### 7.2 与 CONVENTIONS.md 的关系

`CONVENTIONS.md` 是命名与约定的**单一事实来源**，本文件不重复其内容，仅在各语言规范中引用。所有命名必须遵循 `CONVENTIONS.md`。

### 7.3 与 Phase1_详细执行计划.md 的关系

本文件 §4 Review 流程、§5 质量门禁直接对应 `Phase1_详细执行计划.md` §7.1~§7.5，本文件是其可执行落地。验收标准检查清单关联 §7.4 的 24 项验收项。

---

## 第8章 实施检查清单

Phase 1a 启动前需确认以下事项全部就绪：

- [x] 分支策略已确认（Feature 分支 + 三层分支模型）
- [x] PR 模板已创建（`.github/pull_request_template.md`）
- [x] 各语言代码规范已文档化（§3）
- [x] Review 流程已定义（§4，含 Reviewer 分配 / SLA / 升级规则）
- [x] 四级质量门禁已确认（§5，对齐 §7.5）
- [x] 提交规范已定义（§6，Conventional Commits）
- [ ] GitHub Branch Protection 已配置（main / develop 强保护）
- [ ] GitHub Required Reviews 已配置（main 需 2 名，develop 需 1 名）
- [ ] GitHub Status Checks 已配置（CI 全绿方可合并）
- [ ] commitlint CI 检查已启用
- [ ] SonarQube / golangci-lint / ruff / ESLint CI 检查已启用
- [ ] 覆盖率门禁 CI 检查已启用（JaCoCo / pytest-cov / go test -cover / vitest --coverage）

---

> 本文档由 Phase1a 启动前准备工程师（任务 148）编写，依据 `Phase1_详细执行计划.md` §7 质量保证策略、`CONTRIBUTING.md`、`CONVENTIONS.md` 综合制定。文档经首席架构师评审通过后，作为 Phase 1a 代码协作的基准规范。