# 数据引擎大数据平台 T034 - 跨集群查询路由与归并服务 Dockerfile
# ============================================================================
# 多阶段构建：
#   Stage 1: Maven 构建 fat jar
#   Stage 2: Eclipse Temurin JRE 17 运行
# ============================================================================
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
# 先下载依赖（利用 Docker 层缓存）
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/federated-query-0.1.0-exec.jar app.jar
EXPOSE 8094
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]