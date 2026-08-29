# 数据引擎大数据平台 T034 - 跨集群查询路由与归并服务 Dockerfile
# ============================================================================
# 多阶段构建：
#   Stage 1: Maven 构建 fat jar
#   Stage 2: Eclipse Temurin JRE 17 运行
# ============================================================================
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml ./pom.xml
RUN mvn -B -q install -N
# 先下载依赖（利用 Docker 层缓存）
COPY platform/karmada/federated-query/pom.xml ./
RUN mvn dependency:go-offline -f pom.xml -B
COPY platform/karmada/federated-query/src ./src
RUN mvn clean package -f pom.xml -DskipTests -B

FROM eclipse-temurin:17-jre-alpine

# 创建非 root 用户（alpine 运行时使用 busybox 工具链，保持 root 执行）。
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=builder --chown=app:app /build/target/federated-query-0.1.0-exec.jar app.jar
EXPOSE 8094
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

# 切换非 root 用户运行。
USER app

HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 CMD wget --spider -q http://localhost:8094/actuator/health

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]