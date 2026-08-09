# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io/library

# =============================================================================
# 阶段 1: 构建 sql-gateway 依赖并安装到本地 Maven 仓库
# =============================================================================
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-17 AS sql-gateway-builder

WORKDIR /build/sql-gateway

# 复制 sql-gateway 的 pom 和源码（构建上下文为 platform 目录）
COPY sql-gateway/pom.xml ./
COPY sql-gateway/src ./src

# 构建 sql-gateway 并安装到本地 Maven 仓库
RUN mvn -B -q clean install -DskipTests

# =============================================================================
# 阶段 2: 构建 lineage-analyzer（复用阶段 1 的 Maven 仓库）
# =============================================================================
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 从阶段 1 复制 Maven 本地仓库（包含已安装的 sql-gateway artifact）
COPY --from=sql-gateway-builder /root/.m2/repository /root/.m2/repository

# 复制 lineage-analyzer 的 pom 和源码
COPY governance/lineage-analyzer/pom.xml ./
COPY governance/lineage-analyzer/src ./src

# 构建 lineage-analyzer
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q clean package -DskipTests

# =============================================================================
# 阶段 3: 运行
# =============================================================================
FROM ${BASE_REGISTRY}/eclipse-temurin:17-jre-alpine AS runtime

LABEL org.opencontainers.image.title="lineage-analyzer" \
      org.opencontainers.image.description="数据引擎大数据平台 - 数据血缘分析引擎" \
      org.opencontainers.image.source="https://github.com/shuqing-bigdata/DataEngineBDP"

# 创建非 root 用户 (alpine busybox 语法)
RUN addgroup -S -g 1001 appgroup \
    && adduser -S -u 1001 -G appgroup -h /app appuser

WORKDIR /app

# 复制构建产物
COPY --from=builder /build/target/lineage-analyzer-*.jar /app/lineage-analyzer.jar

# 创建数据目录并赋权
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 切换非 root 用户
USER appuser

EXPOSE 8086

# JVM 参数：容器感知，限制堆内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/lineage-analyzer.jar"]
