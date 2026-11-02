# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io

# ============ 构建阶段 ============
FROM ${BASE_REGISTRY}/library/golang:1.26-alpine AS builder
ARG TARGETARCH

WORKDIR /build

# 先复制依赖描述文件，利用层缓存加速依赖下载。
COPY go.mod go.sum ./
ENV GOPROXY=https://goproxy.cn,direct

# 复制源码。
COPY . .

# 静态编译，禁用 cgo 以适配 alpine 运行时。
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build -trimpath -ldflags="-s -w" -o /out/llm-gateway .

# ============ 运行阶段 ============
FROM ${BASE_REGISTRY}/library/alpine:3.20 AS runtime

# brotli-libs 基座为 1.2.0-r1（CVE-2026-111497），Alpine 仓库已有修复版 1.2.1-r0；
# libexpat 的豁免记的是 2.8.5-r0→2.9.0-r0，2026-11-02 新拉基座已随上游重建到
# 2.9.0-r0，但 CI 可能复用旧层，故一并列入升级。docs/trivy-image-ignore.yaml 的
# 豁免按 CVE 号全局生效，出货镜像仍须构建期自证修复（与已兜底的 8 个文件同口径）。
RUN apk upgrade --no-cache libexpat brotli-libs

# 安装最小运行时依赖（ca-certificates 用于 HTTPS 调用大模型 API，curl 用于 healthcheck）。
RUN apk add --no-cache ca-certificates tzdata curl

# 创建非 root 用户。
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=builder /out/llm-gateway /app/llm-gateway

RUN chown -R app:app /app

# 切换非 root 用户运行。
USER app

EXPOSE 8084

# 健康检查（容器层）。
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8084/health || exit 1

ENTRYPOINT ["/app/llm-gateway"]