# Cosign + Sigstore Rekor 透明日志配置指南

> 本文档对应供应链安全修复项 **M7**：cosign 签名接入 Sigstore 公共 Rekor 透明日志。
> 适用于 `DataEngineBDP` 项目镜像签名流水线 `.github/workflows/image-sign-sbom.yml`。

## 目录

- [1. 背景与问题](#1-背景与问题)
- [2. Sigstore / Rekor 透明日志介绍](#2-sigstore--rekor-透明日志介绍)
- [3. 签名方案对比](#3-签名方案对比)
- [4. 方案 A：key 签名 + tlog 上传](#4-方案-akey-签名--tlog-上传)
- [5. 方案 B：keyless 签名（当前采用，推荐）](#5-方案-bkeyless-签名当前采用推荐)
- [6. 方案 C：私有 Sigstore 部署](#6-方案-c私有-sigstore-部署)
- [7. GitHub Actions 配置示例](#7-github-actions-配置示例)
- [8. 验证命令示例](#8-验证命令示例)
- [9. 故障排查](#9-故障排查)
- [10. 升级路径](#10-升级路径)

---

## 1. 背景与问题

### 修复前状态

原流水线中 cosign 签名存在以下隐患：

- 未显式启用 Rekor 透明日志上传（`--tlog-upload` 未声明）
- 未配置 `REKOR_URL` 环境变量，签名记录无法被公开审计
- 缺少签名后实际执行 `cosign verify` 的校验步骤（仅生成验证命令文档）

### 修复后状态

- 显式声明 `--tlog-upload=true`，签名记录写入公共 Rekor 透明日志
- 配置 `REKOR_URL=https://rekor.sigstore.dev` 与 `FULCIO_URL=https://fulcio.sigstore.dev`
- 新增"验证签名（Rekor 透明日志校验）"步骤，签名后立即执行 `cosign verify`
- 验证使用 `--certificate-identity-regexp` 与 `--certificate-oidc-issuer` 校验签名身份

### M7 风险等级

| 维度 | 修复前 | 修复后 |
|------|--------|--------|
| 签名可审计性 | 仅本地存储，无法公开验证 | 写入公共 Rekor，全球可审计 |
| 签名时间戳 | 无可信时间证明 | Rekor 提供 Merkle 时间戳 |
| 签名防抵赖 | 签名方可否认签名行为 | 透明日志不可篡改，无法抵赖 |
| 验证闭环 | 仅生成命令文档，未实际验证 | 签名后立即执行 verify 校验 |

---

## 2. Sigstore / Rekor 透明日志介绍

### 2.1 Sigstore 项目

[Sigstore](https://www.sigstore.dev/) 是由 Linux 基金会托管的开源软件签名项目，由三个核心组件构成：

| 组件 | 作用 | 公共服务地址 |
|------|------|-------------|
| **Fulcio** | 短期证书签发机构（CA），基于 OIDC 身份签发签名证书 | `https://fulcio.sigstore.dev` |
| **Rekor** | 透明日志服务，存储签名记录，提供 Merkle 时间戳 | `https://rekor.sigstore.dev` |
| **Cosign** | 客户端工具，负责签名、验证、证书管理 | — |

### 2.2 Rekor 透明日志

Rekor 是一个**仅追加（append-only）的 Merkle 透明日志**，具备以下特性：

- **不可篡改**：所有记录通过 Merkle 树哈希链式关联，任何篡改都会破坏根哈希
- **公开可审计**：任何人都可以从公共 Rekor 检索签名记录，独立验证
- **可信时间戳**：Rekor 为每条记录提供 inclusion proof，证明记录在特定时间已存在
- **全球统一**：公共 Rekor 实例由 Sigstore 社区运维，所有签名方共享同一日志

### 2.3 签名流程（keyless 模式）

```
┌─────────────┐     OIDC Token      ┌─────────────┐
│  GitHub CI  │ ──────────────────→ │  OIDC IdP   │
│ (cosign)    │ ←────────────────── │ (GitHub)    │
└──────┬──────┘    短期签名证书      └─────────────┘
       │
       │ 1. 用证书私钥签名镜像
       │ 2. 上传签名记录到 Rekor
       ▼
┌─────────────┐                ┌─────────────┐
│  GHCR 镜像  │                │    Rekor    │
│  (签名附加) │                │ (透明日志)  │
└─────────────┘                └─────────────┘
       │                              │
       └──────────┬───────────────────┘
                  ▼
         cosign verify 时同时校验：
         ① 镜像签名有效性
         ② 证书由 Fulcio 签发（OIDC 身份匹配）
         ③ 签名记录存在于 Rekor 透明日志
```

---

## 3. 签名方案对比

| 特性 | 方案 A：key + tlog | 方案 B：keyless + tlog | 方案 C：私有 Sigstore |
|------|-------------------|----------------------|---------------------|
| 私钥管理 | 需要管理 cosign 密钥对 | 无需私钥（OIDC 身份绑定） | 无需私钥（私有 OIDC） |
| 签名身份 | 匿名（仅密钥指纹） | 绑定 GitHub workflow | 绑定企业 OIDC 身份 |
| 透明日志 | 公共 Rekor | 公共 Rekor | 私有 Rekor |
| 密钥泄露风险 | 私钥泄露即可伪造签名 | 无私钥，无法泄露 | 无私钥 |
| 适用场景 | 兼容旧系统、离线环境 | 公有云 CI/CD（推荐） | 气隙环境、合规要求 |
| 配置复杂度 | 低 | 低（GitHub 自带 OIDC） | 高（需部署全套 Sigstore） |

> **本项目当前采用方案 B**（keyless + 公共 Rekor），无需管理私钥，签名身份自动绑定 GitHub Actions workflow。

---

## 4. 方案 A：key 签名 + tlog 上传

适用于已有 cosign 密钥对、或需要在无 OIDC 环境签名的场景。

### 4.1 前置准备

```bash
# 生成 cosign 密钥对（会提示设置密码）
cosign generate-key-pair
# 产出：cosign.key（私钥）、cosign.pub（公钥）

# 将私钥与密码存入 GitHub Secrets
# Settings → Secrets and variables → Actions → New repository secret
# - COSIGN_PRIVATE_KEY：cosign.key 的内容
# - COSIGN_PASSWORD：生成时设置的密码
```

### 4.2 签名命令

```bash
# key 签名 + 启用 Rekor 透明日志上传
# --tlog-upload=true 是 cosign v2 的默认值，此处显式声明以明确意图
cosign sign --key cosign.key \
  --tlog-upload=true \
  ghcr.io/<owner>/<image>:<tag>

# 签名时会提示输入密码，CI 中通过 COSIGN_PASSWORD 环境变量自动传入
```

### 4.3 验证命令

```bash
# key 签名验证：cosign 自动从 Rekor 检索签名记录并校验
cosign verify --key cosign.pub \
  ghcr.io/<owner>/<image>:<tag>

# 验证成功输出示例：
# Verification for ghcr.io/.../image:tag --
# The following checks were performed on each of these signatures:
#   - The cosign signature was verified against the payload
#   - The Rekor signature was integrated into the tlog
```

### 4.4 GitHub Actions 片段

```yaml
- name: Key 签名 + Rekor tlog
  env:
    COSIGN_PRIVATE_KEY: ${{ secrets.COSIGN_PRIVATE_KEY }}
    COSIGN_PASSWORD: ${{ secrets.COSIGN_PASSWORD }}
    REKOR_URL: https://rekor.sigstore.dev
  run: |
    echo "$COSIGN_PRIVATE_KEY" > /tmp/cosign.key
    cosign sign --key /tmp/cosign.key --tlog-upload=true "$IMAGE"
    rm -f /tmp/cosign.key
```

---

## 5. 方案 B：keyless 签名（当前采用，推荐）

利用 GitHub Actions 内置 OIDC provider，无需管理私钥，签名身份自动绑定 workflow。

### 5.1 前提条件

1. **GitHub Actions 权限**：workflow 必须声明 `id-token: write`（获取 OIDC token）
2. **cosign 版本**：v2.0+（本项目使用 v2.2.4）
3. **镜像仓库**：已推送到 GHCR 或其他支持 OCI manifest 的仓库

### 5.2 签名命令

```bash
# keyless 签名：cosign 自动获取 OIDC token → Fulcio 签发短命证书 → 签名 → 上传 Rekor
cosign sign --yes --tlog-upload=true ghcr.io/<owner>/<image>:<tag>

# --yes：跳过交互确认（CI 环境）
# --tlog-upload=true：显式启用 Rekor 透明日志上传（v2 默认为 true）
```

### 5.3 验证命令

```bash
# keyless 验证：校验签名身份与 OIDC 签发者
cosign verify ghcr.io/<owner>/<image>:<tag> \
  --certificate-identity-regexp "https://github.com/<owner>/<repo>/.github/workflows/.*" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com"

# 参数说明：
# --certificate-identity-regexp：匹配签名证书的 SAN（Subject Alternative Name）
#   GitHub Actions keyless 签名的身份格式为：
#   https://github.com/<owner>/<repo>/.github/workflows/<workflow>@refs/heads/<branch>
# --certificate-oidc-issuer：OIDC 签发者
#   GitHub Actions 固定为 https://token.actions.githubusercontent.com
```

### 5.4 本项目实际配置

本项目 `image-sign-sbom.yml` 中 cosign-sign 作业的关键配置：

```yaml
permissions:
  id-token: write        # keyless 签名所需 OIDC token
  packages: write        # 推送 GHCR 镜像
  attestations: write    # cosign attestation 附加签名

jobs:
  cosign-sign:
    steps:
      - name: 安装 cosign
        uses: sigstore/cosign-installer@v3
        with:
          cosign-release: 'v2.2.4'

      - name: Keyless 签名（OIDC + Rekor 透明日志，推荐）
        env:
          REKOR_URL: https://rekor.sigstore.dev
          FULCIO_URL: https://fulcio.sigstore.dev
        run: |
          cosign sign --yes --tlog-upload=true "$img"

      - name: 验证签名（Rekor 透明日志校验）
        env:
          REKOR_URL: https://rekor.sigstore.dev
        run: |
          cosign verify "$img" \
            --certificate-identity-regexp "https://github.com/${{ github.repository }}/.github/workflows/.*" \
            --certificate-oidc-issuer "https://token.actions.githubusercontent.com"
```

---

## 6. 方案 C：私有 Sigstore 部署

适用于气隙（air-gapped）环境、合规要求（数据不出网）、或需要自定义签名策略的场景。

### 6.1 组件清单

| 组件 | 部署方式 | 说明 |
|------|---------|------|
| Rekor | Kubernetes Deployment | 透明日志服务，需持久化存储 |
| Fulcio | Kubernetes Deployment | 短期 CA，需配置 OIDC 签发者 |
| OIDC IdP | Dex / Keycloak | 私有身份提供者 |
| CT Log | Trillian | 证书透明度日志（Fulcio 依赖） |
| TUF | tuf-on-ci | 更新分发框架 |

### 6.2 签名命令（指向私有 Sigstore）

```bash
# 指向私有 Rekor 与 Fulcio
cosign sign --yes --tlog-upload=true \
  --rekor-url https://rekor.internal.example.com \
  --fulcio-url https://fulcio.internal.example.com \
  ghcr.io/<owner>/<image>:<tag>
```

### 6.3 验证命令（指向私有 Sigstore）

```bash
cosign verify ghcr.io/<owner>/<image>:<tag> \
  --rekor-url https://rekor.internal.example.com \
  --certificate-identity-regexp "https://github.internal.example.com/.*" \
  --certificate-oidc-issuer "https://oidc.internal.example.com"
```

### 6.4 部署参考

- 官方部署指南：<https://docs.sigstore.dev/install/>
- 开源部署工具：[sigstore-probers](https://github.com/sigstore/probers)、[sigstore-the-hard-way](https://github.com/sigstore/sigstore-the-hard-way)
- 托管方案：[GitHub Private Sigstore（企业版）](https://docs.github.com/en/enterprise-server/admin/enterprise-security/managing-private-support-for-sigstore)

---

## 7. GitHub Actions 配置示例

### 7.1 完整 workflow 片段（keyless + Rekor）

```yaml
name: Sign Images

on:
  push:
    branches: [main]

permissions:
  contents: read
  packages: write
  id-token: write       # keyless 签名必需
  attestations: write   # attestation 签名

jobs:
  sign:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: sigstore/cosign-installer@v3
        with:
          cosign-release: 'v2.2.4'

      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Keyless 签名 + Rekor 透明日志
        env:
          REKOR_URL: https://rekor.sigstore.dev
          FULCIO_URL: https://fulcio.sigstore.dev
        run: |
          IMAGE="ghcr.io/${{ github.repository_owner }}/myapp:${{ github.sha }}"
          # 签名并上传到 Rekor 透明日志
          cosign sign --yes --tlog-upload=true "$IMAGE"

      - name: 验证签名
        env:
          REKOR_URL: https://rekor.sigstore.dev
        run: |
          IMAGE="ghcr.io/${{ github.repository_owner }}/myapp:${{ github.sha }}"
          cosign verify "$IMAGE" \
            --certificate-identity-regexp "https://github.com/${{ github.repository }}/.github/workflows/.*" \
            --certificate-oidc-issuer "https://token.actions.githubusercontent.com"
```

### 7.2 权限说明

| 权限 | 用途 | 是否必需 |
|------|------|---------|
| `id-token: write` | 获取 GitHub OIDC token（keyless 签名） | keyless 必需 |
| `packages: write` | 推送镜像到 GHCR | 是 |
| `attestations: write` | 写入 cosign attestation | 是（attestation 场景） |
| `contents: read` | 读取仓库代码 | 是 |

> **安全提示**：`id-token: write` 仅允许当前 workflow 获取 OIDC token，不会泄露给其他 workflow。遵循最小权限原则。

---

## 8. 验证命令示例

### 8.1 本地验证已签名镜像

```bash
# 安装 cosign
# macOS:  brew install cosign
# Linux:  参见 https://docs.sigstore.dev/cosign/installation/

# 验证 keyless 签名（本项目采用的方式）
cosign verify ghcr.io/<owner>/sq-frontend:<sha> \
  --certificate-identity-regexp "https://github.com/<owner>/DataEngineBDP/.github/workflows/.*" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com"
```

### 8.2 查询 Rekor 透明日志

```bash
# 查询某镜像的签名记录在 Rekor 中的条目
cosign attestations ghcr.io/<owner>/sq-frontend:<sha>

# 直接通过 Rekor API 查询
curl -s "https://rekor.sigstore.dev/api/v1/log/entries" \
  -H "Content-Type: application/json" \
  -d '{"hash": {"algorithm": "sha256", "value": "<manifest-digest-sha256>"}}'
```

### 8.3 批量验证脚本

```bash
#!/usr/bin/env bash
# verify-all-signatures.sh — 批量验证本项目所有镜像签名
set -euo pipefail

REPO="your-org/DataEngineBDP"
SHA="${1:?用法: verify-all-signatures.sh <commit-sha>}"
REGISTRY_NS="your-org"

IDENTITY_REGEXP="https://github.com/${REPO}/.github/workflows/.*"
OIDC_ISSUER="https://token.actions.githubusercontent.com"

IMAGES=(
  "ghcr.io/${REGISTRY_NS}/sq-frontend:${SHA}"
  "ghcr.io/${REGISTRY_NS}/sq-encaps-layer:${SHA}"
  "ghcr.io/${REGISTRY_NS}/sq-sql-gateway:${SHA}"
  "ghcr.io/${REGISTRY_NS}/sq-catalog:${SHA}"
  "ghcr.io/${REGISTRY_NS}/sq-rule-engine:${SHA}"
)

fail=0
for img in "${IMAGES[@]}"; do
  echo "验证: $img"
  if cosign verify "$img" \
    --certificate-identity-regexp "$IDENTITY_REGEXP" \
    --certificate-oidc-issuer "$OIDC_ISSUER"; then
    echo "  ✓ 通过"
  else
    echo "  ✗ 失败"
    fail=$((fail + 1))
  fi
done

if [ "$fail" -gt 0 ]; then
  echo "错误：$fail 个镜像签名验证失败"
  exit 1
fi
echo "全部验证通过"
```

---

## 9. 故障排查

### 9.1 签名失败：OIDC token 获取失败

```
Error: getting key from fulcio: getting cert: rpc error: ... permission denied
```

**原因**：workflow 未声明 `id-token: write` 权限。

**解决**：在 workflow `permissions` 中添加 `id-token: write`。

### 9.2 验证失败：证书身份不匹配

```
Error: unable to verify: no matching signatures
```

**原因**：`--certificate-identity-regexp` 与实际签名身份不匹配。

**排查**：

```bash
# 查看签名证书的实际身份
cosign verify ghcr.io/<owner>/<image>:<tag> --output-certificate /tmp/cert.pem
openssl x509 -in /tmp/cert.pem -noout -text | grep -A1 "Subject Alternative Name"
```

**解决**：调整 `--certificate-identity-regexp` 以匹配实际身份。

### 9.3 签名失败：Rekor 上传超时

```
Error: uploading to tlog: ... deadline exceeded
```

**原因**：网络问题或 Rekor 服务暂时不可用。

**解决**：

- 重试（公共 Rekor 偶尔会有抖动）
- 如需跳过 tlog（不推荐，仅应急）：`cosign sign --tlog-upload=false`（会失去公开审计能力）

### 9.4 验证失败：Rekor 中找不到记录

```
Error: ... tlog entry not found
```

**原因**：签名时未上传到 Rekor（使用了 `--tlog-upload=false`）。

**解决**：重新签名并确保 `--tlog-upload=true`。

---

## 10. 升级路径

```
当前状态（M7 修复后）
    │
    │  keyless + 公共 Rekor（方案 B）
    │  ✓ 无需私钥管理
    │  ✓ 公开可审计
    │
    ├──→ 可选增强 1：添加 attestation（SBOM 签名附加到镜像）
    │      cosign attest --predicate sbom.spdx.json --type spdxjson
    │
    ├──→ 可选增强 2：配置 Rekor 监控告警
    │      监控签名异常、非工作时间签名、未知身份签名
    │
    └──→ 合规升级：迁移到私有 Sigstore（方案 C）
           适用于：数据不出网、行业合规、企业内 OIDC
```

---

## 附录：相关文件

| 文件 | 说明 |
|------|------|
| `.github/workflows/image-sign-sbom.yml` | 镜像签名 + SBOM + 漏洞扫描流水线 |
| `docs/COSIGN-TLOG-SETUP.md` | 本文档 |
| `docs/SECRETS-MANAGEMENT-GUIDE.md` | 密钥管理指南（含 cosign 密钥方案） |

## 附录：参考链接

- [Sigstore 官方文档](https://docs.sigstore.dev/)
- [Cosign GitHub](https://github.com/sigstore/cosign)
- [Rekor 透明日志](https://github.com/sigstore/rekor)
- [GitHub Actions OIDC 文档](https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/configuring-openid-connect-in-cloud-providers)
- [SLSA 供应链安全框架](https://slsa.dev/)