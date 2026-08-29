# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
# 默认 docker.m.daocloud.io 代理 Docker Hub 官方镜像，运行阶段基础镜像为
# docker.m.daocloud.io/eclipse-temurin:17-jre-alpine
ARG BASE_REGISTRY=docker.m.daocloud.io

# =============================================================================
# 阶段 1: 构建
# =============================================================================
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先复制 pom.xml 利用层缓存加速依赖下载
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N
# 安装内部依赖库 common-security
COPY platform/common-security/pom.xml ./platform/common-security/pom.xml
COPY platform/common-security/src ./platform/common-security/src
RUN mvn -B -q -f platform/common-security/pom.xml install -DskipTests
COPY platform/infra-provider-private/pom.xml ./
RUN mvn -B -q dependency:go-offline -f pom.xml

# 复制源码并构建
COPY platform/infra-provider-private/src ./src
RUN mvn -B -q clean package -DskipTests -f pom.xml

# =============================================================================
# 阶段 2: 运行
# =============================================================================
FROM ${BASE_REGISTRY}/eclipse-temurin:17-jre-alpine AS runtime

LABEL org.opencontainers.image.title="infra-provider-private" \
      org.opencontainers.image.description="数据引擎大数据平台 - L0.4 私有云 VM 供应 Provider（vSphere + OpenStack）" \
      org.opencontainers.image.source="https://github.com/shuqing-bigdata/DataEngineBDP"

# 创建非 root 用户（alpine 使用 addgroup/adduser）
RUN addgroup --system --gid 1001 appgroup \
    && adduser --system --uid 1001 --ingroup appgroup --home /app appuser

WORKDIR /app

# 复制构建产物
COPY --from=builder /build/target/infra-provider-private-*.jar /app/infra-provider-private.jar

# 创建数据目录并赋权，确保 appuser 可写（H2 文件库 / 日志落盘）
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 切换非 root 用户
USER appuser

EXPOSE 8084

# JVM 参数：容器感知，限制堆内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 CMD wget --spider -q http://localhost:8084/actuator/health

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/infra-provider-private.jar"]