# SonarQube / SonarCloud 集成配置指南

> **文档目的**：说明 DataEngineBDP 项目 SonarCloud 代码质量分析的配置方法、质量阈规则、结果查看方式，以及与现有 CI 的关系。
>
> **适用范围**：所有向 `main` 分支提交代码或发起 PR 的开发者。
>
> **维护者**：DevOps 团队
>
> **最后更新**：2026-08-20

---

## 目录

1. [架构概览](#1-架构概览)
2. [前置条件](#2-前置条件)
3. [配置 SONAR_TOKEN Secret](#3-配置-sonar_token-secret)
4. [SonarCloud 项目初始化](#4-sonarcloud-项目初始化)
5. [质量阈规则](#5-质量阈规则)
6. [查看分析结果](#6-查看分析结果)
7. [与现有 CI 的关系](#7-与现有-ci-的关系)
8. [本地运行 SonarCloud 分析](#8-本地运行-sonarcloud-分析)
9. [故障排查](#9-故障排查)
10. [启用质量门禁阻断](#10-启用质量门禁阻断)

---

## 1. 架构概览

### 1.1 工作流程

```
开发者 push/PR → GitHub Actions → sonarqube.yml workflow
                                      │
                                      ├── Java 模块分析（遍历 platform/ 下所有 pom.xml）
                                      │     └── mvn verify sonar:sonar → SonarCloud
                                      │
                                      └── 前端分析（frontend/ 目录）
                                            └── npx sonar-scanner → SonarCloud
```

### 1.2 关键设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| SonarCloud vs 自建 SonarQube | SonarCloud 公共云 | 免运维、免维护数据库、免费额度足够 |
| 配置方式 | 命令行参数 | **不修改任何 pom.xml**，避免污染构建配置 |
| 项目粒度 | 每个模块独立 projectKey | 单模块报告清晰，可独立设置质量阈 |
| 失败处理 | 仅告警不阻断 | SONAR_TOKEN 未配置前不阻断主 CI |
| 触发条件 | push/PR to main + 手动 | 与 codeql.yml 保持一致 |

### 1.3 涉及的文件

| 文件 | 用途 |
|------|------|
| `.github/workflows/sonarqube.yml` | SonarCloud CI workflow（主文件） |
| `sonar-project.properties` | 全局属性配置（前端和本地扫描使用） |
| `docs/SONARQUBE-SETUP.md` | 本文档 |

---

## 2. 前置条件

### 2.1 SonarCloud 账户

1. 访问 [https://sonarcloud.io](https://sonarcloud.io)
2. 使用 GitHub 账户登录
3. 加入组织 `levango7`（需组织管理员邀请）

### 2.2 项目仓库要求

- 仓库已启用 GitHub Actions
- 仓库已配置 `SONAR_TOKEN` secret（见 [第 3 节](#3-配置-sonar_token-secret)）
- `platform/` 目录下存在至少一个 `pom.xml`（Java 模块）
- `frontend/` 目录下存在 `package.json` 和 `package-lock.json`（前端）

### 2.3 工具版本

| 工具 | 版本 | 说明 |
|------|------|------|
| JDK | 17 (temurin) | 与 build.yml/codeql.yml 保持一致 |
| Maven | 3.9.9 | 与 build.yml 保持一致 |
| sonar-scanner | 最新 | 通过 npx 自动获取 |
| GitHub Actions runner | ubuntu-latest | 标准托管 runner |

---

## 3. 配置 SONAR_TOKEN Secret

### 3.1 获取 SONAR_TOKEN

1. 登录 [SonarCloud](https://sonarcloud.io)
2. 点击右上角头像 → **My Account**
3. 切换到 **Security** 标签页
4. 在 **Generate Tokens** 区域：
   - **Name**：`DataEngineBDP-CI`
   - **Type**：`Global Analysis Token`（推荐）或 `Project Analysis Token`
   - **Expiration**：建议 90 天（到期前轮换）
5. 点击 **Generate**
6. **立即复制 token**（关闭页面后无法再次查看）

> ⚠️ **安全警告**：Token 等同于密码，切勿提交到代码仓库、聊天记录或日志中。

### 3.2 配置 GitHub Secret

1. 打开仓库页面：`https://github.com/Levango7/DataEngineBDP`
2. 点击 **Settings** → **Secrets and variables** → **Actions**
3. 点击 **New repository secret**：
   - **Name**：`SONAR_TOKEN`
   - **Secret**：粘贴上一步复制的 token
4. 点击 **Add secret**

### 3.3 验证配置

配置完成后，手动触发 workflow 验证：

1. 进入仓库 **Actions** 标签页
2. 左侧选择 **SonarQube Analysis**
3. 点击 **Run workflow** → 选择 `main` 分支 → **Run workflow**
4. 等待执行完成，查看日志确认无 `::warning::...SONAR_TOKEN...` 告警

---

## 4. SonarCloud 项目初始化

每个 Java 模块和前端会对应一个独立的 SonarCloud 项目。首次运行 workflow 时，SonarCloud 会自动创建这些项目。

### 4.1 项目命名规则

| 模块类型 | projectKey | 示例 |
|----------|------------|------|
| Java 模块 | `Levango7_DataEngineBDP_<模块名>` | `Levango7_DataEngineBDP_rule-engine` |
| 前端 | `Levango7_DataEngineBDP_frontend` | — |

### 4.2 首次运行预期

首次运行时，SonarCloud 会：
- 自动创建项目（若不存在）
- 执行全量分析（首次较慢，后续增量）
- 生成基线指标

> **注意**：首次分析可能因项目未在 SonarCloud 后台预创建而出现告警，属正常现象，第二次运行后会自动恢复。

---

## 5. 质量阈规则

### 5.1 默认质量阈（SonarCloud Built-in）

SonarCloud 默认提供 **Sonar way** 质量阈，适用于新代码（New Code）：

| 指标 | 阈值 | 说明 |
|------|------|------|
| Bugs | 0 | 新代码不允许引入任何 Bug |
| Vulnerabilities | 0 | 新代码不允许引入任何安全漏洞 |
| Code Smells | 0 | 新代码不允许引入任何代码异味 |
| Coverage | ≥ 80% | 新代码覆盖率不低于 80% |
| Duplications | < 3% | 新代码重复率低于 3% |

### 5.2 推荐自定义质量阈

针对 DataEngineBDP 项目特点，建议在 SonarCloud 后台为每个项目自定义质量阈：

```
名称：DataEngineBDP Quality Gate
```

| 指标 | 条件 | 理由 |
|------|------|------|
| 新代码 Bugs | = 0 | 严格阻断新 Bug |
| 新代码 Vulnerabilities | = 0 | 严格阻断新漏洞（与 CodeQL 互补） |
| 新代码 Critical Code Smells | = 0 | 阻断严重代码异味 |
| 新代码 Coverage | < 70% 时告警 | 当前覆盖率基线较低，先告警后逐步收紧 |
| 新代码 Duplications | > 5% 时告警 | DTO/Entity 重复属正常，阈值放宽 |
| 整体 Rating | Security Rating = A | 安全评级必须 A |

### 5.3 配置自定义质量阈

1. 登录 SonarCloud → 进入组织 `levango7`
2. 顶部菜单 **Quality Gates** → **Create**
3. 按上表添加条件
4. 设为 **Default**（应用于所有未单独指定的项目）

### 5.4 质量阈状态传递

- 质量阈 **通过**：PR 上显示绿色 ✅ Check
- 质量阈 **失败**：PR 上显示红色 ❌ Check（当前仅告警，不阻断合并，见 [第 10 节](#10-启用质量门禁阻断)）

---

## 6. 查看分析结果

### 6.1 SonarCloud 仪表盘

访问入口：`https://sonarcloud.io/dashboard?id=<projectKey>`

**Java 模块示例**：
- rule-engine: `https://sonarcloud.io/dashboard?id=Levango7_DataEngineBDP_rule-engine`
- vector-engine: `https://sonarcloud.io/dashboard?id=Levango7_DataEngineBDP_vector-engine`

**前端**：
- `https://sonarcloud.io/dashboard?id=Levango7_DataEngineBDP_frontend`

### 6.2 仪表盘关键指标

| 指标 | 含义 | 关注点 |
|------|------|--------|
| Bugs | 缺陷数 | 应为 0 |
| Vulnerabilities | 安全漏洞数 | 应为 0（与 CodeQL 互补） |
| Code Smells | 代码异味 | 关注 Critical 和 Blocker |
| Coverage | 覆盖率 | 关注新代码覆盖率 |
| Duplications | 重复率 | 关注新代码重复率 |
| Quality Gate | 质量阈状态 | 应为 Passed |

### 6.3 PR 集成

PR 创建后，SonarCloud 会自动在 PR 上：
- 发布 **评论**：包含质量阈状态、新增问题数、覆盖率变化
- 创建 **Check**：显示在 PR Checks 区域，状态反映质量阈结果

### 6.4 GitHub Actions Job Summary

workflow 执行完成后，在 Actions 运行详情页的 **Summary** 区域会显示所有模块的 SonarCloud 仪表盘链接，便于快速跳转。

---

## 7. 与现有 CI 的关系

### 7.1 现有 workflow 一览

| Workflow | 用途 | 与 SonarQube 关系 |
|----------|------|-------------------|
| `ci.yml` | 多语言 lint + 覆盖率门禁 | 互补：CI 关注覆盖率数值，SonarQube 关注代码质量维度 |
| `build.yml` | 构建产物 | SonarQube 依赖构建产物（class 文件）进行分析 |
| `codeql.yml` | 安全漏洞扫描 | **互补**：CodeQL 侧重安全漏洞，SonarQube 侧重代码质量 |
| `security.yml` | 安全扫描 | 互补 |
| `security-scan.yml` | 安全扫描 | 互补 |
| `image-sign-sbom.yml` | 镜像签名 | 独立 |
| `multi-arch-build.yml` | 多架构构建 | 独立 |
| `release.yml` | 发布 | 独立 |
| **`sonarqube.yml`** | **代码质量分析** | **本工作流** |

### 7.2 与 CodeQL 的分工

| 维度 | CodeQL | SonarCloud |
|------|--------|------------|
| 侧重 | 安全漏洞（SQL 注入、XSS 等） | 代码质量（复杂度、重复、坏味道） |
| 查询语言 | CodeQL DSL | 内置规则 + Sonar way |
| 结果位置 | GitHub Security tab | SonarCloud 仪表盘 |
| 覆盖语言 | java/go/python/javascript | java/javascript（当前配置） |
| 阻断 CI | 是（SARIF 上传失败阻断） | 否（当前仅告警） |

**建议**：两者并行使用，CodeQL 守安全底线，SonarCloud 守质量底线。

### 7.3 与 ci.yml 覆盖率门禁的关系

- `ci.yml`：硬性覆盖率数值门禁（Java 行 ≥ 80% / 分支 ≥ 70%），失败阻断 CI
- `sonarqube.yml`：覆盖率作为质量阈的一个维度，当前仅告警

**不冲突**：两者独立运行，互不影响。`ci.yml` 是当前生效的硬门禁，`sonarqube.yml` 是补充的质量视角。

---

## 8. 本地运行 SonarCloud 分析

### 8.1 Java 模块本地分析

```bash
# 进入模块目录
cd platform/rule-engine

# 执行分析（需设置 SONAR_TOKEN 环境变量）
export SONAR_TOKEN="your-token-here"

mvn verify sonar:sonar \
  -Dsonar.projectKey=Levango7_DataEngineBDP_rule-engine \
  -Dsonar.projectName="rule-engine" \
  -Dsonar.organization=levango7 \
  -Dsonar.host.url=https://sonarcloud.io \
  -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \
  -B
```

### 8.2 前端本地分析

```bash
cd frontend
npm ci
npm run test:coverage  # 生成 coverage/lcov.info

export SONAR_TOKEN="your-token-here"
npx sonar-scanner \
  -Dsonar.projectKey=Levango7_DataEngineBDP_frontend \
  -Dsonar.projectName="frontend" \
  -Dsonar.organization=levango7 \
  -Dsonar.sources=src \
  -Dsonar.host.url=https://sonarcloud.io \
  -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info
```

> **注意**：本地分析会消耗 SonarCloud 分析配额，建议仅在调试配置时使用。

---

## 9. 故障排查

### 9.1 常见问题

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| `::warning::...SONAR_TOKEN...` | SONAR_TOKEN 未配置 | 按 [第 3 节](#3-配置-sonar_token-secret) 配置 |
| `401 Unauthorized` | Token 过期或无效 | 重新生成 Token 并更新 Secret |
| `Project not found` | SonarCloud 项目未初始化 | 首次运行会自动创建，或手动在 SonarCloud 创建 |
| `No coverage report found` | JaCoCo 报告未生成 | 确认模块 pom.xml 包含 jacoco-maven-plugin |
| `OutOfMemoryError` | 大模块分析内存不足 | workflow 已设 timeout 90 分钟，必要时拆分模块 |
| `sonar-scanner command not found` | npx 未正确获取 | 检查 frontend/node_modules 是否完整 |

### 9.2 查看详细日志

workflow 中 `mvn` 使用 `-q` 静默模式。如需详细日志调试：

1. 手动触发 workflow（workflow_dispatch）
2. 或临时移除 `-q` 参数重新运行

### 9.3 验证 Token 有效性

```bash
curl -u "your-token-here:" https://sonarcloud.io/api/user_tokens/search
```

返回 200 表示 Token 有效，401 表示无效。

---

## 10. 启用质量门禁阻断

### 10.1 当前状态

**当前**：SonarCloud 分析失败仅告警（`::warning::`），不阻断 CI 主流程。
**目的**：在 SONAR_TOKEN 配置稳定、质量阈规则验证通过后，再启用阻断，避免误伤。

### 10.2 启用阻断的步骤

1. **确认 SONAR_TOKEN 已配置**：所有模块分析无告警
2. **确认质量阈规则合理**：在 SonarCloud 后台验证质量阈误报率
3. **修改 workflow**：在 `.github/workflows/sonarqube.yml` 中：
   - 将 Java 分析步骤的 `|| echo "::warning::..."` 改为 `|| exit 1`
   - 将前端分析步骤的 `|| echo "::warning::..."` 改为 `|| exit 1`
4. **添加质量阈检查步骤**（可选）：

   ```yaml
   - name: 检查 SonarCloud 质量阈
     run: |
       for mod in $(find platform -mindepth 1 -maxdepth 1 -type d); do
         mod_name=$(basename "$mod")
         if [ -f "$mod/pom.xml" ]; then
           status=$(curl -s -u "$SONAR_TOKEN:" \
             "https://sonarcloud.io/api/qualitygates/project_status?projectKey=Levango7_DataEngineBDP_$mod_name" \
             | jq -r '.projectStatus.status')
           if [ "$status" != "OK" ]; then
             echo "::error::$mod_name 质量阈失败（status=$status）"
             exit 1
           fi
         fi
       done
     env:
       SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
   ```

5. **灰度验证**：先在非 main 分支测试，确认无误报后再合并到 main

### 10.3 渐进式启用建议

| 阶段 | 范围 | 阻断策略 |
|------|------|----------|
| 阶段 1（当前） | 全部模块 | 仅告警 |
| 阶段 2 | 仅前端 | 阻断前端，Java 仍告警 |
| 阶段 3 | 核心 Java 模块（rule-engine、vector-engine 等） | 阻断核心模块 |
| 阶段 4 | 全部模块 | 全阻断 |

---

## 附录 A：相关链接

- [SonarCloud 官方文档](https://docs.sonarsource.com/sonarcloud/)
- [SonarQube Maven 插件文档](https://docs.sonarsource.com/sonarqube/latest/analyzing-source-code/scanners/sonarscanner-for-maven/)
- [sonar-scanner CLI 文档](https://docs.sonarsource.com/sonarqube/latest/analyzing-source-code/scanners/sonarscanner/)
- [GitHub Actions 缓存最佳实践](https://docs.github.com/en/actions/using-workflows/caching-dependencies-to-speed-up-workflows)

## 附录 B：项目模块清单

当前 `platform/` 下共 31 个 Java 模块，每个模块对应一个独立的 SonarCloud 项目：

| # | 模块名 | projectKey |
|---|--------|------------|
| 1 | ai-assistant | Levango7_DataEngineBDP_ai-assistant |
| 2 | asset-exchange | Levango7_DataEngineBDP_asset-exchange |
| 3 | business-portal | Levango7_DataEngineBDP_business-portal |
| 4 | catalog | Levango7_DataEngineBDP_catalog |
| 5 | chunker | Levango7_DataEngineBDP_chunker |
| 6 | dqctl | Levango7_DataEngineBDP_dqctl |
| 7 | encaps-layer | Levango7_DataEngineBDP_encaps-layer |
| 8 | finops | Levango7_DataEngineBDP_finops |
| 9 | flink-cdc | Levango7_DataEngineBDP_flink-cdc |
| 10 | governance | Levango7_DataEngineBDP_governance |
| 11 | industry-templates | Levango7_DataEngineBDP_industry-templates |
| 12 | infra-orchestrator | Levango7_DataEngineBDP_infra-orchestrator |
| 13 | infra-provider-baremetal | Levango7_DataEngineBDP_infra-provider-baremetal |
| 14 | infra-provider-cloud | Levango7_DataEngineBDP_infra-provider-cloud |
| 15 | infra-provider-private | Levango7_DataEngineBDP_infra-provider-private |
| 16 | infra-provider-xinchang | Levango7_DataEngineBDP_infra-provider-xinchang |
| 17 | karmada | Levango7_DataEngineBDP_karmada |
| 18 | knative | Levango7_DataEngineBDP_knative |
| 19 | knowledge-engine | Levango7_DataEngineBDP_knowledge-engine |
| 20 | llm-gateway | Levango7_DataEngineBDP_llm-gateway |
| 21 | llmops | Levango7_DataEngineBDP_llmops |
| 22 | ml-platform | Levango7_DataEngineBDP_ml-platform |
| 23 | model-finetuning | Levango7_DataEngineBDP_model-finetuning |
| 24 | nl2sql | Levango7_DataEngineBDP_nl2sql |
| 25 | observability | Levango7_DataEngineBDP_observability |
| 26 | open-api-catalog | Levango7_DataEngineBDP_open-api-catalog |
| 27 | registry | Levango7_DataEngineBDP_registry |
| 28 | rule-engine | Levango7_DataEngineBDP_rule-engine |
| 29 | sql-gateway | Levango7_DataEngineBDP_sql-gateway |
| 30 | storage-io | Levango7_DataEngineBDP_storage-io |
| 31 | stream-batch-scheduler | Levango7_DataEngineBDP_stream-batch-scheduler |
| 32 | tag-engine | Levango7_DataEngineBDP_tag-engine |
| 33 | vector-engine | Levango7_DataEngineBDP_vector-engine |

> **注**：模块清单随项目演进可能变化，以 `platform/` 目录实际结构为准。