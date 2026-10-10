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

# 内部依赖：common-security + encaps-layer（encaps-data 依赖后者的 jar；
# 本模块与 encaps-layer 同根包 com.levango7.dataenginebdp.encaps，运行期会组件扫描到它）
COPY platform/common-security/pom.xml ./platform/common-security/pom.xml
COPY platform/common-security/src ./platform/common-security/src
RUN mvn -B -q -f platform/common-security/pom.xml install -DskipTests
COPY platform/encaps-layer/pom.xml ./platform/encaps-layer/pom.xml
COPY platform/encaps-layer/src ./platform/encaps-layer/src
RUN mvn -B -q -f platform/encaps-layer/pom.xml install -DskipTests

# 复制模块 pom 与源码并构建（跳过测试以加速镜像构建）
COPY platform/encaps-data/pom.xml ./
RUN mvn -B -q dependency:go-offline -f pom.xml
COPY platform/encaps-data/src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Stage 2: Runtime ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:17-jre AS runtime
WORKDIR /app

RUN groupadd --system --gid 1001 appgroup \
    && useradd --system --uid 1001 --gid 1001 --home /app appuser

COPY --from=builder /workspace/target/encaps-data-0.1.0-SNAPSHOT-exec.jar /app/app.jar

# /app/data：H2 文件库（application.yml 默认 ./data/encaps-data-db）；/app/logs：日志目录
RUN mkdir -p /app/data /app/logs && chown -R appuser:appgroup /app

EXPOSE 8083

USER appuser

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
