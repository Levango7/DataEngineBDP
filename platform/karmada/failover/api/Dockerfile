# Failover API Dockerfile
# ============================================================================
# 多阶段构建：编译 Go 二进制 → 运行精简镜像
# ============================================================================

# ---------------------------------------------------------------------------
# 阶段 1：编译
# ---------------------------------------------------------------------------
FROM golang:1.25-alpine AS builder

WORKDIR /build

# 先拷贝 go.mod/go.sum 利用 Docker 缓存加速依赖下载。
COPY go.mod go.sum ./
RUN go mod download

# 拷贝源码。
COPY . .

# 编译静态二进制（CGO_ENABLED=0 适配 SQLite 纯 Go 驱动）。
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o failover-api .

# ---------------------------------------------------------------------------
# 阶段 2：运行
# ---------------------------------------------------------------------------
FROM alpine:3.20

# 安装 ca-certificates（HTTPS 调用 Karmada/Prometheus 需要）。
RUN apk add --no-cache ca-certificates tzdata

WORKDIR /app

# 拷贝二进制。
COPY --from=builder /build/failover-api .

# 暴露端口（与 main.go defaultPort 一致）。
EXPOSE 8094

# 运行。
ENTRYPOINT ["/app/failover-api"]