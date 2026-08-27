# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
# 默认使用 DaoCloud 公共加速，国外环境可传 --build-arg BASE_REGISTRY=docker.io/library
ARG BASE_REGISTRY=docker.m.daocloud.io/library

# ---------- Stage 1: Build ----------
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /workspace

# 安装父 POM 到本地仓库（multi-module 项目必需，build context 为项目根目录）
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N

# 复制模块 pom.xml 和源码
COPY platform/encaps-layer/pom.xml ./
RUN mvn -B -q dependency:go-offline -f pom.xml

# 复制源码并构建（跳过测试以加速镜像构建）
COPY platform/encaps-layer/src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Stage 2: Runtime ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 创建非 root 用户运行应用
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

# 从构建阶段复制产物
COPY --from=builder /workspace/target/encaps-layer-*.jar /app/app.jar

# 创建数据目录并赋权，确保 appuser 可写（H2 文件库 / 日志落盘）
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 暴露服务端口
EXPOSE 8080

# 切换非 root 用户
USER appuser

# JVM 参数：容器感知，限制堆内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]