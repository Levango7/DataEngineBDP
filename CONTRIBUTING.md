# 贡献指南

感谢您关注数据引擎大数据平台（DataEngineBDP）并愿意为之贡献。本指南描述了开发环境搭建、代码规范、提交规范与 PR 流程。

## 开发环境搭建

### 系统要求

| 工具 | 最低版本 | 推荐版本 | 用途 |
| --- | --- | --- | --- |
| JDK | 17 | 17 (LTS) | Java 组件构建与运行 |
| Maven | 3.9 | 3.9.6 | Java 组件依赖管理与构建 |
| Go | 1.23 | 1.23.4 | Go 组件构建与运行 |
| Python | 3.11 | 3.11.8 | Python 组件构建与运行 |
| Node.js | 20 | 20.11 LTS | 前端构建 |
| npm | 10 | 10.2 | 前端依赖管理 |
| Docker | 24.0 | 24.0.7 | 容器镜像构建 |
| kubectl | 1.28 | 1.29 | 集群操作 |
| Helm | 3.14 | 3.14.0 | Chart 部署 |
| Git | 2.40 | 2.44 | 版本控制 |

### 克隆仓库

```bash
git clone https://github.com/Levango7/DataEngineBDP.git
cd DataEngineBDP
```

### 各语言环境初始化

```bash
# Java 组件（任选一个验证）
mvn -f platform/encaps-layer/pom.xml clean compile

# Go 组件
go -C platform/catalog mod download

# Python 组件
python -m venv .venv
.venv\Scripts\activate
pip install -e platform/llmops

# 前端
cd frontend
npm install
cd ..
```

### IDE 推荐配置

- **IntelliJ IDEA**：用于 Java 组件开发，安装 Lombok / MapStructSupport 插件。
- **GoLand** 或 **VS Code + Go 扩展**：用于 Go 组件开发。
- **PyCharm** 或 **VS Code + Python 扩展**：用于 Python 组件开发。
- **VS Code + Volar**：用于前端 Vue3 开发，启用 TypeScript strict 模式。

## 代码规范

### 通用规范

- 缩进使用空格，不使用 Tab。
- 文件末尾保留一个空行。
- 行尾去除多余空格。
- UTF-8 编码，LF 换行符。
- 不提交 IDE 配置文件（.idea / .vscode），使用 .gitignore 排除。

### Java 规范

- 遵循 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) 并适配 Spring Boot 约定。
- 类名使用 PascalCase（如 `TenantServiceImpl`、`OrchestrationService`）。
- 方法与变量使用 camelCase（如 `createTenant`、`tenantId`）。
- 常量使用 UPPER_SNAKE_CASE（如 `MAX_RETRY_COUNT`）。
- 包名全小写（如 `com.shuqing.encaps.tenant`）。
- 每个 public 方法必须有 Javadoc 注释。
- 异常处理：业务异常抛自定义 `BusinessException`，系统异常抛 `SystemException`，不裸抛 `RuntimeException`。

### Go 规范

- 运行 `gofmt -s -w .` 格式化代码。
- 运行 `go vet ./...` 静态检查。
- 包名全小写单单词（如 `catalog`、`handler`）。
- 导出标识符必须有注释，注释以标识符名称开头。
- 错误处理：不忽略 error 返回值，使用 `errors.Is` / `errors.As` 判断错误类型。

### Python 规范

- 遵循 [PEP 8](https://peps.python.org/pep-0008/)，使用 `black` 格式化、`isort` 排序导入。
- 使用 `mypy --strict` 类型检查。
- 使用 `ruff` 替代 flake8 / pylint 做静态检查。
- 函数与类必须有 docstring，使用 Google 风格。
- 异步优先使用 `async def`，同步入口使用 `def`。

### TypeScript / Vue 规范

- 启用 `strict: true`，禁止 `any` 类型（必要时使用 `unknown` 后做类型收窄）。
- 组件名使用 PascalCase，文件名与组件名一致。
- 使用 Composition API + `<script setup lang="ts">` 语法。
- 使用 Pinia 状态管理，避免 Vuex。
- 运行 `npm run lint` 与 `npm run type-check` 通过后方可提交。

### 命名约定

详见 [CONVENTIONS.md](CONVENTIONS.md)。关键约定：

- 套餐命名：`base` / `standard` / `flagship`（禁止 `basic` / `enterprise` / `pro`）。
- 工作空间命名：`ws-<name>`（禁止 `<tenant>-default`）。
- 模块计数：49 模块（禁止沿用"41 模块"旧口径）。
- SKE 版本：v0.1（禁止 SKE v1.0）。

## 提交规范

本项目遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/v1.0.0/) 规范。

### 提交消息格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

### type 取值

| type | 含义 |
| --- | --- |
| feat | 新功能 |
| fix | 缺陷修复 |
| docs | 文档变更 |
| style | 代码格式（不影响功能） |
| refactor | 重构（既非新增功能也非修复缺陷） |
| perf | 性能优化 |
| test | 测试相关 |
| chore | 构建工具 / 依赖 / 辅助工具变更 |
| ci | CI/CD 相关 |
| build | 构建系统或外部依赖变更 |
| revert | 回滚某次提交 |

### scope 取值

scope 标注受影响的组件或模块，例如：`encaps-layer`、`sql-gateway`、`catalog`、`frontend`、`ske`、`docs`、`chart`。

### subject 规范

- 使用简体中文或英文祈使句。
- 不超过 50 个字符。
- 结尾不加句号。

### 示例

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
```

## 分支策略

| 分支 | 用途 | 命名 |
| --- | --- | --- |
| main | 生产分支，始终可发布 | `main` |
| develop | 集成分支，最新开发成果 | `develop` |
| feature/* | 功能开发分支 | `feature/<scope>-<short-desc>` |
| fix/* | 缺陷修复分支 | `fix/<scope>-<short-desc>` |
| release/* | 发布准备分支 | `release/v<x.y.z>` |
| hotfix/* | 紧急修复分支 | `hotfix/<scope>-<short-desc>` |

### 分支流转

1. 从 `develop` 拉出 `feature/*` 或 `fix/*` 分支开发。
2. 开发完成后向 `develop` 发起 Pull Request。
3. `develop` 定期向 `main` 发起 Release PR，合并后打 tag 发布。
4. 生产紧急修复从 `main` 拉出 `hotfix/*`，修复后同时合并回 `main` 与 `develop`。

## Pull Request 流程

### 提交前自检

- [ ] 本地构建通过（Java: `mvn clean package`，Go: `go build ./...`，Python: `pytest`，前端: `npm run build`）。
- [ ] 单元测试通过且覆盖率不下降。
- [ ] 代码已格式化（Java: `mvn spotless:apply`，Go: `gofmt -s -w .`，Python: `black . && isort .`，前端: `npm run lint -- --fix`）。
- [ ] 提交消息符合 Conventional Commits 规范。
- [ ] 新增公开 API 有文档或 Javadoc / docstring。
- [ ] 不引入新的直接依赖（如必须，在 PR 描述中说明理由）。

### PR 标题与描述

- PR 标题与首条提交消息格式一致（Conventional Commits）。
- PR 描述包含：变更动机、变更内容、测试方式、是否影响向后兼容。
- 关联相关 Issue（如 `Closes #42`）。

### 评审要求

- 至少一名 Code Reviewer 批准。
- CI 全部通过。
- 涉及架构变更或公共 API 变更需两名 Reviewer 批准。
- 评审关注：功能正确性、边界与异常处理、性能影响、安全风险、可读性、测试充分性。

### 合并方式

- 优先使用 Squash Merge，保持 main / develop 历史线性。
- 大特性合并可使用 Rebase Merge。
- 禁止直接 Push 到 main / develop。

## 测试要求

### 单元测试

- 每个新增或修改的 public 方法必须有对应单元测试。
- 测试覆盖正常路径、边界条件与异常路径。
- Java 使用 JUnit 5 + Mockito，Go 使用标准库 `testing` + `testify`，Python 使用 `pytest` + `pytest-asyncio`，前端使用 `vitest`。
- 测试命名：Java `should<期望>When<条件>`，Go `Test<函数>_<条件>`，Python `test_<期望>_when_<条件>`。

### 集成测试

- 涉及多组件交互的变更需补充集成测试。
- 集成测试位于 `tests/integration/`，使用 docker-compose 编排依赖。
- 运行方式：`pytest tests/integration/ -v`。

### 端到端测试

- 涉及端到端数据流的变更需更新 `scripts/poc/` 验证脚本。
- 运行方式：`bash scripts/poc/run-poc.sh`。

## 问题与建议

- Bug 报告请使用 GitHub Issue，标注 `bug` 标签，附复现步骤与环境信息。
- 功能建议请使用 GitHub Issue，标注 `enhancement` 标签，描述场景与期望行为。
- 安全漏洞请勿公开 Issue，邮件联系维护者。

## 行为准则

参与本项目即表示您同意保持尊重与专业的交流态度。不接受任何人身攻击、骚扰或歧视性言论。