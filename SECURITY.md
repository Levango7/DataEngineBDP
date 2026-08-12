# 安全政策

数据引擎大数据平台（DataEngineBDP）重视产品安全。本文件说明如何报告安全漏洞，以及当前的安全支持与响应流程。

## 支持的版本

| 版本 | 支持状态 |
| --- | --- |
| 2.x（最新 minor） | ✅ 接受安全修复 |
| 1.x | ⚠️ 仅关键修复（视情况评估） |
| < 1.0 | ❌ 不再支持 |

建议所有生产部署升级到最新的稳定版本。

## 报告安全漏洞

**请勿通过公开的 GitHub Issue 报告安全漏洞。**

请通过以下方式私密报告：

- **GitHub 私密漏洞报告（推荐）**：使用本仓库 **Security → Advisories → New draft security advisory** 提交，维护者会在私密环境中处理。
- **邮件**：通过仓库提交历史中维护者的公开联系邮箱发送，标题以 `[SECURITY]` 开头。

### 报告中请包含

- 受影响组件与版本
- 漏洞类型与影响范围（如：认证绕过 / 注入 / 敏感信息泄露）
- 复现步骤或概念证明（PoC）
- 潜在的缓解措施（如已有）

### 响应时间目标

| 阶段 | 目标时间 |
| --- | --- |
| 确认收到报告 | 72 小时内 |
| 初步评估与严重度分级 | 7 天内 |
| 修复或缓解方案 | 依严重度，关键漏洞优先 |

我们会与报告者保持沟通，直至修复公开发布。遵循[协调披露原则](https://en.wikipedia.org/wiki/Coordinated_vulnerability_disclosure)：请在修复发布前不要在公开渠道披露细节。

## 现有安全机制

本平台的 CI/CD 流水线内置多层安全扫描：

- **SAST**：CodeQL（Java / Go / Python / JavaScript）在每次 push / PR 时执行。
- **密钥扫描**：Gitleaks 阻止硬编码密钥提交。
- **依赖与镜像漏洞**：Trivy（文件系统 + IaC）、govulncheck、pip-audit、OWASP Dependency-Check。
- **依赖更新**：Dependabot 每周自动创建依赖更新 PR。
- **SBOM**：每次构建生成 CycloneDX / SPDX 物料清单。

## 生产部署安全清单

在生产环境部署前，请务必：

1. 设置强随机 `JWT_SECRET`（≥ 32 字节），禁止使用任何默认/示例值。
2. 为裸金属 BMS/IPMI 设置强 `IPMI_USERNAME` / `IPMI_PASSWORD`（无默认值，未配置时服务将拒绝启动）。
3. 将运维后台 `ADMIN_TOKEN` 设置为强随机值。
4. 修改 `values-dev.yaml` 中所有 dev 占位凭据（doris / keycloak / dinky / superset 等），生产使用 `values-prod.yaml` 并接入外部密钥管理。
5. 启用 NetworkPolicy 与 ResourceQuota 实现租户隔离。

详细步骤见 [部署指南](docs/deployment-guide.md)。
