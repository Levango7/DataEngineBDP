# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io/library

# =============================================================================
# 阶段 1: 构建
# =============================================================================
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 安装父 POM 到本地仓库（multi-module 项目必需，build context 为项目根目录）
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N

# 先复制模块 pom.xml 利用层缓存加速依赖下载
COPY platform/sql-gateway/pom.xml ./
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q dependency:go-offline -f pom.xml

# 复制源码并构建
COPY platform/sql-gateway/src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q clean package -DskipTests -f pom.xml

# =============================================================================
# 阶段 2: 运行
# =============================================================================
FROM ${BASE_REGISTRY}/eclipse-temurin:17-jre AS runtime

LABEL org.opencontainers.image.title="sql-gateway" \
      org.opencontainers.image.description="数据引擎大数据平台 - 统一SQL网关" \
      org.opencontainers.image.source="https://github.com/shuqing-bigdata/DataEngineBDP"

# 创建非 root 用户
RUN groupadd --system --gid 1001 appgroup \
    && useradd  --system --uid 1001 --gid appgroup --home /app appuser

WORKDIR /app

# 复制构建产物
COPY --from=builder /build/target/sql-gateway-*.jar /app/sql-gateway.jar

# 创建数据目录并赋权，确保 appuser 可写（H2 文件库 / 日志落盘）
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 切换非 root 用户
USER appuser

EXPOSE 8081

# JVM 参数：容器感知，限制堆内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/sql-gateway.jar"]