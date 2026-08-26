# syntax=docker/dockerfile:1
# 多阶段构建：构建阶段
FROM golang:1.26-alpine AS builder
ARG TARGETARCH

WORKDIR /build

# 先拷贝 go.mod 以利用 Docker 层缓存加速依赖下载。
COPY go.mod ./
RUN go mod download

# 拷贝源码并构建静态二进制。
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH} go build -ldflags="-s -w" -o dqctl .

# 运行阶段：最小化镜像
FROM alpine:3.19

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