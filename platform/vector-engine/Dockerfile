# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io

# ============ 构建阶段 ============
FROM ${BASE_REGISTRY}/library/golang:1.26-alpine AS builder
ARG TARGETARCH

WORKDIR /build

# 先复制依赖描述文件，利用层缓存加速依赖下载。
COPY go.mod go.sum* ./
ENV GOPROXY=https://goproxy.cn,direct

# 复制源码。
COPY . .

# mod下载在 QEMU 下优化，避免 tidy 联网校验依赖。
RUN go mod download

# 静态编译，禁用 cgo 以适配 alpine 运行时。
# 启用 milvus_enabled build tag，链接真实 Milvus Go SDK（github.com/milvus-io/milvus-sdk-go/v2），
# 而非内存 Mock 实现。连接失败时由 newMilvusStoreOrFallback 自动回退到 Mock。
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build -tags milvus_enabled -trimpath -ldflags="-s -w" -o /out/vector-engine .

# ============ 运行阶段 ============
FROM ${BASE_REGISTRY}/library/alpine:3.20 AS runtime

# 基座自带 libexpat 2.8.5-r0（CVE-2026-77214）与 brotli-libs 1.2.0-r1（CVE-2026-111497），
# Alpine 仓库已有修复版 2.9.0-r0 / 1.2.1-r0。镜像门禁扫的是基座引用本身，且
# docs/trivy-image-ignore.yaml 的豁免是按 CVE 号全局生效的——出货镜像必须在这里
# 升到修复版，不能只靠豁免账（与已兜底的 8 个文件同一口径）。
RUN apk upgrade --no-cache libexpat brotli-libs

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
    CMD wget -qO- http://127.0.0.1:8086/api/v1/health || exit 1

ENTRYPOINT ["/app/vector-engine"]