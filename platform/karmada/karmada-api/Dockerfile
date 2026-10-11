# Karmada PropagationPolicy 控制台 API Dockerfile
# ============================================================================
# 多阶段构建：编译 Go 二进制 → 运行精简镜像
# ============================================================================

# ---------------------------------------------------------------------------
# 阶段 1：编译
# ---------------------------------------------------------------------------
FROM golang:1.26-alpine AS builder
ARG TARGETARCH

WORKDIR /build

# 先拷贝 go.mod/go.sum 利用 Docker 缓存加速依赖下载。
COPY go.mod go.sum ./
RUN go mod download

# 拷贝源码。
COPY . .

# 编译静态二进制（CGO_ENABLED=0 适配 SQLite 纯 Go 驱动 glebarez/sqlite）。
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build -ldflags="-s -w" -o karmada-api .

# ---------------------------------------------------------------------------
# 阶段 2：运行
# ---------------------------------------------------------------------------
FROM alpine:3.20

# 安装 ca-certificates（HTTPS 调用 Karmada 控制面需要，保持 root 执行）。
RUN apk add --no-cache ca-certificates tzdata

# 创建非 root 用户。
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

# 拷贝二进制。
COPY --from=builder --chown=app:app /build/karmada-api .

# 暴露端口（与 main.go defaultPort 一致）。
EXPOSE 8090

# 确保 app 用户对 /app 可写（SQLite 数据库文件需落盘），切换非 root 运行。
RUN chown -R app:app /app
USER app

# 运行。
HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 CMD wget --spider -q http://localhost:8090/api/v1/health

ENTRYPOINT ["/app/karmada-api"]