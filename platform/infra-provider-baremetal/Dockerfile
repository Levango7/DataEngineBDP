# syntax=docker/dockerfile:1.6
# Dockerfile for infra-provider-baremetal
# 多阶段构建: golang构建 → alpine运行
#
# 构建: docker build -t infra-provider-baremetal:0.1.0 .
# 运行: docker run -p 8080:8080 -v $(pwd)/data:/app/data infra-provider-baremetal:0.1.0

###############################################################################
# 阶段1: 构建
###############################################################################
ARG BASE_REGISTRY=docker.m.daocloud.io
FROM ${BASE_REGISTRY}/library/golang:1.26-alpine AS builder
ARG TARGETARCH

# 安装构建依赖(纯Go无需gcc，但保留git用于私有模块)
RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /build

# 先拷贝go.mod/go.sum利用缓存层
COPY go.mod go.sum* ./

# 下载依赖
RUN go mod download

# 拷贝源码
COPY . .

# 静态构建(CGO_ENABLED=0 纯Go)
ARG VERSION=0.1.0
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} \
    go build \
    -trimpath \
    -ldflags="-s -w -X main.Version=${VERSION}" \
    -o /out/infra-provider-baremetal \
    ./src

# 拷贝配置文件
RUN mkdir -p /out/config && cp -r src/config/config.yaml /out/config/config.yaml

###############################################################################
# 阶段2: 运行
###############################################################################
FROM ${BASE_REGISTRY}/library/alpine:3.20 AS runtime

# brotli-libs 基座为 1.2.0-r1（CVE-2026-111497），Alpine 仓库已有修复版 1.2.1-r0；
# libexpat 的豁免记的是 2.8.5-r0→2.9.0-r0，2026-11-02 新拉基座已随上游重建到
# 2.9.0-r0，但 CI 可能复用旧层，故一并列入升级。docs/trivy-image-ignore.yaml 的
# 豁免按 CVE 号全局生效，出货镜像仍须构建期自证修复（与已兜底的 8 个文件同口径）。
# musl/musl-utils（2026-11-06 补）：CVE-2026-107891（HIGH，1.2.5-r3→1.2.5-r4），
# alpine:3.20 基座引用被 Trivy Image Scan 命中；出货镜像构建期自升，基座引用那一面
# 记限期豁免（同族口径）。
RUN apk upgrade --no-cache libexpat brotli-libs musl musl-utils

# 安装最小运行时依赖
RUN apk add --no-cache ca-certificates tzdata curl && \
    update-ca-certificates

# 创建非root用户
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

# 从构建阶段拷贝二进制与配置
COPY --from=builder /out/infra-provider-baremetal /app/infra-provider-baremetal
COPY --from=builder /out/config/config.yaml /app/config/config.yaml

# 创建数据目录(SQLite存储)
RUN mkdir -p /app/data && chown -R app:app /app

# 切换非root用户
USER app

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8080/healthz || exit 1

# 入口
ENTRYPOINT ["/app/infra-provider-baremetal"]
CMD ["--config", "/app/config/config.yaml"]