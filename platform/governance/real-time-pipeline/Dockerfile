# 数据引擎大数据平台 - T036 实时治理管道 Dockerfile
# 多阶段构建：Maven 编译 → JRE 运行

# ============ Stage 1: Maven 编译 ============
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先复制 pom.xml，利用 Docker 层缓存加速依赖下载
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N
COPY platform/governance/real-time-pipeline/pom.xml ./
RUN mvn dependency:go-offline -f pom.xml -B

# 复制源码并编译
COPY platform/governance/real-time-pipeline/src ./src
RUN mvn clean package -f pom.xml -DskipTests -B

# ============ Stage 2: JRE 运行 ============
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 复制编译产物
COPY --from=builder /build/target/real-time-governance-pipeline-0.1.0.jar app.jar

# 暴露端口（与 application.yml 一致）
EXPOSE 18090

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:18090/actuator/health | grep -q '"status":"UP"' || exit 1

# 非 root 用户运行
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# JVM 参数：容器感知内存限制，开启 GC 日志
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]