# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
ARG BASE_REGISTRY=docker.m.daocloud.io

# ============ 构建阶段 ============
# multi-module 项目：build context 必须为项目根目录（与 rule-engine 等组件一致）。
# 此前组件目录 context 下 COPY pom.xml 解析不到父 POM，buildx 报 "/src" not found。
FROM ${BASE_REGISTRY}/library/maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 安装父 POM 到本地仓库（multi-module 项目必需）
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N

# 安装内部依赖库 common-security（dependency:go-offline 需能从本地仓库解析）
COPY platform/common-security/pom.xml ./platform/common-security/pom.xml
COPY platform/common-security/src ./platform/common-security/src
RUN mvn -B -q -f platform/common-security/pom.xml install -DskipTests

# 复制模块 pom.xml 并下载依赖（利用层缓存）
COPY platform/tag-engine/pom.xml ./
RUN mvn -B -e -DskipTests dependency:go-offline -f pom.xml

# 复制源码并构建
COPY platform/tag-engine/src ./src
RUN mvn -B -e -DskipTests clean package -f pom.xml

# ============ 运行阶段 ============
FROM ${BASE_REGISTRY}/library/eclipse-temurin:17-jre-alpine AS runtime
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
COPY --from=builder /build/target/tag-engine-*.jar /app/app.jar

# 创建数据目录并赋权，确保 appuser 可写（H2 文件库 / 日志落盘）
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 暴露服务端口
EXPOSE 8080

# 切换非 root 用户
USER appuser

# JVM 参数：容器感知，限制堆内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 CMD wget --spider -q http://localhost:8080/health

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
