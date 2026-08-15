# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io/library

# ============ 构建阶段 ============
FROM ${BASE_REGISTRY}/golang:1.26-alpine AS builder
ARG TARGETARCH

WORKDIR /build

# 先复制依赖描述文件，利用层缓存加速依赖下载。
COPY go.mod go.sum ./

# 下载依赖（纯 Go SQLite 驱动无需 CGO）
ENV GOPROXY=https://goproxy.cn,direct
RUN go mod download

# 复制源码并构建。
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build \
    -trimpath -ldflags="-s -w" -o /out/ai-assistant ./cmd/server

# ============ 运行阶段 ============
FROM ${BASE_REGISTRY}/alpine:3.19 AS runtime

RUN apk add --no-cache ca-certificates tzdata curl

WORKDIR /app

COPY --from=builder /out/ai-assistant /app/ai-assistant

# 会话数据目录（SQLite）
RUN mkdir -p /app/data && addgroup -S app && adduser -S app -G app \
    && chown -R app:app /app

USER app

EXPOSE 18110

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -fsS http://localhost:18110/api/v1/health || exit 1

ENTRYPOINT ["/app/ai-assistant"]
