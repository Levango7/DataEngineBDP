# encaps-layer 镜像签名与 SBOM 生成报告

## 1. 执行摘要

本报告记录了 encaps-layer Docker 镜像的构建、SBOM（Software Bill of Materials）生成以及镜像签名与验证的完整过程。所有操作在 WSL2 Ubuntu-24.04 环境中完成，使用 cosign 进行密钥对签名，使用 syft 生成 SPDX 格式的 SBOM。

**执行时间**：2026-08-19 23:00 ~ 23:30（UTC+8）

**执行环境**：WSL2 Ubuntu-24.04，Docker Engine 29.7.2，buildx v0.36.1

---

## 2. 镜像信息

| 属性 | 值 |
|------|-----|
| 镜像名称 | `encaps-layer:local` |
| 镜像 ID | `sha256:8998f76e1a8d812b70e2731caf0e68ec11246aa502ca5fdd00b0bb88044680ee` |
| 架构 | `linux/amd64` |
| 操作系统 | `linux` |
| 磁盘占用 | 808 MB |
| 内容大小 | 291 MB（291,179,122 bytes） |
| 层数 | 10 层 |
| 创建时间 | 2026-08-19T23:19:41+08:00 |
| 基础镜像 | `eclipse-temurin:17-jre`（经 DaoCloud 加速） |
| 构建方式 | 多阶段构建（maven:3.9-eclipse-temurin-17 构建 + eclipse-temurin:17-jre 运行） |
| 运行用户 | 非 root 用户 `appuser` |
| 暴露端口 | 8080 |

### 镜像层结构（自顶向下）

1. `ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]` - 启动命令
2. `ENV JAVA_OPTS=-XX:MaxRAMPercentage=75.0...` - JVM 参数
3. `USER appuser` - 切换非 root 用户
4. `EXPOSE 8080` - 暴露端口
5. `RUN mkdir -p /app/data && chown...` - 创建数据目录
6. `COPY encaps-layer-*.jar /app/app.jar` - 复制应用 JAR
7. `RUN groupadd/useradd appuser` - 创建非 root 用户
8. `WORKDIR /app` - 设置工作目录
9. Eclipse Temurin JRE 基础镜像层（含 entrypoint、JAVA_HOME、apt 包等）
10. Debian 基础系统层

---

## 3. SBOM 信息

| 属性 | 值 |
|------|-----|
| SBOM 格式 | SPDX JSON（SPDX-2.3） |
| 生成工具 | syft（go install 编译，源码版本 v1.18.1） |
| 包总数 | **310 个** |
| 文件路径 | `/mnt/f/nexus/DataEngineBDP/encaps-layer-sbom.spdx.json` |
| 文件大小 | 5,982,586 bytes（约 5.7 MB） |
| 文档名称 | `encaps-layer` |
| SPDX ID | `SPDXRef-DOCUMENT` |

### 包类型分布

SBOM 包含以下类型的组件：

- **Java/Maven 依赖**：HikariCP 5.0.1、angus-activation 2.0.2、antlr4-runtime 4.13.0、aopalliance 1.0、zjsonpatch 0.3.0、zstd-jni 1.5.5-1 等
- **应用自身**：`app 0.1.0-SNAPSHOT`（encaps-layer 应用包）
- **Debian 系统包**：adduser 3.153ubuntu1、apt 3.2.0、wget 1.25.0-2ubuntu4.3、zlib1g 1:1.3.dfsg+really1.3.1-1ubuntu3 等
- **JVM 相关**：HdrHistogram 2.1.12、LatencyUtils 2.0.3 等

### SBOM 生成命令

```bash
syft encaps-layer:local -o spdx-json > /mnt/f/nexus/DataEngineBDP/encaps-layer-sbom.spdx.json
```

---

## 4. 签名信息

| 属性 | 值 |
|------|-----|
| 签名工具 | cosign v2.4.1（Sigstore 项目） |
| 签名方式 | 本地密钥对签名（非 keyless） |
| 密钥类型 | ECDSA P-256（cosign 默认） |
| 密钥密码 | `test123`（环境变量 `COSIGN_PASSWORD`） |
| 私钥文件 | `cosign.key`（653 bytes，加密 PEM 格式） |
| 公钥文件 | `cosign.pub`（178 bytes，PEM 格式） |
| 透明日志上传 | 已禁用（`--tlog-upload=false`） |
| 签名存储 | 本地注册表 `localhost:5000` |
| 签名目标 | `localhost:5000/encaps-layer:local` |
| 签名摘要 | `sha256:8998f76e1a8d812b70e2731caf0e68ec11246aa502ca5fdd00b0bb88044680ee` |

### 签名流程

由于 GitHub 网络不可达，无法使用 keyless（OIDC）签名，改用本地密钥对签名。本地镜像无法直接签名（cosign 需要注册表存储签名），因此：

1. **启动本地注册表**：

   ```bash
   docker run -d --name local-registry -p 5000:5000 registry:2
   ```

2. **推送镜像到本地注册表**：

   ```bash
   docker tag encaps-layer:local localhost:5000/encaps-layer:local
   docker push localhost:5000/encaps-layer:local
   ```

3. **生成密钥对**：

   ```bash
   COSIGN_PASSWORD=test123 cosign generate-key-pair
   ```

4. **签名镜像**：

   ```bash
   COSIGN_PASSWORD=test123 cosign sign \
     --key cosign.key \
     --tlog-upload=false \
     --allow-insecure-registry \
     localhost:5000/encaps-layer:local
   ```

5. **验证签名**：

   ```bash
   cosign verify \
     --key cosign.pub \
     --insecure-ignore-tlog \
     --allow-insecure-registry \
     localhost:5000/encaps-layer:local
   ```

### 验证结果

```
Verification for localhost:5000/encaps-layer:local --
The following checks were performed on each of these signatures:
  - The cosign claims were validated
  - The signatures were verified against the specified public key

[{"critical":{"identity":{"docker-reference":"localhost:5000/encaps-layer"},
"image":{"docker-manifest-digest":"sha256:8998f76e1a8d812b70e2731caf0e68ec11246aa502ca5fdd00b0bb88044680ee"},
"type":"cosign container image signature"},"optional":null}]
```

**验证状态：✅ 通过**

- cosign claims 验证：✅ 通过
- 公钥签名验证：✅ 通过
- 镜像摘要匹配：✅ 通过（sha256:8998f76e...）

---

## 5. 工具版本

### cosign

| 属性 | 值 |
|------|-----|
| 版本 | v2.4.1 |
| GitCommit | unknown（go install 编译） |
| GoVersion | go1.25.14 |
| Platform | linux/amd64 |
| 安装方式 | `go install github.com/sigstore/cosign/v2/cmd/cosign@v2.4.1`（GOPROXY=goproxy.cn） |
| 二进制路径 | `/usr/local/bin/cosign` |
| 二进制大小 | 109,894,016 bytes（约 105 MB） |

### syft

| 属性 | 值 |
|------|-----|
| 版本 | v1.18.1（源码版本，go install 未注入版本元数据） |
| GoVersion | go1.25.14 |
| Platform | linux/amd64 |
| 安装方式 | `go install github.com/anchore/syft/cmd/syft@v1.18.1`（GOPROXY=goproxy.cn） |
| 二进制路径 | `/usr/local/bin/syft` |
| 二进制大小 | 83,730,560 bytes（约 80 MB） |

---

## 6. 网络环境说明

由于 WSL2 环境中 GitHub 不可达（连接超时），cosign 和 syft 无法通过预编译二进制下载安装。解决方案：

- **Go 工具链**：通过 `apt-get install golang-go` 安装 Go 1.22.2
- **Go 模块代理**：使用国内 `GOPROXY=https://goproxy.cn,direct` 加速 Go 模块下载
- **Docker 镜像加速**：DaoCloud（`docker.m.daocloud.io`）用于拉取基础镜像和 registry:2
- **本地注册表**：使用 `registry:2` 作为本地 OCI 注册表，存储镜像签名

---

## 7. 交付物清单

| 文件 | 路径 | 说明 |
|------|------|------|
| SBOM（SPDX JSON） | `F:\nexus\DataEngineBDP\encaps-layer-sbom.spdx.json` | 310 个包的软件物料清单 |
| cosign 私钥 | `F:\nexus\DataEngineBDP\cosign.key` | ECDSA P-256 私钥（加密） |
| cosign 公钥 | `F:\nexus\DataEngineBDP\cosign.pub` | ECDSA P-256 公钥 |
| 本报告 | `F:\nexus\DataEngineBDP\tests\security\COSIGN-SBOM-REPORT.md` | 签名与 SBOM 报告 |
| 签名镜像 | `localhost:5000/encaps-layer:local` | 本地注册表中的已签名镜像 |
| 本地镜像 | `encaps-layer:local` | Docker 本地镜像（与签名镜像同 digest） |

---

## 8. 验证结果汇总

| 检查项 | 状态 | 详情 |
|--------|------|------|
| Docker 镜像构建 | ✅ 成功 | encaps-layer:local，291 MB，10 层 |
| cosign 安装 | ✅ 成功 | v2.4.1，go install 编译 |
| syft 安装 | ✅ 成功 | v1.18.1，go install 编译 |
| SBOM 生成 | ✅ 成功 | SPDX-2.3 JSON，310 个包，5.7 MB |
| 密钥对生成 | ✅ 成功 | ECDSA P-256，cosign.key + cosign.pub |
| 镜像签名 | ✅ 成功 | 本地密钥对签名，存储于 localhost:5000 |
| 签名验证 | ✅ 成功 | 公钥验证通过，摘要匹配 |

---

## 9. 安全建议

1. **密钥保管**：`cosign.key` 私钥已加密（密码 `test123`），生产环境应使用更强密码并安全存储
2. **透明日志**：本次签名跳过了 Rekor 透明日志上传（`--tlog-upload=false`），生产环境建议启用以提供可审计性
3. **Keyless 签名**：网络允许时建议使用 OIDC keyless 签名，避免密钥管理负担
4. **SBOM 持续监控**：310 个依赖包应纳入漏洞监控（如 grype 扫描），及时响应 CVE
5. **签名策略**：建议在 CI/CD 流水线中集成 cosign 签名，确保所有发布镜像均经过签名

---

## 10. 结论

encaps-layer Docker 镜像已成功构建、签名并生成 SBOM。镜像包含 310 个软件组件，使用 cosign v2.4.1 进行本地 ECDSA 密钥对签名，签名验证通过。SBOM 以 SPDX-2.3 JSON 格式生成，符合行业标准。所有交付物已保存至指定路径。

**整体状态：✅ 全部完成**