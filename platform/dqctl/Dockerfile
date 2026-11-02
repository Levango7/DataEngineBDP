# syntax=docker/dockerfile:1
# 多阶段构建：构建阶段
ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/golang:1.26-alpine AS builder
ARG TARGETARCH

WORKDIR /build

# 先拷贝 go.mod 以利用 Docker 层缓存加速依赖下载。
COPY go.mod ./
RUN go mod download

# 拷贝源码并构建静态二进制。
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build -ldflags="-s -w" -o dqctl .

# 运行阶段：最小化镜像
FROM ${BASE_REGISTRY}/library/alpine:3.20

# 基座自带 libexpat 2.8.5-r0（CVE-2026-77214）与 brotli-libs 1.2.0-r1（CVE-2026-111497），
# Alpine 仓库已有修复版 2.9.0-r0 / 1.2.1-r0。镜像门禁扫的是基座引用本身，且
# docs/trivy-image-ignore.yaml 的豁免是按 CVE 号全局生效的——出货镜像必须在这里
# 升到修复版，不能只靠豁免账（与已兜底的 8 个文件同一口径）。
RUN apk upgrade --no-cache libexpat brotli-libs

# 创建非 root 用户。
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

# 从构建阶段拷贝二进制。
COPY --from=builder --chown=app:app /build/dqctl /usr/local/bin/dqctl

# 确保 app 用户对工作目录可写。
RUN chown -R app:app /app

# 切换非 root 用户运行。
USER app

ENTRYPOINT ["dqctl"]