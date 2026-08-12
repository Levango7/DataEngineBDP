# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io/library

# ============ 构建阶段 ============
FROM ${BASE_REGISTRY}/golang:1.26-alpine AS builder
ARG TARGETARCH

WORKDIR /build

# 先复制依赖描述文件，利用层缓存加速依赖下载。
COPY go.mod ./
# 注意：本项目暂无 go.sum，使用 GOPROXY 拉取 gin。
ENV GOPROXY=https://goproxy.cn,direct

# 复制源码。
COPY . .

# mod下载在 QEMU 下比在 host 慢得多，go mod tidy 也会联网校验，故使用 download（安全且稳态）
RUN go mod download

# 静态编译，禁用 cgo 以适配 alpine 运行时。
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build -trimpath -ldflags="-s -w" -o /out/catalog .

# ============ 运行阶段 ============
FROM ${BASE_REGISTRY}/alpine:3.19 AS runtime

# 安装最小运行时依赖（ca-certificates 用于 HTTPS 调用，curl 用于 docker-compose healthcheck）。
RUN apk add --no-cache ca-certificates tzdata curl

# 创建非 root 用户。
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=builder /out/catalog /app/catalog

# 确保 app 用户对 /app 可写（SQLite 文件 catalog.db 需要落盘）。
RUN chown -R app:app /app

# 切换非 root 用户运行。
USER app

EXPOSE 8082

# 健康检查（容器层）。
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8082/api/v1/health || exit 1

ENTRYPOINT ["/app/catalog"]