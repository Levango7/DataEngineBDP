# ============================================================================
# Java 函数运行时 Dockerfile · 数据引擎大数据平台 T025
# ----------------------------------------------------------------------------
# 多阶段构建：GraalVM Native Image 编译为单二进制
# 冷启动优化策略：
#   1. Native Image：AOT 编译为单二进制，启动 < 1s（无 JVM 预热）
#   2. distroless 基础镜像：极简运行时（约 20MB）
#   3. 静态链接：无运行时依赖
# 目标冷启动：≤ 1s（Native Image，远优于 ≤ 3s 目标）
# ============================================================================
# ---------- 阶段 1：Maven 编译 ----------
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build
COPY pom.xml /build/pom.xml
# 依赖预下载（利用层缓存）
RUN mvn dependency:go-offline -B

COPY src /build/src
# 编译 Native Image（耗时约 2-3 分钟，仅在构建时执行）
RUN mvn native:compile -B -DskipTests

# ---------- 阶段 2：distroless 运行时 ----------
FROM gcr.io/distroless/java17-debian12:nonroot

COPY --from=builder /build/target/function-runtime-java /app/function-runtime

ENV SERVER_PORT=8080 \
    LOG_LEVEL=INFO

EXPOSE 8080

# Native Image 单二进制启动（无 JVM，启动 < 1s）
ENTRYPOINT ["/app/function-runtime"]