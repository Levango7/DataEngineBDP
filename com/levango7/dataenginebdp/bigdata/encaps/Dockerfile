# ==================================================================================================
# bigdata-encaps 模块 Dockerfile — 大数据封装层（Spring Boot 微服务）
#
# 多阶段构建：
#   Stage 1 (builder): Gradle 8.5 + JDK 17 编译并打包可执行 jar（bootJar）
#   Stage 2 (runtime): JRE 17 Alpine 精简运行时镜像
#
# 构建上下文须为项目根目录（docker-compose 已配置 context: ../../.. ）
# 健康检查端点：/api/v1/health/liveness（重构后统一存活探针）
# ==================================================================================================

# ---------- Stage 1: 构建 ----------
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app

# 复制 Gradle 配置（利用层缓存，源码变更时不重新下载依赖）
COPY settings.gradle build.gradle gradle/ ./
# 复制全部源码树
COPY com/ ./com/

# 构建 bigdata-encaps 可执行 jar，跳过测试
RUN gradle :bigdata-encaps:bootJar -x test --no-daemon

# ---------- Stage 2: 运行时 ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 安装 wget（Alpine 默认不含），供 HEALTHCHECK 使用
RUN apk add --no-cache wget

# 从构建阶段复制可执行 jar
COPY --from=builder /app/build/bigdata-encaps/libs/bigdata-encaps.jar app.jar

# 暴露 HTTP 服务端口
EXPOSE 8080

# 健康检查 — 存活探针（30s 间隔，3s 超时，3 次重试，40s 启动宽限）
HEALTHCHECK --interval=30s --timeout=3s --retries=3 --start-period=40s \
  CMD wget -qO- http://localhost:8080/api/v1/health/liveness || exit 1

# 启动 Spring Boot 应用
ENTRYPOINT ["java", "-jar", "app.jar"]