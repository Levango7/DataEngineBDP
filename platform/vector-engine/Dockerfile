# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io/library

# ============ 构建阶段 ============
FROM ${BASE_REGISTRY}/golang:1.23-alpine AS builder

WORKDIR /build

# 先复制依赖描述文件，利用层缓存加速依赖下载。
COPY go.mod go.sum* ./
ENV GOPROXY=https://goproxy.cn,direct

# 复制源码。
COPY . .

# go mod tidy 自动补全 go.sum 及 indirect 依赖
RUN go mod tidy

# 静态编译，禁用 cgo 以适配 alpine 运行时。
# 启用 milvus_enabled build tag，链接真实 Milvus Go SDK（github.com/milvus-io/milvus-sdk-go/v2），
# 而非内存 Mock 实现。连接失败时由 newMilvusStoreOrFallback 自动回退到 Mock。
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -tags milvus_enabled -trimpath -ldflags="-s -w" -o /out/vector-engine .

# ============ 运行阶段 ============
FROM ${BASE_REGISTRY}/alpine:3.19 AS runtime

# 安装最小运行时依赖。
RUN apk add --no-cache ca-certificates tzdata curl

# 创建非 root 用户。
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=builder /out/vector-engine /app/vector-engine

RUN chown -R app:app /app

USER app

EXPOSE 8086

# 健康检查（容器层）。
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8086/health || exit 1

ENTRYPOINT ["/app/vector-engine"]