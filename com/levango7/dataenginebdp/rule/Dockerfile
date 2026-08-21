# ==================================================================================================
# rule 模块 Dockerfile — 规则库（库模块，无 @SpringBootApplication，无 main 主类）
#
# 多阶段构建：
#   Stage 1 (builder): Gradle 8.5 + JDK 17 编译普通 jar（jar 任务，非 bootJar）
#   Stage 2 (runtime): JRE 17 Alpine 精简运行时镜像
#
# 本模块为纯库模块（无 @SpringBootApplication、无 main 主类），使用 gradle :rule:jar 构建普通 jar。
# 容器化目的：产出可分发 jar 镜像，供 ruleengine 及其他模块在运行时引用。
# 容器启动后保持存活，便于在编排系统中作为依赖服务占位与 jar 分发载体。
#
# 构建上下文须为项目根目录
# ==================================================================================================

# ---------- Stage 1: 构建 ----------
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app

COPY settings.gradle build.gradle gradle/ ./
COPY com/ ./com/

# 构建普通 jar（非 bootJar），跳过测试
RUN gradle :rule:jar -x test --no-daemon

# ---------- Stage 2: 运行时 ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=builder /app/build/rule/libs/rule.jar app.jar

# 规则库模块 — 无独立主类，作为依赖 jar 供 ruleengine 引用。
# 容器保持存活以便编排系统管理；jar 位于 /app/app.jar。
ENTRYPOINT ["sh", "-c", "echo 'rule 库模块已就绪，jar 位于 /app/app.jar，供 ruleengine 等模块引用' && tail -f /dev/null"]