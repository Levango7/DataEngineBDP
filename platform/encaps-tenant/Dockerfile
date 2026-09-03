# syntax=docker/dockerfile:1.6

# 封装层租户域服务（encaps-tenant）
# 依赖链：common-security → encaps-layer → encaps-tenant（多模块 reactor 构建）
# 关键配置（运行期）：APP.TENANT.CONTROLLER.ENABLED 默认 false（防 /api/v1/tenants
# 双注册——本镜像 classpath 含 encaps-layer jar，其 TenantController 会被组件扫描带入）

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io/library

# ---------- Stage 1: Build ----------
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /workspace

# 安装父 POM 到本地仓库（multi-module 项目必需，build context 为项目根目录）
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N

# 安装内部依赖库 common-security
COPY platform/common-security/pom.xml ./platform/common-security/pom.xml
COPY platform/common-security/src ./platform/common-security/src
RUN mvn -B -q -f platform/common-security/pom.xml install -DskipTests

# 安装封装层核心 encaps-layer（本模块的直接依赖，提供 common/model/repository/security 共享代码）
COPY platform/encaps-layer/pom.xml ./platform/encaps-layer/pom.xml
COPY platform/encaps-layer/src ./platform/encaps-layer/src
RUN mvn -B -q -f platform/encaps-layer/pom.xml install -DskipTests

# 复制本模块 pom 并预取依赖
COPY platform/encaps-tenant/pom.xml ./
RUN mvn -B -q dependency:go-offline -f pom.xml

# 复制源码并构建（跳过测试以加速镜像构建；全 context 启动测试由 CI mvn 阶段覆盖）
COPY platform/encaps-tenant/src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Stage 2: Runtime ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 创建非 root 用户运行应用
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

# 从构建阶段复制产物（可执行 jar 带 exec classifier）
COPY --from=builder /workspace/target/encaps-tenant-*-exec.jar /app/app.jar

# 创建数据目录并赋权，确保 appuser 可写（H2 文件库 / 日志落盘）
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 暴露服务端口
EXPOSE 8081

# 切换非 root 用户
USER appuser

# JVM 参数：容器感知，限制堆内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 CMD wget --spider -q http://localhost:8081/actuator/health

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
