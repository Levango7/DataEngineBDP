# syntax=docker/dockerfile:1.6
# Flink CDC 作业提交器镜像（部署形态裁决见 docs/flink-cdc-deployment.md）
#
# 这不是常驻服务：CDC 作业长驻在 Flink 集群（checkpoint/exactly-once 由集群承担），
# 本镜像只负责「作业定义（jar）+ 提交动作（flink run）」——云原生底座下按需运行、
# 用完即退，避免常驻空转与双重资源管理。
#
# 两种调用模式（同一 artifact，配置驱动）：
#   ① 即时提交：docker run ... flink run -c ...CdcFrameworkMain jar --config /config/cdc.yaml [--async]
#   ② 定时提交：K8s CronJob 以同一镜像与同一契语调用（chart 批次交付）
#
# ⚠️ 不含物化视图管理面：materializedview/Controller 无 HTTP 服务器入口（仓内无
#    SpringApplication.run），部署完整性专项 backlog；本镜像不假装提供它。
#
# 构建：mvn 多段（父 pom + common-security 预装，同 rule-engine 口径；flink 依赖
# provided ⇒ job jar 不含 Flink，运行时由 flink 基础镜像提供）。

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
RUN mvn -B -e -DskipTests clean package -f pom.xml

# 运行层：官方 flink 客户端镜像（含 flink CLI 与 flink-clients，与 pom 的
# flink.version 1.20.0 对齐；flink 依赖为 provided，必须由本基础镜像提供运行时）
FROM ${BASE_REGISTRY}/library/flink:1.20.0-java17 AS runtime

COPY --from=builder /build/target/flink-cdc-*.jar /opt/flink-cdc/flink-cdc.jar
# YAML 作业定义模板（mysql/postgres/oracle/kafka），便于挂载覆盖前参照
COPY platform/flink-cdc/src/main/resources/cdc-*.yaml /opt/flink-cdc/templates/

# 配置挂载点：运行时 -v /config/cdc.yaml 覆盖
VOLUME ["/config"]

# 默认契语=提交作业（非服务）。FLINK_JOBMANAGER 由运行环境注入：
#   docker run -e FLINK_JOBMANAGER=flink-jm:8081 -v ./cdc.yaml:/config/cdc.yaml <image>
ENV FLINK_JOBMANAGER=flink-jobmanager:8081

# flink 1.20 官方镜像的 CLI 已接收 -m 形式的 JobManager 地址；默认同步阻塞直到
# 作业提交完成，需要「提交即返回」时在 args 末尾加 --async（CdcFrameworkMain 支持）。
CMD ["sh", "-c", "flink run -m $FLINK_JOBMANAGER -c com.levango7.dataenginebdp.flinkcdc.CdcFrameworkMain /opt/flink-cdc/flink-cdc.jar --config ${CDC_CONFIG:-/config/cdc.yaml}"]
