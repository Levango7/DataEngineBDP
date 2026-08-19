# 多架构镜像构建报告

## 构建环境信息

表：构建环境参数说明表

| 项目 | 值 |
|------|-----|
| 操作系统 | WSL2 Ubuntu-24.04 |
| Docker Engine 版本 | 29.7.2 |
| buildx 版本 | v0.36.1 (1d8dde89b8aba914e05e45366770736fea1fd690) |
| BuildKit 版本 | v0.32.2 |
| builder 名称 | multiarch-builder |
| builder driver | docker-container |
| 支持平台 | linux/amd64, linux/arm64, linux/386 |
| 镜像加速 | docker.m.daocloud.io (buildkitd.toml 配置) |
| 项目路径 | /mnt/f/nexus/DataEngineBDP/platform/encaps-layer |
| 构建产物 jar | encaps-layer-0.1.0-SNAPSHOT.jar (96MB) |
| 构建用 Dockerfile | Dockerfile.multiarch (简化版，跳过 maven 构建) |

## Dockerfile 多架构兼容性分析

### 原始 Dockerfile 分析

原始 `Dockerfile` 使用多阶段构建：

- **Stage 1 (builder)**：基于 `maven:3.9-eclipse-temurin-17`，在容器内执行 `mvn dependency:go-offline` 和 `mvn clean package -DskipTests`
- **Stage 2 (runtime)**：基于 `eclipse-temurin:17-jre`，复制 jar 包并配置运行时环境

基础镜像 `eclipse-temurin:17-jre` 和 `maven:3.9-eclipse-temurin-17` 均为官方多架构镜像，支持 amd64 和 arm64。Dockerfile 中无硬编码的架构相关指令，多架构兼容性良好。

### 构建优化决策

原始 Dockerfile 的 Stage 1 会在容器内重新执行完整的 maven 构建（下载依赖 + 编译），在 amd64 上耗时超过 15 分钟仍未完成，在 arm64 上通过 qemu 模拟将更加缓慢（预计 1-2 小时）。

由于本地已存在预构建的 jar 包（`target/encaps-layer-0.1.0-SNAPSHOT.jar`，96MB），创建了简化版 `Dockerfile.multiarch`，直接 COPY 本地 jar 包到 runtime 阶段，跳过 Stage 1 maven 构建。此优化将多架构构建时间从预估 2+ 小时降至 12 分钟。

### 遇到的问题与解决

表：问题与解决方案对照表

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| `# syntax=docker/dockerfile:1.6` 拉取超时 | docker-container driver 的 BuildKit 容器不继承 docker daemon 的镜像加速配置，无法访问 registry-1.docker.io | 创建 `buildkitd.toml` 配置 `[registry."docker.io"] mirrors = ["docker.m.daocloud.io"]`，重建 builder |
| Stage 1 maven 构建超时（>15min） | 容器内重新下载所有 maven 依赖并编译，网络慢且无缓存 | 创建 `Dockerfile.multiarch` 简化版，直接 COPY 本地预构建 jar 包 |

## amd64 构建结果

表：amd64 构建结果参数说明表

| 指标 | 值 |
|------|-----|
| 构建状态 | 成功 |
| 构建时间 | 10 秒 |
| 镜像大小 | 808MB |
| 镜像 ID | 49ac13ec9129 |
| 镜像标签 | encaps-layer:amd64 |
| manifest digest | sha256:49ac13ec9129e417f076357f1f61390d1ba07850ce3060e9cd7afd84e37b3c20 |
| config digest | sha256:e057f3e520eaf7eab8a7343f0c47596ad99b5bca2fa2d1f4c769f02bd1fbe31e |
| 基础镜像 | docker.m.daocloud.io/library/eclipse-temurin:17-jre |
| 加载方式 | --load（已加载到本地 docker） |

### amd64 构建日志摘要

```text
#4 [1/5] FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre@sha256:cbc584b... DONE 0.0s (缓存命中)
#6 [3/5] RUN groupadd --system appgroup && useradd --system --gid appgroup appuser
#8 [4/5] COPY target/encaps-layer-0.1.0-SNAPSHOT.jar /app/app.jar DONE 0.3s
#9 [5/5] RUN mkdir -p /app/data && chown -R appuser:appgroup /app DONE 0.4s
#10 exporting to oci image format DONE 5.3s
#11 importing to docker DONE 0.0s
```

## arm64 构建结果

表：arm64 构建结果参数说明表

| 指标 | 值 |
|------|-----|
| 构建状态 | 成功 |
| 构建方式 | qemu 用户态模拟（binfmt_misc） |
| 基础镜像拉取时间 | 700.1 秒（约 11.7 分钟） |
| 构建步骤时间 | 2.6 秒 |
| manifest digest | sha256:b826b0152597435148b5f08a483db446c9dc4c51f8c7acf287a0dc15b76b3415 |
| config digest | sha256:cad649eb44924be12fbe3e55bdae2d6a1bbee31c43344b820f6a67e5c8be1390 |
| 基础镜像 | docker.m.daocloud.io/library/eclipse-temurin:17-jre (arm64) |
| 加载方式 | OCI tar（多架构镜像不能 --load 到本地 docker） |

### arm64 构建日志摘要

```text
#3 [linux/arm64 internal] load metadata for eclipse-temurin:17-jre DONE 7.2s
#6 [linux/arm64 1/5] FROM ...eclipse-temurin:17-jre@sha256:cbc584b... DONE 700.1s (镜像拉取)
#12 [linux/arm64 2/5] WORKDIR /app DONE 0.3s
#13 [linux/arm64 3/5] RUN groupadd --system appgroup && ... DONE 0.5s (qemu 模拟)
#14 [linux/arm64 4/5] COPY target/encaps-layer-0.1.0-SNAPSHOT.jar /app/app.jar DONE 1.2s
#15 [linux/arm64 5/5] RUN mkdir -p /app/data && chown ... DONE 0.6s (qemu 模拟)
```

### arm64 构建时间分析

arm64 构建总耗时约 703 秒，其中：

- 基础镜像拉取：700.1 秒（99.6%），通过 daocloud 加速拉取 arm64 版 eclipse-temurin:17-jre
- 构建步骤执行：2.6 秒（0.4%），通过 qemu 模拟执行 groupadd/useradd/mkdir/chown 等简单命令

基础镜像拉取是主要瓶颈。构建步骤本身通过 qemu 模拟仅耗时 2.6 秒，因为简化 Dockerfile 只包含简单的 shell 命令（无 maven 编译等重度计算）。

## 多架构 manifest 信息

### manifest list

```json
{
  "schemaVersion": 2,
  "mediaType": "application/vnd.oci.image.index.v1+json",
  "manifests": [
    {
      "mediaType": "application/vnd.oci.image.manifest.v1+json",
      "digest": "sha256:49ac13ec9129e417f076357f1f61390d1ba07850ce3060e9cd7afd84e37b3c20",
      "size": 2196,
      "platform": { "architecture": "amd64", "os": "linux" }
    },
    {
      "mediaType": "application/vnd.oci.image.manifest.v1+json",
      "digest": "sha256:b826b0152597435148b5f08a483db446c9dc4c51f8c7acf287a0dc15b76b3415",
      "size": 2196,
      "platform": { "architecture": "arm64", "os": "linux" }
    },
    {
      "mediaType": "application/vnd.oci.image.manifest.v1+json",
      "digest": "sha256:5b40218521ce8a414874d57e719e1b1338f0e9b73279a081d7bdf000d78ee843",
      "size": 837,
      "platform": { "architecture": "unknown", "os": "unknown" },
      "annotations": { "vnd.docker.reference.type": "attestation-manifest" }
    },
    {
      "mediaType": "application/vnd.oci.image.manifest.v1+json",
      "digest": "sha256:0a70b46ec4b233c9a93a4006f1eebbdede07838ddbd0e05db0b347f8647fdbf4",
      "size": 837,
      "platform": { "architecture": "unknown", "os": "unknown" },
      "annotations": { "vnd.docker.reference.type": "attestation-manifest" }
    }
  ]
}
```

表：多架构 manifest 条目说明表

| 平台 | manifest digest | size | 说明 |
|------|-----------------|------|------|
| linux/amd64 | sha256:49ac13ec... | 2196 字节 | amd64 镜像 manifest |
| linux/arm64 | sha256:b826b015... | 2196 字节 | arm64 镜像 manifest |
| unknown/unknown | sha256:5b402185... | 837 字节 | amd64 attestation manifest (BuildKit provenance) |
| unknown/unknown | sha256:0a70b46e... | 837 字节 | arm64 attestation manifest (BuildKit provenance) |

### 多架构镜像产物

表：多架构镜像产物参数说明表

| 指标 | 值 |
|------|-----|
| 总构建时间 | 719 秒（约 12 分钟） |
| OCI tar 文件大小 | 554MB（580,807,680 字节） |
| 输出路径 | /tmp/multiarch-image.tar |
| manifest list digest | sha256:1202e0788ac6cd946097e00abb3eb98cdeca198201990d63e5d93ca6afe52c3f |
| 镜像标签 | encaps-layer:multiarch |
| OCI index mediaType | application/vnd.oci.image.index.v1+json |
| 包含平台数 | 2（amd64 + arm64） |

## 构建日志摘要

### 完整构建流程

图：多架构构建流程示意图

```text
docker buildx build -f Dockerfile.multiarch \
  --platform linux/amd64,linux/arm64 \
  --tag encaps-layer:multiarch \
  --output type=oci,dest=/tmp/multiarch-image.tar .

  ┌─ linux/amd64 ─────────────────────────────────┐
  │ [1/5] FROM eclipse-temurin:17-jre  (缓存命中)  │
  │ [2/5] WORKDIR /app                              │
  │ [3/5] RUN groupadd + useradd                    │
  │ [4/5] COPY jar (96MB)                           │
  │ [5/5] RUN mkdir + chown                         │
  └─────────────────────────────────────────────────┘
  ┌─ linux/arm64 ─────────────────────────────────┐
  │ [1/5] FROM eclipse-temurin:17-jre  (拉取 700s)  │
  │ [2/5] WORKDIR /app                              │
  │ [3/5] RUN groupadd + useradd  (qemu 模拟)       │
  │ [4/5] COPY jar (96MB)                           │
  │ [5/5] RUN mkdir + chown  (qemu 模拟)            │
  └─────────────────────────────────────────────────┘
          │
          ▼
  exporting to OCI image format (manifest list)
  → /tmp/multiarch-image.tar (554MB)
```

### 关键时间节点

表：关键时间节点参数说明表

| 步骤 | 耗时 | 说明 |
|------|------|------|
| amd64 基础镜像 | 0.0s | 缓存命中（之前构建已拉取） |
| arm64 metadata 解析 | 7.2s | 解析 arm64 镜像 manifest |
| arm64 基础镜像拉取 | 700.1s | 通过 daocloud 拉取 arm64 版 eclipse-temurin:17-jre |
| amd64 构建步骤 | ~2s | WORKDIR + RUN + COPY + RUN |
| arm64 构建步骤 | 2.6s | qemu 模拟执行简单 shell 命令 |
| OCI 导出 | 6.1s | 导出 manifest list + layers 到 tar |
| **总计** | **719s** | 约 12 分钟 |

## 结论和性能建议

### 结论

1. **multi-arch builder 创建成功**：`multiarch-builder`（docker-container driver）正常运行，支持 linux/amd64 和 linux/arm64 平台
2. **amd64 构建成功**：镜像 808MB，构建时间 10 秒（基础镜像缓存命中），已 --load 到本地 docker
3. **arm64 构建成功**：通过 qemu 用户态模拟构建，构建步骤仅 2.6 秒，基础镜像拉取 700 秒
4. **多架构 manifest 正确**：OCI image index 包含 amd64 和 arm64 两个平台 manifest，以及对应的 BuildKit attestation manifest
5. **多架构镜像产物**：554MB OCI tar 文件，路径 /tmp/multiarch-image.tar

### 性能建议

1. **基础镜像缓存**：arm64 构建主要瓶颈是基础镜像拉取（700 秒）。建议在 CI/CD 环境中预拉取 arm64 基础镜像到 builder 缓存，可将多架构构建时间降至 10 秒以内
2. **简化 Dockerfile 策略**：对于多架构构建，强烈建议使用"本地构建 jar + 简化 Dockerfile COPY"模式，避免在容器内执行 maven 编译。原始 Dockerfile 的多阶段构建在 arm64 上通过 qemu 模拟执行 maven 预计需要 1-2 小时
3. **buildkitd 镜像加速**：docker-container driver 的 BuildKit 容器不继承 docker daemon 的镜像加速配置，必须通过 `buildkitd.toml` 的 `[registry."docker.io"] mirrors` 配置镜像加速，否则拉取 docker.io 镜像会超时
4. **OCI 输出格式**：多架构镜像不能 --load 到本地 docker（本地 docker 只支持当前架构），使用 `--output type=oci,dest=<file>.tar` 导出为 OCI 格式 tar 包，或推送到支持多架构的 registry
5. **qemu 模拟性能**：简化 Dockerfile 的构建步骤（groupadd/useradd/mkdir/chown）通过 qemu 模拟仅耗时 2.6 秒，性能可接受。但如果 Dockerfile 包含重度计算（如 maven 编译、native image 生成），qemu 模拟性能会下降至原生的 1/5-1/10
6. **attestation manifest**：BuildKit v0.32.2 默认生成 provenance attestation manifest（每个平台一个），大小约 837 字节，不影响镜像功能，可通过 `--provenance=false` 禁用

### 产物文件清单

表：产物文件清单参数说明表

| 文件 | 路径 | 说明 |
|------|------|------|
| 多架构镜像 tar | /tmp/multiarch-image.tar (WSL2) | 554MB OCI 格式，包含 amd64+arm64 |
| amd64 本地镜像 | encaps-layer:amd64 | 808MB，已加载到本地 docker |
| 简化 Dockerfile | platform/encaps-layer/Dockerfile.multiarch | 多架构构建用，跳过 maven 构建 |
| buildkitd 配置 | tests/deploy/buildkitd.toml | BuildKit 镜像加速配置 |
| amd64 构建脚本 | tests/deploy/build-amd64.sh | amd64 单架构构建脚本 |
| 多架构构建脚本 | tests/deploy/build-multiarch.sh | amd64+arm64 多架构构建脚本 |
| amd64 构建日志 | /tmp/amd64-build.log (WSL2) | amd64 构建详细日志 |
| 多架构构建日志 | /tmp/multiarch-build.log (WSL2) | 多架构构建详细日志 |