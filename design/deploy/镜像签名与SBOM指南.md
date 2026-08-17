# 镜像签名与 SBOM 指南

## 1. 概述

### 1.1 为什么需要镜像签名与 SBOM

软件供应链攻击已成为企业级平台的主要安全威胁之一。攻击者通过篡改镜像、注入恶意依赖或利用未修复漏洞，可在镜像构建到部署的任意环节实施攻击。DataEngineBDP 作为多平台多租户大数据平台，承载敏感数据与计算任务，必须建立端到端的供应链安全防线。

镜像签名与 SBOM（Software Bill of Materials，软件物料清单）是供应链安全的两大核心支柱：

| 能力 | 工具 | 作用 |
|------|------|------|
| 镜像签名 | cosign | 保证镜像自构建后未被篡改，部署前可验证签名身份与完整性 |
| 软件物料清单 | Syft | 列出镜像/源码包含的所有依赖及其版本，支撑漏洞追踪与合规审计 |
| 漏洞扫描 | Trivy | 检测镜像与依赖中的已知漏洞（CVE），按严重级别输出报告 |

图：供应链安全流水线示意图

```
源码提交 → 构建镜像 → 推送 GHCR → cosign 签名 → Syft 生成 SBOM → Trivy 漏洞扫描 → 制品归档
                                              ↓                ↓                  ↓
                                         签名附加到        SBOM artifact       SARIF 上传
                                         镜像 manifest     (SPDX/CycloneDX)    GitHub Security
```

### 1.2 适用范围

本指南适用于 DataEngineBDP 平台所有通过 GitHub Actions 构建并推送到 GHCR（ghcr.io）的容器镜像，包括但不限于：

- `sq-frontend`：前端 Nginx 静态站点镜像
- `sq-encaps-layer`：封装层服务镜像
- `sq-sql-gateway`：SQL 网关镜像
- `sq-catalog`：数据目录服务镜像
- `sq-rule-engine`：规则引擎镜像

### 1.3 与既有流水线的关系

| 流水线 | 文件 | 职责 | 与本指南关系 |
|--------|------|------|-------------|
| CI | `.github/workflows/ci.yml` | 代码检查 + CycloneDX SBOM 生成 | 互补：CI 做源码级检查，本流水线做镜像级安全 |
| Build | `.github/workflows/build.yml` | 全量镜像构建并推送 GHCR | 协作：build.yml 构建镜像，本流水线可对构建产物签名 |
| Release | `.github/workflows/release.yml` | 打 tag 时发布 GitHub Release + SBOM | 互补：release.yml 做发布物 SBOM，本流水线做镜像 SBOM |
| Security Scan | `.github/workflows/security-scan.yml` | Trivy 依赖/镜像/IaC 扫描 | 互补：security-scan.yml 做依赖扫描，本流水线做镜像漏洞扫描 |
| Image Sign & SBOM | `.github/workflows/image-sign-sbom.yml` | 镜像签名 + 镜像 SBOM + 镜像漏洞扫描 | 本指南所述流水线 |

## 2. cosign 配置

### 2.1 签名模式选择

cosign 支持两种签名模式：

| 模式 | 说明 | 优势 | 劣势 | 适用场景 |
|------|------|------|------|---------|
| keyless（OIDC） | 利用 GitHub Actions OIDC token 签名，无需管理私钥 | 无密钥管理负担；签名身份绑定 workflow；审计性强 | 依赖 sigstore 公共基础设施；需联网 | 推荐：CI/CD 自动签名 |
| key-based | 使用 cosign 密钥对签名 | 完全离线可控；不依赖外部服务 | 需管理私钥；密钥泄露风险 | 离线环境或合规要求自管密钥 |

DataEngineBDP 默认采用 **keyless（OIDC）模式**，本节先介绍该模式；2.4 节给出 key-based 模式的备选配置。

### 2.2 Keyless 模式前置条件

keyless 签名依赖以下条件：

1. GitHub 仓库启用 OIDC token（默认启用，无需额外配置）
2. workflow 拥有 `id-token: write` 权限
3. 镜像已推送到支持签名的 registry（GHCR 原生支持）
4. runner 可访问 `sigstore` 公共 Rekor 透明日志服务

### 2.3 GitHub Secrets 配置（keyless 模式）

keyless 模式无需配置 cosign 私钥 secret，但需要以下基础 secret：

表：keyless 模式 Secrets 说明表

| Secret 名称 | 用途 | 是否必需 | 说明 |
|-------------|------|---------|------|
| `GITHUB_TOKEN` | 推送镜像与签名到 GHCR | 必需 | GitHub 自动提供，无需手动配置 |
| `REGISTRY_PASSWORD` | 备选 registry 登录凭证 | 可选 | 使用非 GHCR 的自建 Harbor 时配置 |

### 2.4 Key-based 模式配置（备选）

若合规要求使用自管密钥，按以下步骤配置：

命令示例：生成 cosign 密钥对

```bash
# 生成密钥对（会提示设置密码）
cosign generate-key-pair
# 产出：cosign.pub（公钥）、cosign.key（私钥）
```

将私钥与密码配置为 GitHub Secrets：

表：key-based 模式 Secrets 说明表

| Secret 名称 | 值 | 说明 |
|-------------|-----|------|
| `COSIGN_PRIVATE_KEY` | `cosign.key` 文件内容 | cosign 私钥（PEM 格式） |
| `COSIGN_PASSWORD` | 生成密钥时设置的密码 | 私钥解密密码 |
| `COSIGN_PUBLIC_KEY` | `cosign.pub` 文件内容 | 公钥（用于验证端配置） |

key-based 签名步骤替换为：

代码示例：key-based 签名步骤（YAML）

```yaml
- name: Key-based 签名
  env:
    COSIGN_PRIVATE_KEY: ${{ secrets.COSIGN_PRIVATE_KEY }}
    COSIGN_PASSWORD: ${{ secrets.COSIGN_PASSWORD }}
  run: |
    echo "$COSIGN_PRIVATE_KEY" > /tmp/cosign.key
    cosign sign --key /tmp/cosign.key "$IMAGE_REF"
    rm /tmp/cosign.key
```

### 2.5 签名验证

部署前或审计时验证镜像签名：

命令示例：验证 keyless 签名

```bash
# 验证 keyless 签名（需指定身份与 OIDC issuer）
cosign verify ghcr.io/<org>/sq-frontend:<sha> \
  --certificate-identity-regexp "https://github.com/<org>/DataEngineBDP/.github/workflows/.*" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com"
```

命令示例：验证 key-based 签名

```bash
# 验证 key-based 签名（需提供公钥）
cosign verify --key cosign.pub ghcr.io/<org>/sq-frontend:<sha>
```

验证成功输出示例：

```
Verification for ghcr.io/levango7/sq-frontend@sha256:...
The following checks were performed on each of these signatures:
  - The cosign claims were validated
  - The code-signing certificate was valid
  - The signatures were verified against the specified public key
```

## 3. Syft 配置

### 3.1 SBOM 格式选择

Syft 支持多种 SBOM 格式，DataEngineBDP 同时生成两种主流格式以兼容不同消费方：

| 格式 | 输出参数 | 标准规范 | 适用消费方 |
|------|---------|---------|-----------|
| SPDX JSON | `-o spdx-json` | SPDX 2.3 | GitHub Dependency Review、合规审计工具 |
| CycloneDX JSON | `-o cyclonedx-json` | CycloneDX 1.5 | Dependency-Track、OWASP Dependency-Check |

### 3.2 SBOM 生成范围

本流水线针对两个维度生成 SBOM：

1. **源码 SBOM**：扫描整个代码仓库（`syft dir:.`），覆盖 Maven/npm/pip/Go module 等所有依赖
2. **镜像 SBOM**：扫描已推送的容器镜像（`syft <image-ref>`），覆盖镜像内操作系统包 + 应用依赖

源码 SBOM 用于开发期依赖审计；镜像 SBOM 用于部署期漏洞匹配，两者互补。

### 3.3 输出位置

SBOM 文件统一输出到 `reports/sbom/` 目录，命名规则：

| 文件名 | 说明 |
|--------|------|
| `source-sbom.spdx.json` | 源码 SBOM（SPDX 格式） |
| `source-sbom.cyclonedx.json` | 源码 SBOM（CycloneDX 格式） |
| `<component>-image.spdx.json` | 镜像 SBOM（SPDX 格式，如 `encaps-layer-image.spdx.json`） |
| `<component>-image.cyclonedx.json` | 镜像 SBOM（CycloneDX 格式） |

所有 SBOM 文件最终归档到 artifact `supply-chain-security-artifacts` 的 `sbom/` 子目录。

### 3.4 本地生成 SBOM

命令示例：本地生成 SBOM（需安装 Syft）

```bash
# 安装 Syft
curl -sSfL https://raw.githubusercontent.com/anchore/syft/main/install.sh | sh -s -- -b /usr/local/bin

# 源码 SBOM
syft dir:. -o spdx-json > source-sbom.spdx.json
syft dir:. -o cyclonedx-json > source-sbom.cyclonedx.json

# 镜像 SBOM
syft ghcr.io/<org>/sq-frontend:<sha> -o spdx-json > frontend-image.spdx.json
```

## 4. Trivy 配置

### 4.1 漏洞扫描策略

本流水线采用以下扫描策略：

| 策略项 | 配置值 | 说明 |
|--------|--------|------|
| 扫描器 | `--scanners vuln` | 仅扫描漏洞（不扫描 misconfig/license） |
| 严重级别 | `--severity HIGH,CRITICAL` | 聚焦高危漏洞，减少噪声 |
| 忽略未修复 | `--ignore-unfixed` | 跳过上游尚未发布修复版本的漏洞 |
| 退出码 | `--exit-code 0` | 不阻断流水线（仅报告）；生产门禁见 7.3 节 |

### 4.2 报告格式

Trivy 同时输出两种格式：

| 格式 | 输出参数 | 用途 |
|------|---------|------|
| SARIF | `--format sarif` | 上传 GitHub Security tab，在 PR 中可视化展示 |
| JSON | `--format json` | 详细报告含修复建议，供自动化处理与审计 |

### 4.3 严重级别定义

表：Trivy 严重级别说明表

| 级别 | CVSS 评分 | 处理建议 |
|------|----------|---------|
| CRITICAL | 9.0 - 10.0 | 必须修复，建议阻断发布 |
| HIGH | 7.0 - 8.9 | 应尽快修复，需人工评审 |
| MEDIUM | 4.0 - 6.9 | 计划修复，不阻断 |
| LOW | 0.1 - 3.9 | 可接受风险，记录跟踪 |
| UNKNOWN | 未评分 | 人工评估 |

### 4.4 本地执行扫描

命令示例：本地 Trivy 镜像扫描

```bash
# 安装 Trivy
curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin

# 扫描镜像（SARIF 格式）
trivy image --format sarif \
  --output vuln.sarif \
  --scanners vuln --severity HIGH,CRITICAL \
  --ignore-unfixed \
  ghcr.io/<org>/sq-frontend:<sha>

# 扫描镜像（JSON 格式，含修复建议）
trivy image --format json \
  --output vuln.json \
  --scanners vuln --severity HIGH,CRITICAL \
  --ignore-unfixed \
  ghcr.io/<org>/sq-frontend:<sha>
```

## 5. CI/CD 集成

### 5.1 Workflow 文件

镜像签名与 SBOM 流水线定义在 `.github/workflows/image-sign-sbom.yml`，包含 5 个作业：

图：作业依赖关系图

```
build-and-push (作业1)
       │
       ├──→ cosign-sign (作业2) ──────────┐
       ├──→ generate-sbom (作业3) ────────┤
       └──→ trivy-image-scan (作业4) ─────┤
                                          ↓
                  publish-supply-chain-artifacts (作业5)
```

### 5.2 触发条件

表：Workflow 触发条件说明表

| 触发事件 | 条件 | 说明 |
|---------|------|------|
| `push` | `branches: main` | 推送到 main 分支时触发 |
| `push` | `tags: v*` | 打 tag（如 v1.0.0）时触发 |
| `pull_request` | `branches: main` | 向 main 发起 PR 时触发（跳过签名步骤） |
| `workflow_dispatch` | 手动触发 | 支持指定组件列表、是否推送/签名 |

### 5.3 作业说明

#### 5.3.1 作业 1：build-and-push

- 职责：构建关键组件 Docker 镜像并推送到 GHCR
- 默认组件：`frontend,encaps-layer,sql-gateway,catalog,rule-engine`
- 镜像标签：`${{ github.sha }}`（精确版本）+ `latest`（滚动版本）
- 输出：镜像列表（`image-list`）供后续作业引用

#### 5.3.2 作业 2：cosign-sign

- 职责：对已推送镜像进行 cosign keyless 签名
- 依赖：作业 1 成功完成
- PR 触发时跳过（PR 无权推送签名到 GHCR）
- 产出：签名记录 `sign-record.md`（含验证命令）

#### 5.3.3 作业 3：generate-sbom

- 职责：使用 Syft 生成源码 SBOM + 镜像 SBOM
- 依赖：作业 1 成功完成
- 格式：SPDX JSON + CycloneDX JSON（双格式）
- 产出：`reports/sbom/` 下所有 SBOM 文件

#### 5.3.4 作业 4：trivy-image-scan

- 职责：使用 Trivy 扫描镜像漏洞（HIGH, CRITICAL）
- 依赖：作业 1 成功完成
- 输出：SARIF（GitHub Security tab）+ JSON（详细报告）
- 产出：漏洞统计摘要写入 `$GITHUB_STEP_SUMMARY`

#### 5.3.5 作业 5：publish-supply-chain-artifacts

- 职责：聚合所有供应链安全制品到统一 artifact
- 依赖：作业 2 + 作业 3 + 作业 4 完成
- 产出：`supply-chain-security-artifacts` artifact（保留 90 天）

### 5.4 权限配置

表：Workflow 权限说明表

| 权限 | 用途 |
|------|------|
| `contents: read` | 读取代码 |
| `packages: write` | 推送 GHCR 镜像 |
| `id-token: write` | cosign keyless 签名所需 OIDC token |
| `attestations: write` | cosign attestation 附加签名 |
| `security-events: write` | Trivy SARIF 上传到 GitHub Security tab |

## 6. 验证步骤

### 6.1 验证镜像签名

命令示例：验证 keyless 签名

```bash
# 1. 安装 cosign
go install github.com/sigstore/cosign/v2/cmd/cosign@latest

# 2. 验证签名（替换 <org> 和 <sha>）
cosign verify ghcr.io/<org>/sq-frontend:<sha> \
  --certificate-identity-regexp "https://github.com/<org>/DataEngineBDP/.github/workflows/.*" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com"

# 3. 查看签名详情（attestation）
cosign download attestation ghcr.io/<org>/sq-frontend:<sha>
```

### 6.2 查看 SBOM

命令示例：查看 SBOM 内容

```bash
# 1. 从 GitHub Actions artifact 下载 SBOM
#    路径：Actions → 对应运行 → Artifacts → supply-chain-security-artifacts

# 2. 查看 SPDX SBOM 摘要
jq '.packages | length' source-sbom.spdx.json
jq '.packages[] | {name, versionInfo}' source-sbom.spdx.json | head -50

# 3. 查看 CycloneDX SBOM 摘要
jq '.components | length' source-sbom.cyclonedx.json
jq '.components[] | {name, version}' source-sbom.cyclonedx.json | head -50

# 4. 查看特定组件的镜像 SBOM
jq '.packages[] | select(.name | contains("log4j"))' encaps-layer-image.spdx.json
```

### 6.3 查看漏洞扫描报告

命令示例：查看 Trivy 扫描结果

```bash
# 1. 在 GitHub Security tab 查看 SARIF 结果
#    路径：仓库 → Security → Code scanning alerts

# 2. 从 artifact 下载 JSON 报告后查看
jq '.Results[] | {Target, Type, Vulnerabilities: (.Vulnerabilities | length)}' encaps-layer-vuln.json

# 3. 查看特定漏洞详情
jq '.Results[].Vulnerabilities[] | select(.Severity=="CRITICAL") | {VulnerabilityID, PkgName, InstalledVersion, FixedVersion, Title}' encaps-layer-vuln.json
```

### 6.4 流水线运行验证

在 GitHub Actions 中验证流水线：

1. 进入仓库 → Actions → "Image Sign & SBOM"
2. 点击 "Run workflow" 手动触发，或观察 push/PR 自动触发
3. 检查各作业执行状态：
   - build-and-push：应成功构建并推送镜像
   - cosign-sign：应成功签名（PR 触发时跳过）
   - generate-sbom：应生成 SBOM 文件
   - trivy-image-scan：应生成扫描报告
   - publish-supply-chain-artifacts：应聚合制品
4. 下载 `supply-chain-security-artifacts` artifact，检查内容完整性

## 7. 生产环境注意事项

### 7.1 密钥管理

| 项目 | 开发/测试环境 | 生产环境 |
|------|-------------|---------|
| cosign 模式 | keyless（OIDC） | keyless 或 key-based（合规要求时） |
| 密钥存储 | GitHub Secrets | HSM 或 KMS 管理的密钥服务 |
| 密钥轮换 | 不强制 | 每 90 天轮换一次 |
| 访问控制 | 仓库管理员可配置 | 限制 Secrets 写入权限到安全团队 |

key-based 模式下私钥（`COSIGN_PRIVATE_KEY`）严禁提交到代码仓库，必须通过 GitHub Secrets 或外部 KMS 注入。

### 7.2 Registry 配置

生产环境 registry 需满足以下要求：

- 启用镜像签名支持（GHCR 原生支持；自建 Harbor 需启用 cosign 扩展）
- 配置镜像拉取策略：仅允许拉取已签名镜像（通过 admission controller 强制）
- 启用镜像不可变标签（防止标签被覆盖后签名失效）
- 配置镜像保留策略（已签名镜像保留期 ≥ 90 天，支撑审计回溯）

### 7.3 策略门禁

生产环境建议启用以下门禁：

表：生产环境策略门禁说明表

| 门禁项 | 配置 | 阻断方式 |
|--------|------|---------|
| 签名验证 | 部署前 `cosign verify` | OPA/Gatekeeper admission policy |
| CRITICAL 漏洞 | `--exit-code 1` | 阻断流水线，禁止发布 |
| HIGH 漏洞 | 数量 > 10 时阻断 | 流水线脚本判断 |
| SBOM 完整性 | SBOM 文件必须存在 | 流水线 `if-no-files-found: error` |
| 签名身份 | 限定本仓库 workflow | `--certificate-identity-regexp` 严格匹配 |

启用 CRITICAL 漏洞阻断的配置示例：

代码示例：生产环境漏洞阻断配置（YAML）

```yaml
- name: 扫描镜像漏洞（生产门禁）
  run: |
    trivy image --exit-code 1 \
      --format json --output vuln.json \
      --scanners vuln --severity CRITICAL \
      --ignore-unfixed \
      "$IMAGE_REF"
    # --exit-code 1：发现 CRITICAL 漏洞时退出码非零，阻断流水线
```

### 7.4 部署侧签名验证

生产集群建议部署 OPA Gatekeeper 或 Kyverno 策略，在镜像拉取前强制验证签名：

代码示例：Kyverno 签名验证策略（YAML）

```yaml
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: verify-image-signatures
spec:
  validationFailureAction: enforce
  rules:
    - name: verify-cosign-signature
      match:
        resources:
          kinds:
            - Pod
      verifyImages:
        - imageReferences:
            - "ghcr.io/levango7/sq-*"
          attestors:
            - entries:
                - keyless:
                    subject: "https://github.com/Levango7/DataEngineBDP/.github/workflows/*"
                    issuer: "https://token.actions.githubusercontent.com"
```

### 7.5 审计与监控

- 定期审查签名记录：artifact `image-sign-record` 保留 30 天，关键版本应归档至长期存储
- 监控签名失败率：在 GitHub Actions insights 中观察 `cosign-sign` 作业失败率
- SBOM 对比：定期对比相邻版本 SBOM，检测新增依赖（可能引入供应链风险）
- 漏洞趋势：通过 GitHub Security tab 跟踪漏洞数量趋势，评估修复进展

### 7.6 灾备考虑

- sigstore Rekor 透明日志服务可用性：keyless 模式依赖 Rekor 可用，灾备场景可切换到 key-based 模式
- GHCR 服务可用性：签名存储在 GHCR，GHCR 不可用时无法验证签名，建议关键镜像同步到备选 registry
- 签名密钥备份：key-based 模式下私钥必须离线备份，丢失后已签名镜像无法重新签名

## 8. 故障排查

### 8.1 常见问题

表：常见问题与解决方案对照表

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| cosign 签名失败：`oidc: fetch token: 403` | workflow 缺少 `id-token: write` 权限 | 在 workflow `permissions` 中添加 `id-token: write` |
| cosign 签名失败：`access denied` | GHCR 镜像仓库权限不足 | 确认 `packages: write` 权限；检查仓库 packages 设置 |
| Syft 生成 SBOM 为空 | 源码目录无识别的依赖文件 | 确认 `pom.xml`/`package.json`/`go.mod` 等文件存在 |
| Trivy 扫描超时 | 镜像过大或网络慢 | 增加 `timeout-minutes`；使用 `--ignore-unfixed` 减少扫描量 |
| SARIF 上传失败 | `security-events: write` 权限缺失 | 在 workflow `permissions` 中添加 `security-events: write` |
| 签名验证失败：`no matching signatures` | 镜像未被签名或身份不匹配 | 确认镜像已签名；检查 `--certificate-identity-regexp` 匹配规则 |

### 8.2 调试模式

手动触发 workflow 时启用调试日志：

命令示例：启用 Actions 调试日志

```bash
# 在仓库重新运行 workflow 时勾选 "Enable debug logging"
# 或设置仓库变量
gh variable set ACTIONS_STEP_DEBUG_MODE --body true
```

## 9. 参考资源

- [cosign 官方文档](https://github.com/sigstore/cosign)
- [Syft 官方文档](https://github.com/anchore/syft)
- [Trivy 官方文档](https://aquasecurity.github.io/trivy/)
- [sigstore 透明日志（Rekor）](https://github.com/sigstore/rekor)
- [SPDX 规范](https://spdx.github.io/spdx-spec/)
- [CycloneDX 规范](https://cyclonedx.org/)
- [GitHub Actions OIDC](https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/about-security-hardening-with-openid-connect)
- [SLSA 供应链安全框架](https://slsa.dev/)