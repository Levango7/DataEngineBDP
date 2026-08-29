# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io/library

# ---------- Stage 1: Build ----------
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /workspace

# 先复制 pom.xml，利用层缓存加速依赖下载
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N
# 安装内部依赖库 common-security
COPY platform/common-security/pom.xml ./platform/common-security/pom.xml
COPY platform/common-security/src ./platform/common-security/src
RUN mvn -B -q -f platform/common-security/pom.xml install -DskipTests
# 安装内部依赖库 sql-gateway（lineage-analyzer 复用其 SQL 解析器）
COPY platform/sql-gateway/pom.xml ./platform/sql-gateway/pom.xml
COPY platform/sql-gateway/src ./platform/sql-gateway/src
RUN mvn -B -q -f platform/sql-gateway/pom.xml install -DskipTests
COPY platform/governance/lineage-analyzer/pom.xml ./
RUN mvn -B -q dependency:go-offline -f pom.xml

# 复制源码并构建（跳过测试以加速镜像构建）
COPY platform/governance/lineage-analyzer/src ./src
RUN mvn -B -q clean package -f pom.xml -DskipTests

# ---------- Stage 2: Runtime ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:17-jre-alpine AS runtime

LABEL org.opencontainers.image.title="lineage-analyzer" \
      org.opencontainers.image.description="数据引擎大数据平台 - 数据血缘分析引擎" \
      org.opencontainers.image.source="https://github.com/Levango7/DataEngineBDP"

# 创建非 root 用户 (alpine busybox 语法)
RUN addgroup -S -g 1001 appgroup \
    && adduser -S -u 1001 -G appgroup -h /app appuser

WORKDIR /app

# 复制构建产物
COPY --from=builder /workspace/target/lineage-analyzer-*.jar /app/lineage-analyzer.jar

# 创建数据目录并赋权
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 切换非 root 用户
USER appuser

EXPOSE 8086

# JVM 参数：容器感知，限制堆内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 CMD wget --spider -q http://localhost:8086/actuator/health

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/lineage-analyzer.jar"]
