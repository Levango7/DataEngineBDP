# syntax=docker/dockerfile:1.6
# Flink CDC 作业提交器镜像（部署形态裁决见 docs/flink-cdc-deployment.md）
#
# 这不是常驻服务：CDC 作业长驻在 Flink 集群（checkpoint/exactly-once 由集群承担），
# 本镜像只负责「作业定义（jar）+ 提交动作（flink CLI）」——云原生底座下按需运行、
# 用完即退，避免常驻空转与双重资源管理。
#
# 两种调用模式（同一 artifact，配置驱动）：
#   ① 即时提交：docker run ... java -cp ... CliFrontend run -m <jm> ...（见 CMD）
#   ② 定时提交：K8s CronJob 以同一镜像与同一契语调用（chart 批次交付）
#
# ⚠️ 为什么不用 flink:1.20.0-java17 官方镜像做运行层（2026-11-06 CI 实证）：
#    该基座（Ubuntu 22.04 + 完整发行包）自带 15 条 HIGH（openssl 3.0.2
#    CVE-2026-45447、gpgv CVE-2025-68973、flink-table 1.20.0 CVE-2026-35194 等），
#    而提交器只需要 flink-clients 的 CLI——拿整包发行版当提交器 = 白背 15 条 CVE。
#    改为 JRE + 依赖复制（dependency:copy-dependencies：flink-clients 及其依赖树），
#    扫描面只剩 clients 依赖树；基座为 Alpine（musl 族，按 #116 口径无需 apt 层）。
#
# 构建：maven 多段（父 pom + common-security 预装，同 rule-engine 口径）。

ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

COPY pom.xml ./pom.xml
RUN mvn -B -q install -N

COPY platform/common-security/pom.xml ./platform/common-security/pom.xml
COPY platform/common-security/src ./platform/common-security/src
RUN mvn -B -q -f platform/common-security/pom.xml install -DskipTests

COPY platform/flink-cdc/pom.xml ./
RUN mvn -B -e -DskipTests dependency:go-offline -f pom.xml

COPY platform/flink-cdc/src ./src
RUN mvn -B -e -DskipTests clean package -f pom.xml \
    && mvn -B -e -f pom.xml dependency:copy-dependencies \
         -DincludeGroupIds=org.apache.flink \
         -DoutputDirectory=/build/target/flink-client-libs

# 运行层：JRE Alpine（musl 族无 openssl/gpgv 债务；flink 依赖由上一段按 scope 复制）
FROM ${BASE_REGISTRY}/library/eclipse-temurin:17-jre-alpine AS runtime

COPY --from=builder /build/target/flink-cdc-*.jar /opt/flink-cdc/flink-cdc.jar
COPY --from=builder /build/target/flink-client-libs/ /opt/flink-cdc/flink-client-libs/
# YAML 作业定义模板（mysql/postgres/oracle/kafka），便于挂载覆盖前参照
COPY platform/flink-cdc/src/main/resources/cdc-*.yaml /opt/flink-cdc/templates/

# 配置挂载点：运行时 -v /config/cdc.yaml 覆盖
VOLUME ["/config"]

# 非 root 运行（仓内 Dockerfile 口径）：提交器是短 CLI，无监听端口，降权后
# 仍可读挂载的 /config 与 -v 覆盖的 YAML（world-readable 即可；K8s
# CronJob 侧配 fsGroup/securityContext runAsNonRoot 同效）。
RUN addgroup -S flink && adduser -S flink -G flink

ENV FLINK_JOBMANAGER=flink-jobmanager:8081
USER flink

# 提交契语=flink run（CliFrontend 即 flink 发行版脚本的入口类），主类为
# CdcFrameworkMain；--async 由 CdcFrameworkMain 自身支持（提交后立即返回）。
# 追加参数可透传：docker run <image> <extra args>。
ENV CDC_CONFIG=/config/cdc.yaml
ENTRYPOINT ["java", "-cp", "/opt/flink-cdc/flink-cdc.jar:/opt/flink-cdc/flink-client-libs/*", "org.apache.flink.client.cli.CliFrontend"]
CMD ["run", "-m", "flink-jobmanager:8081", "-c", "com.levango7.dataenginebdp.flinkcdc.CdcFrameworkMain", "/opt/flink-cdc/flink-cdc.jar", "--config", "/config/cdc.yaml"]
