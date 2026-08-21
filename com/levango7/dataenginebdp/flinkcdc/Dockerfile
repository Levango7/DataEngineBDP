# ==================================================================================================
# flinkcdc 模块 Dockerfile — Flink CDC 数据同步框架（库模块，无 @SpringBootApplication）
#
# 多阶段构建：
#   Stage 1 (builder): Gradle 8.5 + JDK 17 编译普通 jar（jar 任务，非 bootJar）
#   Stage 2 (runtime): JRE 17 Alpine 精简运行时镜像
#
# 本模块无 @SpringBootApplication 主类，使用 gradle :flinkcdc:jar 构建普通 jar。
# 入口主类：com.shuqing.bigdata.flinkcdc.CdcFrameworkMain（Flink CDC 作业命令行入口）
#
# 注意：Flink 依赖在 build.gradle 中以 compileOnly 声明（运行时由 Flink 集群提供）。
#       容器直接运行时需通过 FLINK_LIB_DIR 环境变量或挂载方式补充 Flink 运行时依赖。
#       典型用法：将此 jar 提交至 Flink 集群 → flink run -c CdcFrameworkMain flinkcdc.jar --config xxx.yaml
#
# 构建上下文须为项目根目录
# ==================================================================================================

# ---------- Stage 1: 构建 ----------
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app

COPY settings.gradle build.gradle gradle/ ./
COPY com/ ./com/

# 构建普通 jar（非 bootJar），跳过测试
RUN gradle :flinkcdc:jar -x test --no-daemon

# ---------- Stage 2: 运行时 ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache wget

COPY --from=builder /app/build/flinkcdc/libs/flinkcdc.jar app.jar

# CDC 作业配置目录（运行时挂载）
VOLUME /app/config

# Flink CDC 作业入口 — 通过 --config 参数指定 YAML 配置文件
# 运行示例：docker run flinkcdc --config /app/config/cdc-mysql.yaml
ENTRYPOINT ["java", "-cp", "app.jar", "com.shuqing.bigdata.flinkcdc.CdcFrameworkMain"]
CMD ["--config", "/app/config/cdc.yaml"]