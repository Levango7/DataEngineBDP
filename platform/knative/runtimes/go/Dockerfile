# ============================================================================
# Go 函数运行时 Dockerfile · 数据引擎大数据平台 T025
# ----------------------------------------------------------------------------
# 多阶段构建：静态编译单二进制
# 冷启动优化策略：
#   1. 静态编译：CGO_ENABLED=0，无 libc 依赖
#   2. distroless 基础镜像：极简运行时（约 2MB + 二进制）
#   3. -ldflags -s -w：去除调试符号，减小二进制体积
#   4. -trimpath：去除构建路径，可重现构建
# 目标冷启动：≤ 0.5s（Go 静态二进制，远优于 ≤ 3s 目标）
# ============================================================================
# ---------- 阶段 1：Go 编译 ----------
FROM docker.m.daocloud.io/library/golang:1.22-alpine AS builder

WORKDIR /build
COPY go.mod go.sum* /build/

# 依赖预下载（利用层缓存）
RUN go mod download

COPY . /build/

# 静态编译（CGO 禁用，去除调试符号）
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -trimpath -ldflags="-s -w" -o /function-runtime ./cmd

# ---------- 阶段 2：distroless 运行时 ----------
FROM docker.m.daocloud.io/gcr.io/distroless/static-debian12:nonroot

COPY --from=builder /function-runtime /app/function-runtime

ENV SERVER_PORT=8080 \
    LOG_LEVEL=release

EXPOSE 8080

# 静态二进制启动（无运行时依赖，启动 < 0.5s）
ENTRYPOINT ["/app/function-runtime"]