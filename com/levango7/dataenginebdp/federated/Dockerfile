# ==================================================================================================
# federated 模块 Dockerfile — 联邦查询（Spring Boot 微服务，Apache Calcite）
#
# 多阶段构建：
#   Stage 1 (builder): Gradle 8.5 + JDK 17 编译并打包可执行 jar（bootJar）
#   Stage 2 (runtime): JRE 17 Alpine 精简运行时镜像
#
# 构建上下文须为项目根目录
# 健康检查端点：/api/v1/health/liveness
# ==================================================================================================

# ---------- Stage 1: 构建 ----------
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app

COPY settings.gradle build.gradle gradle/ ./
COPY com/ ./com/

RUN gradle :federated:bootJar -x test --no-daemon

# ---------- Stage 2: 运行时 ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache wget

COPY --from=builder /app/build/federated/libs/federated.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 --start-period=40s \
  CMD wget -qO- http://localhost:8080/api/v1/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]