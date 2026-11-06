# syntax=docker/dockerfile:1.6

# 主数据管理服务（master-data，L3.6）
# 依赖链：common-security（父聚合器 dataenginebdp-parent 2.1.0-RC 的子模块，
# 无其它仓内模块依赖）。standalone Spring Boot 可执行 jar（target/master-data-0.1.0.jar）。
# 关键配置（运行期）：端口 8088（application.yml:2）；H2 文件库 ./data/master-data-db
# （application.yml:7，DB_URL 可覆盖）；Flyway 默认关闭（dev 走 H2 + ddl-auto=update，
# application.yml:26，prod profile 置 FLYWAY_ENABLED=true）。
#
# ⚠️ JWT_SECRET 为启动必填项（2026-11-06 实测）：本模块自有
# masterdata/security/SecurityConfig 的 securityFilterChain 直接注入 JwtAuthFilter bean，
# 而该 bean 仅当 app.security.jwt.secret（=环境变量 JWT_SECRET，application.yml:53，无默认值）
# 可解析且 ≥32 字节时才装配（JwtAuthFilterAutoConfiguration.JwtConfiguredCondition）。
# 缺失时启动即 UnsatisfiedDependencyException → 容器退出。
# 与 rule-engine 不同：rule-engine 无自有 SecurityConfig，靠 common-security 的条件装配可裸启。
# 部署侧必须注入 JWT_SECRET（secretRef，≥32 字节）；**不在镜像内置默认密钥**（secret 卫生）。

# 基础镜像仓库 ARG，便于在内网/国内切换镜像源（scripts/check-dockerfile-base-registry.py R1/R2）
ARG BASE_REGISTRY=docker.m.daocloud.io

# ============ 构建阶段 ============
FROM ${BASE_REGISTRY}/library/maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 安装父 POM 到本地仓库（multi-module 项目必需，build context 为仓库根目录）
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N

# 安装内部依赖库 common-security（JwtAuthFilter / TenantContext / SecurityConfig 自动装配来源）
COPY platform/common-security/pom.xml ./platform/common-security/pom.xml
COPY platform/common-security/src ./platform/common-security/src
RUN mvn -B -q -f platform/common-security/pom.xml install -DskipTests

# 复制本模块 pom 并预取依赖（利用层缓存）
COPY platform/master-data/pom.xml ./
RUN mvn -B -e -DskipTests dependency:go-offline -f pom.xml

# 复制源码并构建（跳过测试以加速镜像构建；全 context 测试由 CI mvn 阶段覆盖）
COPY platform/master-data/src ./src
RUN mvn -B -e -DskipTests clean package -f pom.xml

# ============ 运行阶段 ============
FROM ${BASE_REGISTRY}/library/eclipse-temurin:17-jre-alpine AS runtime

# brotli-libs 基座为 1.2.0-r1（CVE-2026-111497），Alpine 仓库已有修复版 1.2.1-r0；
# libexpat 的豁免记的是 2.8.5-r0→2.9.0-r0，2026-11-02 新拉基座已随上游重建到
# 2.9.0-r0，但 CI 可能复用旧层，故一并列入升级（与 rule-engine / encaps-tenant 同口径）。
RUN apk upgrade --no-cache libexpat brotli-libs
# 运行层不需要基镜自带的 Bitnami 服务管理器 pebble：本镜像下面自己覆写了 ENTRYPOINT 跑 java -jar。
# 删它是真减攻击面（Go stdlib 4 条尚无修复版的 HIGH），同 rule-engine / encaps-tenant 既有口径。
RUN rm -f /usr/bin/pebble
WORKDIR /app

# 创建非 root 用户。
# uid/gid 必须显式固化为 1001：/app/data 是镜像内目录（非卷），K8s 的 fsGroup 对它不生效，
# 若 Chart 的 runAsGroup/fsGroup 与此处 gid 不一致，进程就对 /app/data 无写权，
# H2 建库直接抛 java.nio.file.AccessDeniedException: /app/data/master-data-db.mv.db。
# 三个 Java 服务（rule-engine / encaps-tenant / 本服务）统一为 1001，
# deploy/local/values-local-core.yaml 与之对齐。
RUN addgroup -S -g 1001 appgroup \
    && adduser -S -u 1001 -G appgroup -h /app appuser

# 从构建阶段复制产物（spring-boot-maven-plugin 重打的可执行 fat jar）
COPY --from=builder /build/target/*.jar /app/app.jar

# H2 文件库落盘目录并赋权。
# 原默认 URL 为相对路径 ./data/master-data-db（application.yml:7），在 WORKDIR=/app 下
# 解析为 /app/data/master-data-db.mv.db；下面用等价的绝对形式覆盖，
# 既保持默认行为不变，又让部署方可经 DB_URL 一键改库位置（PostgreSQL 生产切换同走此变量）。
# 本服务无 RollingFileAppender（resources 下无 logback-spring.xml），故不需要 /app/logs。
ENV DB_URL="jdbc:h2:file:/app/data/master-data-db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# 暴露服务端口（application.yml:2 server.port=${MASTER_DATA_PORT:8088}）
EXPOSE 8088

# 切换非 root 用户
USER appuser

# JVM 参数：容器感知，限制堆内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# 健康检查：/actuator/health 由 common-security SecurityConfig permitAll 放行（无需鉴权），
# 基座 alpine 无 curl，用自带 wget --spider（application.yml:35 暴露 health）。
HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 CMD wget --spider -q http://localhost:8088/actuator/health

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
