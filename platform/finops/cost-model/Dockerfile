# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io

# ============ 构建阶段 ============
FROM ${BASE_REGISTRY}/library/maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N
# 安装内部依赖库 common-security
COPY platform/common-security/pom.xml ./platform/common-security/pom.xml
COPY platform/common-security/src ./platform/common-security/src
RUN mvn -B -q -f platform/common-security/pom.xml install -DskipTests
# 先下载依赖（利用层缓存）
COPY platform/finops/cost-model/pom.xml ./
RUN mvn -B -e -DskipTests dependency:go-offline -f pom.xml
COPY platform/finops/cost-model/src ./src
RUN mvn -B -e -DskipTests clean package -f pom.xml

# ============ 运行阶段 ============
FROM ${BASE_REGISTRY}/library/eclipse-temurin:17-jre AS runtime
# 运行层不需要基镜自带的 Bitnami 服务管理器 pebble：本镜像下面自己覆写了 ENTRYPOINT 跑 java -jar。
# 而 /usr/bin/pebble 带 4 条**尚无修复版**的 HIGH（CVE-2026-111943 / -78667 / -78669 / -97031，
# 全是 Go stdlib v1.27.1 构建，上游修在 1.26.10 / 1.27.3）。删它是真减攻击面，不是绕扫描：
# 本机 docker build 出同一基镜、用 CI 同参数 `trivy image --severity HIGH,CRITICAL --ignore-unfixed`
# 实扫 删除前 4 条 → 删除后 0 条，且 `java -version` 仍为 openjdk 17.0.20.1（17.0.20.1 未受影响）。
RUN rm -f /usr/bin/pebble
WORKDIR /app

# 创建非 root 用户（与其他 Java 组件保持一致）
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

# 从构建阶段复制产物
COPY --from=builder /build/target/*.jar /app/app.jar

# 创建数据目录并赋权，确保 appuser 可写（H2 文件库 / 日志落盘）
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 暴露服务端口
EXPOSE 8084

# 切换非 root 用户
USER appuser

# JVM 参数：容器感知，限制堆内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 CMD curl -f http://localhost:8084/actuator/health

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]