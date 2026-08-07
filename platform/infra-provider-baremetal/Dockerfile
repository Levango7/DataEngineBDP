# syntax=docker/dockerfile:1.6
# Dockerfile for infra-provider-baremetal
# 多阶段构建: golang构建 → alpine运行
#
# 构建: docker build -t infra-provider-baremetal:0.1.0 .
# 运行: docker run -p 8080:8080 -v $(pwd)/data:/app/data infra-provider-baremetal:0.1.0

###############################################################################
# 阶段1: 构建
###############################################################################
FROM docker.m.daocloud.io/golang:1.25-alpine AS builder

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
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
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
FROM docker.m.daocloud.io/alpine:3.19 AS runtime

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