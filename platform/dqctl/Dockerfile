# syntax=docker/dockerfile:1
# 多阶段构建：构建阶段
FROM golang:1.21-alpine AS builder

WORKDIR /build

# 先拷贝 go.mod 以利用 Docker 层缓存加速依赖下载。
COPY go.mod ./
RUN go mod download

# 拷贝源码并构建静态二进制。
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o dqctl .

# 运行阶段：最小化镜像
FROM alpine:3.19

WORKDIR /app

# 从构建阶段拷贝二进制。
COPY --from=builder /build/dqctl /usr/local/bin/dqctl

ENTRYPOINT ["dqctl"]