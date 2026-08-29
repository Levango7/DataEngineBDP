# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io/library

# ============ 构建阶段 ============
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N
# 先下载依赖（利用层缓存）
COPY platform/finops/cost-model/pom.xml ./
RUN mvn -B -e -DskipTests dependency:go-offline -f pom.xml
COPY platform/finops/cost-model/src ./src
RUN mvn -B -e -DskipTests clean package -f pom.xml

# ============ 运行阶段 ============
FROM ${BASE_REGISTRY}/eclipse-temurin:17-jre AS runtime
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