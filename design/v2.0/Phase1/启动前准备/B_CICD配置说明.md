# B_CICD配置说明

> 文档版本：v1.0  
> 创建日期：2026-08-06  
> 适用阶段：Phase 1a 启动前准备  
> 任务编号：145  
> 关联文档：`Phase1_详细执行计划.md` §7.4 验收标准、`E_开发规范与分支策略.md`

## 第1章 文档目的与范围

### 1.1 文档目的

本文档描述数擎大数据平台 V2.0 Phase 1a 阶段的 CI/CD 流水线配置、质量门禁规则、本地运行方式以及各任务对应的 CI/CD 配置映射。旨在为开发团队提供：

1. CI/CD 流水线全景理解，明确各 workflow 的触发时机与职责边界
2. 多语言（Java/Go/Python/前端）代码检查与覆盖率配置的统一说明
3. 本地复现 CI 检查的具体命令，确保提交前自检通过
4. Phase 1a 各任务与 CI/CD 配置项的对应关系，便于任务负责人按图索骥

### 1.2 配置范围

本次 CI/CD 配置覆盖以下内容：

表：CI/CD配置清单

| 类别 | 文件路径 | 说明 |
|------|----------|------|
| CI 工作流 | `.github/workflows/ci.yml` | PR 提交时触发的持续集成检查（已增强） |
| 构建工作流 | `.github/workflows/build.yml` | 主分支推送时触发的构建与镜像推送（新增） |
| 安全工作流 | `.github/workflows/security.yml` | SonarQube + ZAP + 依赖漏洞扫描（新增） |
| 发布工作流 | `.github/workflows/release.yml` | 打 tag 时发布 GitHub Release（已有，保留） |
| CodeQL 工作流 | `.github/workflows/codeql.yml` | 代码语义级 SAST 分析（已有，保留） |
| Dependabot | `.github/dependabot.yml` | 依赖自动更新（已有，保留） |
| PR 模板 | `.github/pull_request_template.md` | PR 标准化模板（已有，保留） |
| Go lint 配置 | `.golangci.yml` | golangci-lint 规则（新增） |
| Python lint 配置 | `.pylintrc` | pylint 规则（新增） |
| Python 工具配置 | `pyproject.toml` | black/isort/coverage/mypy 配置中心（新增） |
| Java Checkstyle | `design/v2.0/Phase1/启动前准备/config/checkstyle.xml` | Checkstyle 规则（新增） |
| Java SpotBugs | `design/v2.0/Phase1/启动前准备/config/spotbugs-exclude.xml` | SpotBugs 排除规则（新增） |
| Java PMD | `design/v2.0/Phase1/启动前准备/config/pmd-ruleset.xml` | PMD 规则集（新增） |
| JaCoCo 配置片段 | `design/v2.0/Phase1/启动前准备/config/jacoco-plugin-snippet.xml` | JaCoCo 共享配置（新增） |
| 覆盖率门禁脚本 | `scripts/coverage-gate.sh` | 多语言覆盖率门禁检查（新增） |

### 1.3 设计原则

1. **不破坏现有配置**：在已有 `ci.yml`、`release.yml`、`codeql.yml`、`dependabot.yml` 基础上增强，保留原有 job
2. **多语言并行**：Java/Go/Python/前端 4 种语言的检查并行执行，最大化 CI 速度
3. **门禁分层**：lint → test → coverage → integration → security 五层门禁，逐层收紧
4. **配置集中**：工具配置（black/isort/coverage）集中在根目录 `pyproject.toml`，避免散落各组件
5. **失败不阻塞**：lint 与安全扫描采用"报告为主、阻塞为辅"策略，避免 Phase 1a 初期 CI 过度严格

## 第2章 CI/CD 流水线架构

### 2.1 流水线总览

图：CI/CD流水线架构图

```
                        ┌─────────────────────────────────────────────────────────┐
                        │                   代码提交事件                           │
                        └─────────────────────────────────────────────────────────┘
                                            │
                ┌───────────────────────────┼───────────────────────────┐
                │                           │                           │
                ▼                           ▼                           ▼
        ┌──────────────┐            ┌──────────────┐            ┌──────────────┐
        │  PR → main   │            │ push → main  │            │  tag v*      │
        │  ci.yml      │            │ build.yml    │            │ release.yml  │
        │  codeql.yml  │            │ security.yml │            │              │
        │  security.yml│            │ codeql.yml   │            │              │
        └──────┬───────┘            └──────┬───────┘            └──────┬───────┘
               │                           │                           │
               ▼                           ▼                           ▼
        ┌──────────────┐            ┌──────────────┐            ┌──────────────┐
        │  14 个 job   │            │  CI Gate     │            │  CI Gate     │
        │  并行执行    │            │  等待 CI 通过│            │  等待 CI 通过│
        └──────┬───────┘            └──────┬───────┘            └──────┬───────┘
               │                           │                           │
               ▼                           ▼                           ▼
        ┌──────────────┐            ┌──────────────┐            ┌──────────────┐
        │  全部通过    │            │  构建镜像    │            │  发布 Release│
        │  → 允许合并  │            │  推送 GHCR   │            │  上传制品    │
        └──────────────┘            │  生成 SBOM   │            │  生成 SBOM   │
                                    └──────────────┘            └──────────────┘
```

### 2.2 Workflow 职责边界

表：Workflow职责对照表

| Workflow | 触发条件 | 职责 | 是否推送制品 | 失败阻塞合并 |
|----------|----------|------|-------------|-------------|
| `ci.yml` | PR→main、push→任意分支 | 代码检查+单元测试+覆盖率+集成测试 | 否（仅 artifact） | 是 |
| `build.yml` | push→main、push→release/*、手动 | 编译+Docker 镜像构建+推送 GHCR | 是（GHCR 镜像） | 否（主分支无 PR 门禁） |
| `security.yml` | push→main、PR→main、每周二定时、手动 | SonarQube+Trivy+govulncheck+pip-audit+OWASP+ZAP | 否（SARIF 上传） | 否（报告为主） |
| `release.yml` | tag v* | 打包制品+GitHub Release+SBOM | 是（Release 资产） | 是（依赖 CI Gate） |
| `codeql.yml` | push→main、PR→main、每周一定时 | 代码语义级 SAST（Java/Go/Python/JS） | 否（SARIF） | 否（报告为主） |

### 2.3 分支策略与 CI/CD 映射

图：分支与CI/CD触发关系图

```
分支策略：
  main          ← 受保护分支，仅通过 PR 合并
  feature/*     ← 功能开发分支
  release/*     ← 发布维护分支
  tag v*        ← 发布标签

触发关系：
  feature/* push        → ci.yml（仅检查，不构建镜像）
  PR feature/* → main   → ci.yml + codeql.yml + security.yml
  main push             → ci.yml + build.yml + security.yml + codeql.yml
  release/* push        → ci.yml + build.yml
  tag v*                → release.yml（依赖 CI Gate）
  每周一 02:00 UTC       → codeql.yml（全量 SAST）
  每周二 02:00 UTC       → security.yml（全量安全扫描）
```

## 第3章 ci.yml 详细说明

### 3.1 Job 列表与依赖关系

ci.yml 共 14 个 job，分为 4 层：

表：ci.yml Job清单

| 层级 | Job 名称 | 依赖 | 说明 |
|------|----------|------|------|
| L1 代码检查 | `go-lint` | 无 | golangci-lint 检查 5 个 Go 组件 |
| L1 代码检查 | `python-lint` | 无 | pylint+black+isort+flake8+mypy 检查 7 个 Python 组件 |
| L1 代码检查 | `frontend-lint` | 无 | ESLint+Prettier+TypeScript 检查前端 |
| L1 代码检查 | `java-lint` | 无 | Checkstyle+PMD 检查 10 个 Java 组件 |
| L1 基础检查 | `yaml-lint` | 无 | yamllint 校验 YAML 文件 |
| L1 基础检查 | `python-check` | 无 | Python 语法编译检查 |
| L1 基础检查 | `bash-check` | 无 | bash -n 语法检查 |
| L2 构建+测试 | `frontend-build` | 无 | npm ci + npm run build |
| L2 构建+测试 | `helm-lint` | 无 | helm lint 校验 Chart |
| L2 构建+测试 | `java-build-test` | 无 | mvn clean test + JaCoCo 覆盖率 |
| L2 构建+测试 | `go-build-test` | 无 | go test -coverprofile + go vet |
| L2 构建+测试 | `python-test` | 无 | pytest --cov + 覆盖率报告 |
| L3 集成测试 | `docker-build` | 无 | Dockerfile 构建验证（仅 PR） |
| L3 集成测试 | `integration-test` | java-build-test, go-build-test | docker-compose + pytest 集成测试 |
| L4 文档门禁 | `markdown-lint` | 前 14 个 job 全部 | markdownlint 校验 README/CONVENTIONS |

### 3.2 覆盖率报告收集

ci.yml 在测试 job 中自动收集并上传覆盖率报告：

表：覆盖率报告收集对照表

| 语言 | 工具 | 报告格式 | 上传 artifact 名 | 路径 |
|------|------|----------|-----------------|------|
| Java | JaCoCo | XML + HTML | `java-coverage-reports` | `target/site/jacoco/jacoco.xml` |
| Go | go test -coverprofile | cov + HTML | `go-coverage-reports` | `*.cov` + `*.html` |
| Python | pytest-cov | XML + HTML | `python-coverage-reports` | `*-coverage.xml` + `*-html/` |
| 前端 | vitest/jest | JSON | `frontend-dist`（含 coverage） | `frontend/coverage/` |

### 3.3 并发与取消策略

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

同一分支的新提交会自动取消正在运行的旧 CI，避免资源浪费。但 `release.yml` 和 `security.yml` 的 tag/定时触发不受此限制。

## 第4章 build.yml 详细说明

### 4.1 触发条件

```yaml
on:
  push:
    branches: [main, 'release/**']
  workflow_dispatch:
    inputs:
      push_image:
        description: '是否推送镜像到 GHCR（true/false）'
        default: 'true'
```

- **main 分支推送**：每次合并 PR 后自动触发，构建并推送 `latest` + `${{ sha }}` 标签的镜像
- **release/* 分支推送**：发布维护分支的修复版本构建
- **手动触发**：可控制是否推送镜像（便于调试构建过程）

### 4.2 Job 列表

表：build.yml Job清单

| Job 名称 | 依赖 | 说明 |
|----------|------|------|
| `ci-gate` | 无 | 等待 ci.yml 通过后才允许构建 |
| `build-frontend-image` | ci-gate | 构建前端 Nginx 镜像，推送 GHCR |
| `build-java-images` | ci-gate | 构建所有 Java 组件镜像，推送 GHCR |
| `build-go-images` | ci-gate | 构建所有 Go 组件镜像（多架构 amd64+arm64），推送 GHCR |
| `build-python-images` | ci-gate | 构建所有 Python 组件镜像，推送 GHCR |
| `build-charts` | ci-gate | 打包 Helm Chart 为 tgz，推送 GHCR OCI Registry |
| `build-summary` | 前 5 个构建 job | 输出构建摘要 |

### 4.3 镜像命名规范

```
镜像仓库：ghcr.io/${{ github.repository_owner }}/sq-${component_name}
镜像标签：
  - latest        ← 滚动更新（主分支最新）
  - ${{ github.sha }}  ← 精确版本（用于回滚）

示例：
  ghcr.io/shuqing/sq-encaps-layer:latest
  ghcr.io/shuqing/sq-encaps-layer:a1b2c3d4e5f6...
  ghcr.io/shuqing/sq-frontend:latest
  ghcr.io/shuqing/sq-catalog:latest
```

### 4.4 多架构构建

Go 组件支持 `linux/amd64` + `linux/arm64` 双架构构建，适配信创 ARM 服务器：

```yaml
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t ghcr.io/.../sq-${mod_name}:latest \
  --provenance=true --sbom=true
```

### 4.5 镜像签名与 SBOM

所有镜像构建时启用：
- `provenance: true`：SLSA 来源证明
- `sbom: true`：内嵌 SBOM 物料清单

## 第5章 security.yml 详细说明

### 5.1 Job 列表

表：security.yml Job清单

| Job 名称 | 工具 | 扫描对象 | 输出 | 阻塞 |
|----------|------|----------|------|------|
| `sonarqube` | SonarQube Scanner | Java/Go/Python 源码 | SonarQube Dashboard | 否（需配置 quality gate） |
| `trivy-scan` | Trivy | 代码库 fs + IaC config | SARIF → GitHub Security | 否（exit-code=0） |
| `go-vulncheck` | govulncheck | Go 依赖图 | 日志 artifact | 否（仅警告） |
| `python-audit` | pip-audit | Python 依赖 | JSON artifact | 否（仅警告） |
| `java-dependency-check` | OWASP Dependency-Check | Java 依赖 | HTML/XML artifact | 否（仅警告） |
| `zap-dast` | ZAP | 运行中的服务（HTTP） | SARIF + HTML | 否（仅 schedule/manual） |
| `security-summary` | - | 汇总 | 摘要日志 | - |

### 5.2 SonarQube 配置

需配置 GitHub Secrets：

表：SonarQube Secret配置表

| Secret 名 | 说明 | 获取方式 |
|-----------|------|----------|
| `SONAR_TOKEN` | SonarQube 分析令牌 | SonarQube → My Account → Security → Generate Token |
| `SONAR_HOST_URL` | SonarQube 服务地址 | 如 `https://sonar.shuqing.com` |

若未配置 Secret，`sonarqube` job 会自动跳过并输出警告。

### 5.3 ZAP DAST 扫描

ZAP DAST 仅在定时扫描或手动触发时执行（耗时较长）：

```yaml
if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'
```

扫描流程：
1. 启动 `tests/integration/docker-compose.yml` 拉起被测服务
2. ZAP 基线扫描 `http://localhost:8080`
3. ZAP API 扫描 OpenAPI 文档 `http://localhost:8080/v3/api-docs`
4. 销毁被测服务

### 5.4 与 codeql.yml 的关系

表：安全扫描职责对照表

| 维度 | codeql.yml | security.yml |
|------|-----------|--------------|
| 分析方式 | 代码语义级 SAST | 工程化安全扫描 |
| 覆盖语言 | Java/Go/Python/JS | Java/Go/Python + IaC + Docker |
| 依赖漏洞 | 不检查 | Trivy + govulncheck + pip-audit + OWASP |
| 动态扫描 | 不支持 | ZAP DAST |
| 代码质量 | 不检查 | SonarQube |
| 触发频率 | 每周一 | 每周二 |

两者互补，共同构成完整的安全扫描体系。

## 第6章 代码检查配置

### 6.1 Go 代码检查（golangci-lint）

配置文件：`.golangci.yml`

启用 30+ linter，覆盖：

表：Go Linter分类表

| 类别 | Linter | 说明 |
|------|--------|------|
| 代码质量 | errcheck, gosimple, govet, staticcheck | 基础质量检查 |
| 安全 | gosec, bodyclose, noctx | 安全漏洞检查 |
| 性能 | prealloc, sqlclosecheck, rowserrcheck | 性能反模式 |
| 风格 | gofmt, gofumpt, goimports, revive | 代码风格 |
| 复杂度 | gocyclo(15), gocognit(20), funlen(80), lll(120) | 复杂度限制 |
| 设计 | gochecknoglobals, gochecknoinits, exhaustive | 设计约束 |

本地运行：

命令示例：本地运行Go lint检查

```bash
# 安装 golangci-lint
go install github.com/golangci/golangci-lint/cmd/golangci-lint@v1.61.0

# 对单个组件检查
cd platform/catalog
golangci-lint run --timeout=10m ./...

# 对所有 Go 组件检查
for mod in platform/*/; do
  if [ -f "$mod/go.mod" ]; then
    echo "检查 $mod..."
    (cd "$mod" && golangci-lint run --timeout=10m ./...)
  fi
done
```

### 6.2 Python 代码检查

配置文件：`.pylintrc`（pylint）、`pyproject.toml`（black/isort/mypy/coverage）

表：Python Lint工具表

| 工具 | 配置位置 | 检查内容 | 阻塞 CI |
|------|----------|----------|---------|
| black | `pyproject.toml [tool.black]` | 代码格式 | 是 |
| isort | `pyproject.toml [tool.isort]` | import 顺序 | 是 |
| flake8 | 命令行参数 | 风格检查 | 是 |
| pylint | `.pylintrc` | 静态分析 | 否（fail-under=7.0） |
| mypy | `pyproject.toml [tool.mypy]` | 类型检查 | 否（仅警告） |

本地运行：

命令示例：本地运行Python lint检查

```bash
# 安装工具
pip install pylint black isort flake8 mypy pytest-cov

# 对单个组件检查
cd platform/business-portal
black --check --diff .
isort --check --diff .
flake8 --max-line-length=120 --extend-ignore=E203,W503 .
pylint --rcfile=../../.pylintrc --fail-under=7.0 .
mypy --ignore-missing-imports .

# 自动修复格式
black .
isort .
```

### 6.3 Java 代码检查

配置文件：
- `design/v2.0/Phase1/启动前准备/config/checkstyle.xml`：Checkstyle 规则
- `design/v2.0/Phase1/启动前准备/config/spotbugs-exclude.xml`：SpotBugs 排除
- `design/v2.0/Phase1/启动前准备/config/pmd-ruleset.xml`：PMD 规则集

表：Java Lint工具表

| 工具 | 配置文件 | 检查内容 | 阻塞 CI |
|------|----------|----------|---------|
| Checkstyle | checkstyle.xml | 代码风格、命名、Javadoc | 否（仅警告） |
| PMD | pmd-ruleset.xml | 设计、最佳实践、错误预防 | 否（仅警告） |
| SpotBugs | spotbugs-exclude.xml | 缺陷模式、安全 | 否（需 Maven 插件配置） |

本地运行：

命令示例：本地运行Java lint检查

```bash
# 对单个组件检查
cd platform/encaps-layer
mvn checkstyle:check -B
mvn pmd:check -B
mvn spotbugs:check -B  # 需在 pom.xml 配置 spotbugs-maven-plugin

# 完整验证
mvn verify -B
```

### 6.4 前端代码检查

表：前端Lint工具表

| 工具 | 配置位置 | 检查内容 | 阻塞 CI |
|------|----------|----------|---------|
| ESLint | `frontend/.eslintrc.*` | 代码质量 | 否（仅警告） |
| Prettier | `frontend/.prettierrc` | 格式 | 否（仅警告） |
| TypeScript | `frontend/tsconfig.json` | 类型检查 | 否（仅警告） |

本地运行：

命令示例：本地运行前端lint检查

```bash
cd frontend
npm ci
npm run lint           # ESLint
npx prettier --check "src/**/*.{ts,tsx,vue,js,jsx,css,scss,json,md}"
npm run type-check     # TypeScript（或 npx vue-tsc --noEmit）
```

## 第7章 测试覆盖率配置

### 7.1 覆盖率门禁阈值

表：覆盖率门禁阈值表

| 语言 | 工具 | 行覆盖率 | 分支覆盖率 | 方法覆盖率 | 配置位置 |
|------|------|----------|-----------|-----------|----------|
| Java | JaCoCo | ≥ 70% | ≥ 60% | ≥ 70% | `pom.xml` jacoco-maven-plugin |
| Go | go test -cover | ≥ 70% | - | - | `scripts/coverage-gate.sh` |
| Python | pytest-cov | ≥ 70% | ≥ 70% | - | `pyproject.toml [tool.coverage]` |
| 前端 | vitest/jest | ≥ 80% | - | - | `frontend/vitest.config.ts` |

### 7.2 Java JaCoCo 配置

各 Java 组件 `pom.xml` 需引入 `jacoco-maven-plugin`。共享配置片段位于 `design/v2.0/Phase1/启动前准备/config/jacoco-plugin-snippet.xml`，复制到组件 `pom.xml` 的 `<build><plugins>` 中即可。

配置示例：JaCoCo插件配置（XML）

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals><goal>report</goal></goals>
        </execution>
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.70</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

排除的类：`**/model/**`、`**/dto/**`、`**/entity/**`、`**/config/**`、`**/*Application*`、`**/generated/**`

### 7.3 Go 覆盖率配置

Go 通过 `go test -coverprofile` 生成覆盖率，无需额外配置：

命令示例：Go覆盖率生成

```bash
# 生成覆盖率
cd platform/catalog
go test ./... -coverprofile=coverage.cov -covermode=atomic

# 查看覆盖率
go tool cover -func=coverage.cov
go tool cover -html=coverage.cov -o coverage.html

# 覆盖率门禁
bash scripts/coverage-gate.sh
```

### 7.4 Python 覆盖率配置

配置位于根目录 `pyproject.toml [tool.coverage]`，所有 Python 组件共享：

命令示例：Python覆盖率生成

```bash
# 生成覆盖率
cd platform/business-portal
pytest --cov=. --cov-report=xml:coverage.xml --cov-report=html --cov-branch

# 查看覆盖率
coverage report
coverage html

# 覆盖率门禁
bash scripts/coverage-gate.sh
```

排除的文件：`*/tests/*`、`*/test_*.py`、`*/conftest.py`、`*/__init__.py`、`*/migrations/*`

### 7.5 覆盖率门禁脚本

`scripts/coverage-gate.sh` 统一检查所有语言的覆盖率：

命令示例：运行覆盖率门禁

```bash
bash scripts/coverage-gate.sh
```

输出示例：

```
============================================
  覆盖率门禁检查
============================================
Java 最低覆盖率:   70%
Go 最低覆盖率:     70%
Python 最低覆盖率: 70%
前端最低覆盖率:    80%

--- Java JaCoCo 覆盖率 ---
[PASS] encaps-layer: 85.32%
[PASS] sql-gateway: 78.45%
[FAIL] rule-engine: 65.20% (低于 70%)

--- Go 覆盖率 ---
[PASS] catalog: 82.15%
[PASS] dqctl: 75.30%

--- Python 覆盖率 ---
[PASS] business-portal: 72.80%
[FAIL] ml-platform: 55.40% (低于 70%)

============================================
  覆盖率门禁检查失败：2 个组件未达标
```

## 第8章 质量门禁配置

### 8.1 门禁层级

图：质量门禁层级图

```
┌─────────────────────────────────────────────────────────┐
│ L1 代码检查门禁（lint）                                  │
│   ├─ Go: golangci-lint                                   │
│   ├─ Python: black + isort + flake8（阻塞）             │
│   ├─ Python: pylint + mypy（警告）                      │
│   ├─ Java: Checkstyle + PMD（警告）                     │
│   └─ 前端: ESLint + Prettier + TypeScript（警告）       │
├─────────────────────────────────────────────────────────┤
│ L2 单元测试门禁（test）                                  │
│   ├─ Java: mvn test（阻塞）                             │
│   ├─ Go: go test（阻塞）                                │
│   ├─ Python: pytest（阻塞）                             │
│   └─ 前端: npm run build（阻塞）                        │
├─────────────────────────────────────────────────────────┤
│ L3 覆盖率门禁（coverage）                                │
│   ├─ Java: JaCoCo ≥ 70%（verify 阶段）                 │
│   ├─ Go: go cover ≥ 70%（coverage-gate.sh）            │
│   ├─ Python: pytest-cov ≥ 70%（coverage-gate.sh）      │
│   └─ 前端: ≥ 80%（coverage-gate.sh）                    │
├─────────────────────────────────────────────────────────┤
│ L4 集成测试门禁（integration）                          │
│   └─ docker-compose + pytest（阻塞）                    │
├─────────────────────────────────────────────────────────┤
│ L5 安全门禁（security）                                  │
│   ├─ CodeQL SAST（警告）                                │
│   ├─ Trivy 依赖漏洞（警告）                             │
│   ├─ SonarQube quality gate（可配置阻塞）              │
│   └─ ZAP DAST（仅 schedule）                            │
├─────────────────────────────────────────────────────────┤
│ L6 文档门禁（docs）                                      │
│   └─ markdownlint（阻塞，依赖前 14 个 job）            │
└─────────────────────────────────────────────────────────┘
```

### 8.2 PR 合并门禁

GitHub Branch Protection Rule 配置（`main` 分支）：

表：PR合并门禁配置表

| 门禁项 | 配置 | 说明 |
|--------|------|------|
| 必须通过 CI | `ci.yml` 全部 job | 14 个 job 全部绿色 |
| 必须通过 CodeQL | `codeql.yml` 全部 job | 4 种语言 SAST |
| 必须通过安全扫描 | `security.yml` 全部 job | SonarQube + Trivy + 依赖审计 |
| 必须评审 | 至少 1 名 reviewer | 关键模块需 2 名 |
| 必须最新 | 分支必须与 main 同步 | 合并前自动更新 |
| 禁止 force push | 禁止 | 保护历史 |
| 禁止直接 push | 禁止 | 必须通过 PR |

### 8.3 门禁策略说明

Phase 1a 阶段采用"渐进式门禁"策略：

1. **初期（第 1-2 周）**：lint 与安全扫描仅警告不阻塞，避免存量代码问题阻塞开发
2. **中期（第 3-4 周）**：lint 阻塞（black/isort/flake8/golangci-lint），安全扫描仍警告
3. **后期（第 5 周起）**：全部门禁阻塞，达到生产级质量要求

## 第9章 本地运行 CI 检查

### 9.1 一键本地 CI

命令示例：本地运行完整CI检查

```bash
#!/bin/bash
# scripts/local-ci.sh - 本地复现 CI 检查

echo "=== L1 代码检查 ==="
# Go lint
for mod in platform/*/; do
  [ -f "$mod/go.mod" ] && (cd "$mod" && golangci-lint run --timeout=10m ./...)
done

# Python lint
for mod in platform/*/; do
  [ -f "$mod/pyproject.toml" ] && (cd "$mod" && \
    black --check . && isort --check . && flake8 --max-line-length=120 .)
done

# Java lint
for pom in platform/*/pom.xml; do
  mvn -f "$pom" checkstyle:check pmd:check -B -q
done

# 前端 lint
(cd frontend && npm run lint && npx prettier --check "src/**/*.{ts,vue}")

echo "=== L2 单元测试 ==="
for pom in platform/*/pom.xml; do mvn -f "$pom" test -B; done
for mod in platform/*/; do [ -f "$mod/go.mod" ] && (cd "$mod" && go test ./... -cover); done
for mod in platform/*/; do [ -f "$mod/pyproject.toml" ] && (cd "$mod" && pytest --cov); done

echo "=== L3 覆盖率门禁 ==="
bash scripts/coverage-gate.sh

echo "=== L4 集成测试 ==="
(cd tests/integration && docker compose up -d --wait && pytest)
```

### 9.2 各语言本地检查命令

表：本地检查命令对照表

| 检查项 | 命令 | 工作目录 |
|--------|------|----------|
| Go lint | `golangci-lint run --timeout=10m ./...` | `platform/{component}` |
| Go test | `go test ./... -v -coverprofile=coverage.cov` | `platform/{component}` |
| Python format | `black --check --diff .` | `platform/{component}` |
| Python import | `isort --check --diff .` | `platform/{component}` |
| Python lint | `pylint --rcfile=../../.pylintrc --fail-under=7.0 .` | `platform/{component}` |
| Python test | `pytest --cov=. --cov-report=term-missing` | `platform/{component}` |
| Java lint | `mvn checkstyle:check pmd:check -B` | `platform/{component}` |
| Java test | `mvn clean test -B` | `platform/{component}` |
| Java coverage | `mvn verify -B`（含 JaCoCo check） | `platform/{component}` |
| 前端 lint | `npm run lint` | `frontend` |
| 前端 build | `npm run build` | `frontend` |
| YAML lint | `yamllint -d relaxed design/deploy/values/ ske/manifests/` | 项目根 |
| Helm lint | `helm lint design/deploy/charts/{chart}` | 项目根 |
| Bash lint | `bash -n ske/ske.sh ske/tuning/*.sh` | 项目根 |
| Markdown lint | `markdownlint README.md CONVENTIONS.md` | 项目根 |

### 9.3 工具安装

命令示例：安装本地CI工具

```bash
# Go 工具
go install github.com/golangci/golangci-lint/cmd/golangci-lint@v1.61.0
go install golang.org/x/vuln/cmd/govulncheck@latest

# Python 工具
pip install pylint black isort flake8 mypy pytest pytest-cov pytest-asyncio pip-audit

# Java 工具（Maven 自带 checkstyle/pmd/jacoco 插件）
# 需安装 JDK 17 + Maven 3.9

# 前端工具
cd frontend && npm ci

# 通用工具
npm install -g markdownlint-cli
sudo apt-get install -y yamllint
```

## 第10章 Phase1a 各任务对应的 CI/CD 配置

### 10.1 任务与 CI/CD 配置映射

表：Phase1a任务与CI/CD配置映射表

| 任务 ID | 任务名称 | 涉及语言 | 触发的 CI Job | 覆盖率工具 | 安全扫描 |
|---------|----------|----------|--------------|-----------|----------|
| T008-1 | 封装层 API | Java | java-lint, java-build-test | JaCoCo | CodeQL java, OWASP |
| T008-2 | SQL 网关 | Java | java-lint, java-build-test | JaCoCo | CodeQL java, OWASP |
| T008-3 | 自研 Catalog | Go | go-lint, go-build-test | go cover | CodeQL go, govulncheck |
| T008-4 | 规则引擎 | Java | java-lint, java-build-test | JaCoCo | CodeQL java, OWASP |
| T008-5 | dqctl CLI | Go | go-lint, go-build-test | go cover | CodeQL go, govulncheck |
| T009 | LLMOps | Python | python-lint, python-test | pytest-cov | CodeQL python, pip-audit |
| T010 | ML 平台 | Python | python-lint, python-test | pytest-cov | CodeQL python, pip-audit |
| T011 | 前端控制台 | TypeScript/Vue | frontend-lint, frontend-build | vitest | CodeQL javascript |
| T012 | 集成测试 | Python | integration-test | - | - |
| T013 | SKE 集群 | Bash/YAML | bash-check, yaml-lint | - | Trivy IaC |
| T014 | Helm Chart | YAML | helm-lint, yaml-lint | - | Trivy config |

### 10.2 各组件 CI/CD 接入清单

#### 10.2.1 Java 组件（10 个）

表：Java组件CI/CD接入清单

| 组件 | pom.xml JaCoCo | Dockerfile | Helm Chart | CI Job |
|------|----------------|------------|------------|--------|
| encaps-layer | ✓ 已配置 | ✓ | ✓ | java-lint + java-build-test |
| sql-gateway | 需补充 | ✓ | ✓ | java-lint + java-build-test |
| rule-engine | 需补充 | ✓ | ✓ | java-lint + java-build-test |
| tag-engine | 需补充 | - | ✓ | java-lint + java-build-test |
| infra-orchestrator | 需补充 | - | ✓ | java-lint + java-build-test |
| infra-provider-private | 需补充 | - | ✓ | java-lint + java-build-test |
| infra-provider-cloud | 需补充 | - | ✓ | java-lint + java-build-test |
| infra-provider-xinchang | 需补充 | - | ✓ | java-lint + java-build-test |
| governance/lineage-analyzer | 需补充 | - | ✓ | java-lint + java-build-test |
| governance/metadata-collector | 需补充 | - | ✓ | java-lint + java-build-test |

**JaCoCo 补充方式**：将 `design/v2.0/Phase1/启动前准备/config/jacoco-plugin-snippet.xml` 内容复制到组件 `pom.xml` 的 `<build><plugins>` 中。

#### 10.2.2 Go 组件（5 个）

表：Go组件CI/CD接入清单

| 组件 | go.mod | Dockerfile | 多架构 | CI Job |
|------|--------|------------|--------|--------|
| catalog | ✓ | ✓ | amd64+arm64 | go-lint + go-build-test |
| dqctl | ✓ | - | - | go-lint + go-build-test |
| vector-engine | ✓ | - | - | go-lint + go-build-test |
| llm-gateway | ✓ | - | - | go-lint + go-build-test |
| infra-provider-baremetal | ✓ | - | - | go-lint + go-build-test |

#### 10.2.3 Python 组件（7 个）

表：Python组件CI/CD接入清单

| 组件 | pyproject.toml | Dockerfile | pytest 配置 | CI Job |
|------|----------------|------------|-------------|--------|
| business-portal | ✓ | - | ✓ | python-lint + python-test |
| industry-templates | ✓ | - | 需补充 | python-lint + python-test |
| asset-exchange | ✓ | - | 需补充 | python-lint + python-test |
| open-api-catalog | ✓ | - | 需补充 | python-lint + python-test |
| ml-platform | ✓ | - | 需补充 | python-lint + python-test |
| knowledge-engine | ✓ | - | 需补充 | python-lint + python-test |
| llmops | ✓ | - | 需补充 | python-lint + python-test |

**pytest 配置补充**：在组件 `pyproject.toml` 中添加 `[tool.pytest.ini_options]` 段，参考 `business-portal/pyproject.toml`。

## 第11章 GitHub Secrets 配置

### 11.1 必需 Secrets

表：GitHub Secrets配置表

| Secret 名 | 用途 | 必需 | 配置位置 |
|-----------|------|------|----------|
| `GITHUB_TOKEN` | CI 默认令牌 | 自动提供 | - |
| `SONAR_TOKEN` | SonarQube 分析令牌 | 否（跳过 SonarQube） | Settings → Secrets → Actions |
| `SONAR_HOST_URL` | SonarQube 服务地址 | 否（跳过 SonarQube） | Settings → Secrets → Actions |
| `NVD_API_KEY` | NVD 漏洞数据库 API key | 否（加速 OWASP 扫描） | Settings → Secrets → Actions |

### 11.2 GitHub Branch Protection 配置

`main` 分支保护规则（Settings → Branches → Add rule）：

1. **Require status checks to pass before merging**
   - 勾选 `Markdown Lint`（ci.yml 最后一个 job，依赖全部通过）
   - 勾选 `Analyze (java)`、`Analyze (go)`、`Analyze (python)`、`Analyze (javascript)`（codeql.yml）
   - 勾选 `Security Scan Summary`（security.yml）
2. **Require branches to be up to date before merging**
3. **Require at least 1 reviewer**
4. **Restrict who can push to matching branches**（禁止直接 push）

## 第12章 CI/CD 性能优化

### 12.1 并行化策略

- **14 个 job 并行**：ci.yml 的 14 个 job 互相独立（除 integration-test 和 markdown-lint），最大化并行
- **矩阵构建**：CodeQL 使用 matrix 并行 4 种语言
- **多架构构建**：build.yml Go 镜像使用 buildx 多架构并行

### 12.2 缓存策略

表：CI缓存策略表

| 缓存对象 | 配置 | 命中条件 |
|----------|------|----------|
| Maven 依赖 | `actions/setup-java cache: maven` | `pom.xml` 未变 |
| Go 模块 | `actions/setup-go cache: true` | `go.mod`/`go.sum` 未变 |
| pip 依赖 | `actions/setup-python cache: pip` | `requirements.txt`/`pyproject.toml` 未变 |
| npm 依赖 | `actions/setup-node cache: npm` | `package-lock.json` 未变 |
| Docker 层 | `buildx cache-from: type=gha` | GitHub Actions cache |

### 12.3 预计 CI 耗时

表：CI耗时预估表

| Job | 预计耗时 | 说明 |
|-----|----------|------|
| go-lint | 2-3 min | 5 个组件并行 |
| python-lint | 3-5 min | 7 个组件串行 |
| frontend-lint | 2-3 min | 单组件 |
| java-lint | 5-8 min | 10 个组件串行 |
| yaml-lint | 1 min | - |
| frontend-build | 3-5 min | Vite 构建 |
| helm-lint | 1-2 min | 13 个 Chart |
| java-build-test | 10-15 min | 10 个组件 mvn test |
| go-build-test | 3-5 min | 5 个组件 go test |
| python-test | 5-8 min | 7 个组件 pytest |
| docker-build | 5-10 min | 仅 PR |
| integration-test | 5-10 min | docker-compose + pytest |
| markdown-lint | 1 min | - |
| **总计（并行）** | **15-20 min** | 关键路径为 java-build-test |

## 第13章 维护与演进

### 13.1 配置变更流程

1. 修改 `.github/workflows/*.yml` 或配置文件
2. 在 PR 描述中说明变更原因与影响
3. PR 触发 CI 验证变更不破坏现有检查
4. 合并后生效

### 13.2 演进路线

表：CI/CD演进路线表

| 阶段 | 时间 | 演进内容 |
|------|------|----------|
| Phase 1a 当前 | 2026-08 | 14 job CI + build + security + 多语言 lint + 覆盖率 |
| Phase 1b | 2026-09 | 增加 E2E 测试 job、性能基准测试 job |
| Phase 2 | 2026-10 | 引入 ArgoCD GitOps、Helm OCI 推送自动化 |
| Phase 3 | 2026-11 | 引入金丝雀发布、流量镜像、自动回滚 |

### 13.3 监控 CI/CD 健康

- **GitHub Actions Usage**：Settings → Billing → Actions，监控分钟消耗
- **CI 失败率**：通过 `gh run list --status failure` 定期检查
- **平均耗时**：通过 `gh run list --json startedAt,updatedAt` 计算
- **覆盖率趋势**：通过 SonarQube Dashboard 或 coverage-gate.sh 历史记录

## 第14章 常见问题

### 14.1 CI 失败排查

表：CI失败排查对照表

| 失败现象 | 可能原因 | 解决方案 |
|----------|----------|----------|
| `go-lint` 失败 | golangci-lint 发现问题 | 本地运行 `golangci-lint run` 修复 |
| `python-lint` black 失败 | 代码格式不符 | 运行 `black .` 自动修复 |
| `python-lint` isort 失败 | import 顺序不对 | 运行 `isort .` 自动修复 |
| `java-build-test` 失败 | 单元测试失败或编译错误 | 本地 `mvn test` 排查 |
| 覆盖率门禁失败 | 覆盖率低于阈值 | 补充测试用例或调整阈值 |
| `docker-build` 失败 | Dockerfile 语法或依赖问题 | 本地 `docker build` 验证 |
| `integration-test` 失败 | 服务启动失败或测试断言失败 | 本地 `docker compose up` 排查 |
| SonarQube 跳过 | 未配置 SONAR_TOKEN | 配置 Secret 或忽略 |

### 14.2 跳过 CI 检查

**不推荐**，但在紧急情况下可：

- 在提交消息中包含 `[skip ci]` 可跳过 CI
- 仅用于文档变更或紧急修复，且需在 PR 描述中说明理由

### 14.3 新增组件接入 CI

新增 Java 组件：
1. 在 `platform/{component}/` 创建 `pom.xml`，引入 `jacoco-maven-plugin`
2. 创建 `Dockerfile`（如需镜像构建）
3. ci.yml 自动发现并执行 `java-lint` + `java-build-test`
4. build.yml 自动发现并构建镜像
5. 在 `.github/dependabot.yml` 添加 Maven 依赖更新配置

新增 Go 组件：
1. 在 `platform/{component}/` 创建 `go.mod`
2. ci.yml 自动发现并执行 `go-lint` + `go-build-test`
3. build.yml 自动发现并构建镜像

新增 Python 组件：
1. 在 `platform/{component}/` 创建 `pyproject.toml`，添加 `[tool.pytest.ini_options]`
2. ci.yml 自动发现并执行 `python-lint` + `python-test`
3. build.yml 自动发现并构建镜像

## 第15章 参考文档

- [GitHub Actions 文档](https://docs.github.com/en/actions)
- [golangci-lint 配置](https://golangci-lint.run/usage/configuration/)
- [pylint 配置](https://pylint.readthedocs.io/en/latest/user_guide/configuration/all-options.html)
- [black 配置](https://black.readthedocs.io/en/stable/usage_and_configuration/the_basics.html)
- [JaCoCo 配置](https://www.jacoco.org/jacoco/trunk/doc/maven.html)
- [Checkstyle 配置](https://checkstyle.org/config.html)
- [SonarQube 配置](https://docs.sonarsource.com/sonarqube/latest/)
- [Trivy 配置](https://aquasecurity.github.io/trivy/)
- [ZAP 配置](https://www.zaproxy.org/docs/)
- 项目内：`CONTRIBUTING.md`、`CONVENTIONS.md`、`E_开发规范与分支策略.md`

---

> 本文档随 CI/CD 配置变更同步更新。变更 PR 需在描述中说明对本文档的影响。