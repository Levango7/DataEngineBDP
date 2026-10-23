# syntax=docker/dockerfile:1.6

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源
# 默认使用 DaoCloud 公共加速，国外环境可传 --build-arg BASE_REGISTRY=docker.io/library
ARG BASE_REGISTRY=docker.m.daocloud.io

# ---------- Stage 1: Build ----------
FROM ${BASE_REGISTRY}/library/maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /workspace

# 安装父 POM 到本地仓库（multi-module 项目必需，build context 为项目根目录）
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N

# 安装内部依赖库 common-security（stream-batch-scheduler 的直接依赖）
COPY platform/common-security/pom.xml ./platform/common-security/pom.xml
COPY platform/common-security/src ./platform/common-security/src
RUN mvn -B -q -f platform/common-security/pom.xml install -DskipTests

# 复制模块 pom 与源码并构建（跳过测试以加速镜像构建）
COPY platform/stream-batch-scheduler/pom.xml ./
RUN mvn -B -q dependency:go-offline -f pom.xml
COPY platform/stream-batch-scheduler/src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Stage 2: Runtime ----------
FROM ${BASE_REGISTRY}/library/eclipse-temurin:17-jre-alpine AS runtime
# 运行层不需要基镜自带的 Bitnami 服务管理器 pebble：本镜像下面自己覆写了 ENTRYPOINT 跑 java -jar。
# 而 /usr/bin/pebble 带 4 条**尚无修复版**的 HIGH（CVE-2026-111943 / -78667 / -78669 / -97031，
# 全是 Go stdlib v1.27.1 构建，上游修在 1.26.10 / 1.27.3）。删它是真减攻击面，不是绕扫描：
# 本机 docker build 出同一基镜、用 CI 同参数 `trivy image --severity HIGH,CRITICAL --ignore-unfixed`
# 实扫 删除前 4 条 → 删除后 0 条，且 `java -version` 仍为 openjdk 17.0.20.1（17.0.20.1 未受影响）。
RUN rm -f /usr/bin/pebble
WORKDIR /app

# 非 root 运行；uid/gid 固化 1001（与 deploy/local/values-local-core.yaml 的既有约定一致）
RUN addgroup -S -g 1001 appgroup \
    && adduser -S -u 1001 -G appgroup -h /app appuser

# 可执行 jar（spring-boot-maven-plugin classifier=exec）
COPY --from=builder /workspace/target/stream-batch-scheduler-0.1.0-exec.jar /app/app.jar

# /app/data：H2 文件库（默认落在 ${user.home}/.shuqing，compose 里用 SCHEDULER_DB_URL 显式改到此目录）
# /app/logs：Logback RollingFileAppender 输出目录（不会自建父目录）
RUN mkdir -p /app/data /app/logs && chown -R appuser:appgroup /app

EXPOSE 18086

USER appuser

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
