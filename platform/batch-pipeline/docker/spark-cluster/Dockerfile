# ── batch-pipeline Spark Standalone Cluster ─────────────────────
# Base: Eclipse Temurin JRE 17（Master/Worker 运行时仅需 JRE）
# Spark: 4.2.1（清华镜像加速） + Hadoop-AWS 3.5.0 + AWS SDK v2 Bundle
# Python3 + PySpark（Worker Python 需要）
#
# Phase 5 可选：Iceberg Spark extensions（用 ARG ENABLE_ICEBERG=true 开启）
#   - Iceberg 官方 JAR 最高支持 Spark 4.1，不支持 Spark 4.2
#   - 启用 Iceberg 时需把 SPARK_VERSION 改为 4.1.0 + ICEBERG_VERSION=1.11.0
#   - JAR: iceberg-spark-runtime-4.1_2.13-1.11.0.jar + iceberg-aws-bundle-1.11.0.jar
#   - 缺省 ENABLE_ICEBERG=false，不影响现有 cluster 测试（Spark 4.2.1 保持）
#
# 供应链校验（二轮审查 H-5，任务79）：
#   - 所有外部下载（Spark tarball + 5 类 JAR）均钉扎官方校验和
#     （downloads.apache.org / archive.apache.org / repo1.maven.org）
#   - 下载后立即校验，失败即 exit 1（fail-fast，绝不产出带病镜像）
#   - 校验和不依赖下载镜像源：JAR 可从阿里云镜像下载，但校验值取自
#     repo1.maven.org 官方，防止"镜像源与下载源同时被污染"
#   - 各校验值来源与获取日期见同目录 CHECKSUMS.md
# ────────────────────────────────────────────────────────────────

# 基座钉扎 noble（Ubuntu 24.04，默认 python3=3.12）：浮动标签 17-jre 已漂移
# 到 Ubuntu 26.04（默认 python3=3.14），而项目 Driver 端 Python 钉扎 3.12
# （CI 矩阵 / 主 Dockerfile python:3.12-slim）。PySpark 要求 Driver 与
# Worker 的 Python 次版本一致（否则 PYTHON_VERSION_MISMATCH，2026-08-28
# 实测集群 Python 任务全灭），故基座必须钉扎以保持 worker python3=3.12。
# 下方 apt 安装后另有构建期断言，基座再漂移时 fail-fast。
ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/eclipse-temurin:17-jre-noble

# ── 元数据 ──────────────────────────────────────────────────────
LABEL maintainer="batch-pipeline DevOps"
LABEL description="Spark Standalone cluster node (Master/Worker)"

# ── 构建参数 ────────────────────────────────────────────────────
ARG SPARK_VERSION=4.2.1
ARG HADOOP_AWS_VERSION=3.5.0
ARG AWS_SDK_V2_BUNDLE_VERSION=2.35.4
# Spark 取数镜像清单（与 BASE_REGISTRY 同构的旋钮）：逗号分隔的**源根**，按书写顺序尝试。
# 前 SPARK_FAST_MIRRORS 个是快源（快源确定性 404 ⇒ 该发布线被快源挤出 ⇒ 预检判红），
# 其余是兜底源（archive 官方归档，慢但稳定，只在快源全挂时兜底）。
# 为什么用逗号而不是空格：Docker 的 ARG 默认值是**字面量**，带空格有被解析器截断的风险，
# 而加引号又会把引号本身带进值里（ARG 不做 shell 级去引号）；逗号在 URL 里不可能出现，
# 故逗号分隔是无歧义写法，取值时用 tr ',' ' ' 还原成列表。
# 本文件是该清单的**单一事实源**：预检脚本 scripts/check-pinned-downloads.sh 只做一致性
# 核对（清单 ↔ 本文件逐项重建比对），不另存一份副本，避免第二处漂移。
ARG SPARK_MIRRORS=https://mirrors.tuna.tsinghua.edu.cn/apache/spark,https://mirrors.aliyun.com/apache/spark,https://archive.apache.org/dist/spark
ARG SPARK_FAST_MIRRORS=2
# 取数阶段的总预算（秒）。必须小于 scripts/build-retry.sh 的 per-attempt 挂起超时
# （BASE_HANG_TIMEOUT，默认 600s）：预算用尽时由本文件**自己**失败并给出每源状态与耗时，
# 而不是被外层 SIGKILL 成 `context canceled`（台账 #112 的实测形态：挂起 600s × 2 次
# attempt，把 `Docker Build Verify` 顶到 job timeout，红在面板上却长得像编译失败）。
# 取 420s（而非更大的值）：留 180s 给非取数段（基座拉取 / apt / 校验解包 / Maven）。
# 本轮 PR #805 的 CI 实测（`Docker Build Verify` job 132626373067，head 9d76e1ee）：spark-cluster
# 单镜像 **224s**，其中取数 RUN **58.30s**（下载 53.62s，源 1/3 = tuna 命中），非取数段 ≈ **166s**；
# 44 个 Dockerfile 全量 **2163s（36m03s）**，全部「首次尝试，重试 0 次」。
# ⇒ 取数预算 420s 远大于实测成功形态的 58.30s；且「预算耗尽 420s + 非取数段 166s」≈ 586s 仍低于
# per-attempt 挂起超时 600s ⇒ 极端情况下由本文件自己判红，不会被外层 SIGKILL 成 `context canceled`。
# （本行原先写的「非取数段约 275s」是按本文件旧记载与台账 #112 健康轮次**推算**的值，
# 已被上述实测取代；推算值不作为依据。）
ARG SPARK_DOWNLOAD_BUDGET_SECONDS=420
# ── 供应链校验和（钉扎官方值，版本与校验和必须同步变更） ────────
# 来源：repo1.maven.org 官方 .sha1（获取日期与 URL 见 CHECKSUMS.md）
# 若升级版本 ARG 而忘记更新校验和 ARG，构建会在校验步骤 fail-fast
ARG HADOOP_AWS_SHA1=9e594525d264c0db653c7f68da98b245f7d61ea5
ARG AWS_SDK_V2_BUNDLE_SHA1=7252265e3970b214708e68a8b74a8fa8c875af1e
# Phase 5: Iceberg Spark extensions（可选，缺省关闭）
# 启用方式：docker build --build-arg ENABLE_ICEBERG=true --build-arg SPARK_VERSION=4.1.0 .
ARG ENABLE_ICEBERG=false
ARG ICEBERG_VERSION=1.11.0
ARG ICEBERG_SPARK_MAJOR=4.1
ARG ICEBERG_SCALA_VERSION=2.13
# Iceberg JAR 官方 SHA1（对应 ICEBERG_VERSION=1.11.0 + 4.1_2.13 缺省组合）
ARG ICEBERG_SPARK_RUNTIME_SHA1=f9b1e4a18797c58ae406104ef5c75d1b64a373c5
ARG ICEBERG_AWS_BUNDLE_SHA1=afe5b22dd5bc2d2c0639049b06a92dae2545cac7

# ── 系统依赖：curl + tini + python3 + pip ──────────────────────
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl ca-certificates tini procps python3 python3-pip python3-venv socat && \
    rm -rf /var/lib/apt/lists/*

# ── 构建期防护：worker python3 必须是 3.12（与 Driver 端 Python 对齐） ──
# PySpark 要求 Driver/Worker Python 次版本一致，否则 worker 端抛
# PYTHON_VERSION_MISMATCH 拒绝执行任何 Python 任务。基座钉扎 noble 时
# 默认 python3=3.12；若未来基座升级再次漂移（如 26.04 → 3.14），
# 此处立即终止构建，绝不产出带病镜像（与供应链校验同一 fail-fast 原则）。
RUN python3 --version | grep -q "Python 3\.12\." || { \
        echo ">>> [FATAL] worker python3 不是 3.12（实际 $(python3 --version)），将与 Driver 端 Python 错配！"; \
        exit 1; \
    }

# ── Spark 下载（含版本回退 + 国内镜像） ─────────────────────────
# 优先 ${SPARK_VERSION}（ARG，缺省 4.2.1），若不可用则回退 3.5.9
# 2026-11-03 由 4.2.0/3.5.5 抬到 4.2.1/3.5.9：Apache 快源目录按「当前发布线」滚动，
# 4.2.0 与 3.5.5 已被挤出（tuna / aliyun / downloads.apache.org 三条实测 404），
# 只剩 archive.apache.org 有（实测 530MB / 382MB）⇒ 下载被拖到挂起预算之外，
# build-retry 按传输类重试后仍失败（不是代码问题，也不是可无限重跑的抖动）。
# 抬版后回到快源：tuna 实测 529MB / 36 秒，sha512 与 downloads.apache.org 官方值逐字节一致。
# 使用清华镜像加速，回退到 Apache 官方
# 2026-11-15（台账 #112 第 ⑥ 项待裁加固，本轮落地）：取数改成「预算感知 + 源清单旋钮」。
#   ① 源清单从写死的三个 URL 提到 ARG SPARK_MIRRORS（本文件是单一事实源），
#      前 SPARK_FAST_MIRRORS 个是快源，其余是兜底源；预检脚本据此做一致性核对。
#   ② 取数阶段加总预算 ARG SPARK_DOWNLOAD_BUDGET_SECONDS（缺省 420s），
#      按「剩余预算 ÷ 剩余尝试数」切片给每个源；预算用尽 ⇒ 本文件自己失败并
#      打印每源状态与耗时，而不是被外层 SIGKILL 成 `context canceled`。
#   ③ 失败文案不回显 curl 的 stderr（理由见下方 try_version 的注释）。
# 注意：ENV 引用 ARG 必须在 ARG 声明之后（已在 line 20 声明）
ENV SPARK_VERSION_PRIMARY=${SPARK_VERSION}
ENV SPARK_VERSION_FALLBACK=3.5.9

RUN set -eux; \
    # ── 取数预算（秒）：覆盖本 RUN 的全部取数尝试（主版本 + 回退版本） ──
    TOTAL_BUDGET="${SPARK_DOWNLOAD_BUDGET_SECONDS}"; \
    START_TS="$(date +%s)"; \
    elapsed() { echo $(( $(date +%s) - START_TS )); }; \
    budget_left() { echo $(( TOTAL_BUDGET - $(elapsed) )); }; \
    \
    # ── 源根清单来自 ARG SPARK_MIRRORS（逗号分隔；本文件是该清单的单一事实源） ──
    ROOTS="$(printf '%s' "${SPARK_MIRRORS}" | tr ',' ' ')"; \
    ROOTS_TOTAL=0; \
    for r in ${ROOTS}; do ROOTS_TOTAL=$((ROOTS_TOTAL + 1)); done; \
    if [ "${ROOTS_TOTAL}" -lt 2 ]; then \
        echo ">>> [FATAL] SPARK_MIRRORS 只解析出 ${ROOTS_TOTAL} 个源根（至少需要 1 个快源 + 1 个兜底源）"; \
        echo ">>> [FATAL] 原始值：${SPARK_MIRRORS}"; \
        exit 1; \
    fi; \
    echo ">>> 取数源清单（${ROOTS_TOTAL} 个，前 ${SPARK_FAST_MIRRORS} 个为快源）：${ROOTS}"; \
    echo ">>> 取数总预算 ${TOTAL_BUDGET}s（小于 build-retry 的 per-attempt 挂起超时 ⇒ 预算内失败由本文件自己报）"; \
    \
    DOWNLOADED=0; \
    SPARK_VER=""; \
    SPARK_TGZ=""; \
    \
    # 单个版本的取数：按源清单顺序逐个尝试；每次尝试的上限 = 剩余预算 ÷ 剩余尝试数。 \
    # curl 的 stderr **刻意不回显**：scripts/build-retry.sh 用 grep 扫整份构建日志， \
    # 命中它的传输类签名就会换 BASE_REGISTRY 把整轮构建重跑一遍；而换 base 镜像源对 \
    # Spark 压缩包的取数毫无帮助（换的是基座，拉的还是同一个镜像站），纯属白烧 600s。 \
    # 这里改为只回显 curl 退出码 + 本地映射的短描述：诊断信息留得下， \
    # 又不会在日志里误命中传输类签名。 \
    try_version() { \
        tv_ver="$1"; \
        tv_tgz="spark-${tv_ver}-bin-hadoop3.tgz"; \
        tv_sources_left="${ROOTS_TOTAL}"; \
        tv_idx=0; \
        for tv_root in ${ROOTS}; do \
            tv_idx=$((tv_idx + 1)); \
            tv_left="$(budget_left)"; \
            if [ "${tv_left}" -le 5 ]; then \
                echo ">>> [BUDGET] 剩余 ${tv_left}s 不足，跳过源${tv_idx}（${tv_root}）"; \
                continue; \
            fi; \
            tv_slice=$(( tv_left / tv_sources_left )); \
            [ "${tv_slice}" -ge 5 ] || tv_slice=5; \
            tv_url="${tv_root}/spark-${tv_ver}/${tv_tgz}"; \
            echo ">>> [attempt] Spark ${tv_ver} 源${tv_idx}/${ROOTS_TOTAL}：${tv_url}（本次上限 ${tv_slice}s，预算余 ${tv_left}s）"; \
            if curl -fsSL --retry 2 --retry-delay 3 --max-time "${tv_slice}" -o "/tmp/${tv_tgz}" "${tv_url}" 2>/dev/null; then \
                DOWNLOADED=1; SPARK_VER="${tv_ver}"; SPARK_TGZ="${tv_tgz}"; \
                echo ">>> [ok] Spark ${tv_ver} 取数成功（${tv_url}）"; \
                return 0; \
            else \
                tv_rc=$?; \
                case "${tv_rc}" in \
                    6)  tv_why="域名解析失败" ;; \
                    7)  tv_why="连接被拒" ;; \
                    22) tv_why="HTTP 错误状态（4xx/5xx）" ;; \
                    28) tv_why="超时（未在本次上限内取完）" ;; \
                    35) tv_why="TLS 握手异常" ;; \
                    52) tv_why="服务端未回内容" ;; \
                    56) tv_why="接收中断" ;; \
                    *)  tv_why="其它原因" ;; \
                esac; \
                echo ">>> [miss] 源${tv_idx} 取数失败（curl 退出码 ${tv_rc}：${tv_why}；累计已用 $(elapsed)s，预算余 $(budget_left)s）"; \
            fi; \
            tv_sources_left=$((tv_sources_left - 1)); \
        done; \
        return 1; \
    }; \
    \
    # 主版本优先；主版本全灭且预算还够，才试回退版本（预算不够就明说并早失败） \
    if ! try_version "${SPARK_VERSION_PRIMARY}"; then \
        echo ">>> 主版本 ${SPARK_VERSION_PRIMARY} 的 ${ROOTS_TOTAL} 个源均未取到（累计已用 $(elapsed)s，预算余 $(budget_left)s）"; \
        if [ "$(budget_left)" -lt 30 ]; then \
            echo ">>> [BUDGET] 预算余 $(budget_left)s 不足 30s，跳过回退版本 ${SPARK_VERSION_FALLBACK}（宁可早失败，也不半途被外层挂起预算杀掉）"; \
            echo ">>> [FATAL] 取数阶段预算耗尽且未取到压缩包（判定：取数面不可用，不是构建内容坏了）"; \
            exit 1; \
        elif ! try_version "${SPARK_VERSION_FALLBACK}"; then \
            echo ">>> [FATAL] 全部源 × 两个候选版本（${SPARK_VERSION_PRIMARY} / ${SPARK_VERSION_FALLBACK}）都未取到压缩包"; \
            echo ">>> [FATAL] 判定：取数面不可用（不是构建内容坏了）⇒ 按台账 112 的处置指引处理，不要靠重跑解决"; \
            exit 1; \
        fi; \
    fi; \
    \
    # ── 供应链校验（任务79）：版本 → 官方钉扎 SHA512 ─────────────
    # 无论实际下载自哪个镜像源，一律对照官方钉扎值校验，失败即 exit 1。
    # 校验和来源（获取日期 2026-08-27，详见 CHECKSUMS.md）：
    #   4.2.1 ← https://downloads.apache.org/spark/spark-4.2.1/spark-4.2.1-bin-hadoop3.tgz.sha512（2026-11-03 取，且与 tuna 实下载值核对一致）
    #   4.2.0 ← https://downloads.apache.org/spark/spark-4.2.0/spark-4.2.0-bin-hadoop3.tgz.sha512
    #   4.1.0 ← https://archive.apache.org/dist/spark/spark-4.1.0/spark-4.1.0-bin-hadoop3.tgz.sha512
    #   3.5.9 ← https://archive.apache.org/dist/spark/spark-3.5.9/spark-3.5.9-bin-hadoop3.tgz.sha512（2026-11-03 取）
    #   3.5.5 ← https://archive.apache.org/dist/spark/spark-3.5.5/spark-3.5.5-bin-hadoop3.tgz.sha512
    # 未在清单中的版本（如升级 SPARK_VERSION 忘更新）直接拒绝构建。
    case "${SPARK_VER}" in \
        4.2.1) SPARK_TGZ_SHA512="2f76b113ebb2f3677766e38b6dc78f27756c5ae5a7c103f1a76dac523dba8e5f82077b7f480360391c0cceee79ad712d7843f23389fbfeb18bc018fb85e27866" ;; \
        4.2.0) SPARK_TGZ_SHA512="3a6559e8546ff387db8fe7a04b8fe4008853b467b972c2e343df8e14a81450534777719fc5d4998dd96519a86fdf9de140cee753c61e2e94db044d5f9555ddc4" ;; \
        4.1.0) SPARK_TGZ_SHA512="fff7f929d98779b096a2d2395b1b9db1ce277660f3852dd45e1457c373013f2669074315252181a3cea291d8f3a726c70f7f9b247e723edbf8080f40888edde1" ;; \
        3.5.9) SPARK_TGZ_SHA512="66097faf48b12f8673dc13256a44d731c27c86a5831cd8ca930c6fd9dd9bdf31d40593095e959461351d55da1a8e3ad5ed95e0b4b51bea87ca3989ab002ea8ce" ;; \
        3.5.5) SPARK_TGZ_SHA512="ec5ff678136b1ff981e396d1f7b5dfbf399439c5cb853917e8c954723194857607494a89b7e205fce988ec48b1590b5caeae3b18e1b5db1370c0522b256ff376" ;; \
        *) \
            echo ">>> [FATAL] Spark ${SPARK_VER} 无钉扎 SHA512，供应链校验不可跳过！"; \
            echo ">>> 请从 downloads.apache.org / archive.apache.org 获取该版本官方 .sha512 并更新本文件"; \
            exit 1; \
            ;; \
    esac; \
    echo ">>> 校验 Spark ${SPARK_VER} SHA512 ..."; \
    echo "${SPARK_TGZ_SHA512}  /tmp/${SPARK_TGZ}" | sha512sum -c - || { \
        echo ">>> [FATAL] Spark ${SPARK_VER} SHA512 校验失败（文件可能被篡改）！"; \
        exit 1; \
    }; \
    \
    tar -xzf /tmp/${SPARK_TGZ} -C /opt/ && \
    ln -s /opt/spark-${SPARK_VER}-bin-hadoop3 /opt/spark && \
    rm -f /tmp/${SPARK_TGZ} && \
    echo ">>> Spark ${SPARK_VER} 安装完成" && \
    echo "${SPARK_VER}" > /opt/spark/.version

ENV SPARK_HOME=/opt/spark
ENV PATH="${SPARK_HOME}/bin:${SPARK_HOME}/sbin:${PATH}"

# ── Hadoop-AWS 3.5.0 + AWS SDK v2 Bundle（构建时包含） ──────────
# hadoop-aws 3.5.0 需要 AWS SDK v2（bundle），不能用 v1 的 aws-java-sdk-bundle
# JAR 放入 /opt/spark/jars/，Spark 启动时自动加载到 classpath
# 构建时下载，不需要运行时分发（避免 530MB 传输超时/OOM）

ENV MAVEN_BASE=https://repo1.maven.org/maven2
ENV MAVEN_MIRROR=https://maven.aliyun.com/repository/central

RUN set -eux; \
    echo ">>> 下载 hadoop-aws ${HADOOP_AWS_VERSION} ..."; \
    HADOOP_AWS_JAR="hadoop-aws-${HADOOP_AWS_VERSION}.jar"; \
    DOWNLOADED=0; \
    for BASE_URL in "${MAVEN_MIRROR}" "${MAVEN_BASE}"; do \
        URL="${BASE_URL}/org/apache/hadoop/hadoop-aws/${HADOOP_AWS_VERSION}/${HADOOP_AWS_JAR}"; \
        echo ">>> 尝试 ${URL} ..."; \
        if curl -fsSL --retry 2 --retry-delay 3 --max-time 120 -o /tmp/${HADOOP_AWS_JAR} "${URL}"; then \
            DOWNLOADED=1; \
            echo ">>> hadoop-aws ${HADOOP_AWS_VERSION} 下载成功"; \
            break; \
        fi; \
    done; \
    if [ "${DOWNLOADED}" = "0" ]; then \
        echo ">>> [FATAL] hadoop-aws 下载失败！"; \
        exit 1; \
    fi; \
    # 供应链校验（任务79）：对照 repo1.maven.org 官方钉扎 SHA1，失败即 exit 1
    echo ">>> 校验 hadoop-aws SHA1 ..."; \
    echo "${HADOOP_AWS_SHA1}  /tmp/${HADOOP_AWS_JAR}" | sha1sum -c - || { \
        echo ">>> [FATAL] hadoop-aws SHA1 校验失败（文件可能被篡改）！"; \
        exit 1; \
    }; \
    cp /tmp/${HADOOP_AWS_JAR} ${SPARK_HOME}/jars/ && \
    rm -f /tmp/${HADOOP_AWS_JAR} && \
    echo ">>> hadoop-aws 安装完成"

RUN set -eux; \
    echo ">>> 下载 aws-sdk-v2-bundle ${AWS_SDK_V2_BUNDLE_VERSION} ..."; \
    AWS_SDK_JAR="bundle-${AWS_SDK_V2_BUNDLE_VERSION}.jar"; \
    DOWNLOADED=0; \
    for BASE_URL in "${MAVEN_MIRROR}" "${MAVEN_BASE}"; do \
        URL="${BASE_URL}/software/amazon/awssdk/bundle/${AWS_SDK_V2_BUNDLE_VERSION}/${AWS_SDK_JAR}"; \
        echo ">>> 尝试 ${URL} ..."; \
        if curl -fsSL --retry 2 --retry-delay 3 --max-time 600 -o /tmp/${AWS_SDK_JAR} "${URL}"; then \
            DOWNLOADED=1; \
            echo ">>> aws-sdk-v2-bundle ${AWS_SDK_V2_BUNDLE_VERSION} 下载成功"; \
            break; \
        fi; \
    done; \
    if [ "${DOWNLOADED}" = "0" ]; then \
        echo ">>> [FATAL] aws-sdk-v2-bundle 下载失败！"; \
        exit 1; \
    fi; \
    # 供应链校验（任务79）：对照 repo1.maven.org 官方钉扎 SHA1，失败即 exit 1
    echo ">>> 校验 aws-sdk-v2-bundle SHA1 ..."; \
    echo "${AWS_SDK_V2_BUNDLE_SHA1}  /tmp/${AWS_SDK_JAR}" | sha1sum -c - || { \
        echo ">>> [FATAL] aws-sdk-v2-bundle SHA1 校验失败（文件可能被篡改）！"; \
        exit 1; \
    }; \
    cp /tmp/${AWS_SDK_JAR} ${SPARK_HOME}/jars/ && \
    rm -f /tmp/${AWS_SDK_JAR} && \
    echo ">>> aws-sdk-v2-bundle 安装完成"

# ── Analytics Accelerator S3（hadoop-aws 3.5.0 传递依赖） ──────
ARG ANALYTICS_ACCELERATOR_VERSION=1.3.1
# 供应链校验：repo1.maven.org 官方 SHA1（见 CHECKSUMS.md）
ARG ANALYTICS_ACCELERATOR_SHA1=6c9bd0f6c440c9a78e82d272f5f0252d942419f6
RUN set -eux; \
    echo ">>> 下载 analyticsaccelerator-s3 ${ANALYTICS_ACCELERATOR_VERSION} ..."; \
    JAR_NAME="analyticsaccelerator-s3-${ANALYTICS_ACCELERATOR_VERSION}.jar"; \
    DOWNLOADED=0; \
    for BASE_URL in "${MAVEN_MIRROR}" "${MAVEN_BASE}"; do \
        URL="${BASE_URL}/software/amazon/s3/analyticsaccelerator/analyticsaccelerator-s3/${ANALYTICS_ACCELERATOR_VERSION}/${JAR_NAME}"; \
        echo ">>> 尝试 ${URL} ..."; \
        if curl -fsSL --retry 2 --retry-delay 3 --max-time 60 -o /tmp/${JAR_NAME} "${URL}"; then \
            DOWNLOADED=1; \
            echo ">>> analyticsaccelerator-s3 下载成功"; \
            break; \
        fi; \
    done; \
    if [ "${DOWNLOADED}" = "0" ]; then \
        echo ">>> [FATAL] analyticsaccelerator-s3 下载失败！"; \
        exit 1; \
    fi; \
    # 供应链校验（任务79）：对照 repo1.maven.org 官方钉扎 SHA1，失败即 exit 1
    echo ">>> 校验 analyticsaccelerator-s3 SHA1 ..."; \
    echo "${ANALYTICS_ACCELERATOR_SHA1}  /tmp/${JAR_NAME}" | sha1sum -c - || { \
        echo ">>> [FATAL] analyticsaccelerator-s3 SHA1 校验失败（文件可能被篡改）！"; \
        exit 1; \
    }; \
    cp /tmp/${JAR_NAME} ${SPARK_HOME}/jars/ && \
    rm -f /tmp/${JAR_NAME} && \
    echo ">>> analyticsaccelerator-s3 安装完成"

# ── PySpark 安装（Worker Python 需要） ──────────────────────────
# 容器内 python3 用于 Worker 端执行 Python 任务（UDF 等）
# 注意：移除 `|| true`，让 PySpark 安装失败立即暴露构建问题
# （此前 `|| true` 会掩盖 pip 安装失败，导致 Worker 启动后才报错）
# 版本取 /opt/spark/.version（下载阶段写入的**实际**安装版本）：
# 主版本下载失败回退 3.5.9 时，若仍按 ${SPARK_VERSION} 装 pyspark 4.2.1，
# Worker 端 Python 与集群 JVM 版本错配必然运行时报错。
RUN PYSPARK_VER=$(cat /opt/spark/.version) && \
    echo ">>> installing pyspark==${PYSPARK_VER}" && \
    python3 -m pip install --break-system-packages "pyspark==${PYSPARK_VER}" || \
    (pip3 install --break-system-packages "pyspark==${PYSPARK_VER}" || exit 1)

# ── Phase 5: Iceberg Spark extensions（可选） ──────────────────
# 当 ENABLE_ICEBERG=true 时下载 iceberg-spark-runtime + iceberg-aws-bundle JAR
# 放入 ${SPARK_HOME}/jars/，使 spark.read.table("catalog.ns.tbl") / df.writeTo(...)
# 能路由到 Iceberg 表.
#
# 关键约束：Iceberg 官方 JAR 最高支持 Spark 4.1，不支持 Spark 4.2.
# 启用 Iceberg 时必须同时把 SPARK_VERSION 改为 4.1.0（构建参数）.
# 缺省 ENABLE_ICEBERG=false，跳过此步骤，不影响现有 cluster 测试.
#
# JAR 来源：
#   iceberg-spark-runtime-${ICEBERG_SPARK_MAJOR}_${ICEBERG_SCALA_VERSION}-${ICEBERG_VERSION}.jar
#   iceberg-aws-bundle-${ICEBERG_VERSION}.jar
#
# ── 构建期防护（任务71） ────────────────────────────────────────
# 下方 ENABLE_ICEBERG 块下载的是 Iceberg 4.1 runtime JAR，必须配合 SPARK_VERSION=4.1.0
# （ARG 缺省 4.2.1），否则运行期不兼容。此前仅靠注释警告，这里改为显式断言：
# ENABLE_ICEBERG=true 且 SPARK_VERSION!=4.1.0 时立即终止构建，避免产出带病镜像。
# ARG 作用域：SPARK_VERSION / ENABLE_ICEBERG 均已在上方声明（单 stage，作用域有效）。
RUN if [ "${ENABLE_ICEBERG}" = "true" ] && [ "${SPARK_VERSION}" != "4.1.0" ]; then \
        echo ">>> [FATAL] ENABLE_ICEBERG=true requires SPARK_VERSION=4.1.0 (got ${SPARK_VERSION})." && \
        echo ">>> Pass --build-arg SPARK_VERSION=4.1.0 --build-arg ENABLE_ICEBERG=true together." && \
        exit 1; \
    fi

RUN if [ "${ENABLE_ICEBERG}" = "true" ]; then \
        set -eux; \
        echo ">>> Phase 5: 安装 Iceberg Spark extensions ${ICEBERG_VERSION} (Spark ${ICEBERG_SPARK_MAJOR} Scala ${ICEBERG_SCALA_VERSION})"; \
        ICEBERG_SPARK_JAR="iceberg-spark-runtime-${ICEBERG_SPARK_MAJOR}_${ICEBERG_SCALA_VERSION}-${ICEBERG_VERSION}.jar"; \
        DOWNLOADED=0; \
        for BASE_URL in "${MAVEN_MIRROR}" "${MAVEN_BASE}"; do \
            URL="${BASE_URL}/org/apache/iceberg/iceberg-spark-runtime-${ICEBERG_SPARK_MAJOR}_${ICEBERG_SCALA_VERSION}/${ICEBERG_VERSION}/${ICEBERG_SPARK_JAR}"; \
            echo ">>> 尝试 ${URL} ..."; \
            if curl -fsSL --retry 2 --retry-delay 3 --max-time 120 -o /tmp/${ICEBERG_SPARK_JAR} "${URL}"; then \
                DOWNLOADED=1; \
                echo ">>> iceberg-spark-runtime 下载成功"; \
                break; \
            fi; \
        done; \
        if [ "${DOWNLOADED}" = "0" ]; then \
            echo ">>> [FATAL] iceberg-spark-runtime 下载失败！"; \
            exit 1; \
        fi; \
        # 供应链校验（任务79）：对照 repo1.maven.org 官方钉扎 SHA1，失败即 exit 1
        # 注意：钉扎值对应缺省组合 ICEBERG_VERSION=1.11.0 + 4.1_2.13，
        # 若覆盖这些 ARG，必须同步更新 ICEBERG_SPARK_RUNTIME_SHA1
        echo ">>> 校验 iceberg-spark-runtime SHA1 ..."; \
        echo "${ICEBERG_SPARK_RUNTIME_SHA1}  /tmp/${ICEBERG_SPARK_JAR}" | sha1sum -c - || { \
            echo ">>> [FATAL] iceberg-spark-runtime SHA1 校验失败（文件可能被篡改）！"; \
            exit 1; \
        }; \
        cp /tmp/${ICEBERG_SPARK_JAR} ${SPARK_HOME}/jars/ && \
        rm -f /tmp/${ICEBERG_SPARK_JAR} && \
        echo ">>> iceberg-spark-runtime 安装完成"; \
        \
        ICEBERG_AWS_JAR="iceberg-aws-bundle-${ICEBERG_VERSION}.jar"; \
        DOWNLOADED=0; \
        for BASE_URL in "${MAVEN_MIRROR}" "${MAVEN_BASE}"; do \
            URL="${BASE_URL}/org/apache/iceberg/iceberg-aws-bundle/${ICEBERG_VERSION}/${ICEBERG_AWS_JAR}"; \
            echo ">>> 尝试 ${URL} ..."; \
            if curl -fsSL --retry 2 --retry-delay 3 --max-time 120 -o /tmp/${ICEBERG_AWS_JAR} "${URL}"; then \
                DOWNLOADED=1; \
                echo ">>> iceberg-aws-bundle 下载成功"; \
                break; \
            fi; \
        done; \
        if [ "${DOWNLOADED}" = "0" ]; then \
            echo ">>> [FATAL] iceberg-aws-bundle 下载失败！"; \
            exit 1; \
        fi; \
        # 供应链校验（任务79）：对照 repo1.maven.org 官方钉扎 SHA1，失败即 exit 1
        # 注意：钉扎值对应缺省 ICEBERG_VERSION=1.11.0，覆盖时须同步更新 SHA1
        echo ">>> 校验 iceberg-aws-bundle SHA1 ..."; \
        echo "${ICEBERG_AWS_BUNDLE_SHA1}  /tmp/${ICEBERG_AWS_JAR}" | sha1sum -c - || { \
            echo ">>> [FATAL] iceberg-aws-bundle SHA1 校验失败（文件可能被篡改）！"; \
            exit 1; \
        }; \
        cp /tmp/${ICEBERG_AWS_JAR} ${SPARK_HOME}/jars/ && \
        rm -f /tmp/${ICEBERG_AWS_JAR} && \
        echo ">>> iceberg-aws-bundle 安装完成"; \
        echo "${ICEBERG_VERSION}" > ${SPARK_HOME}/.iceberg_version; \
    else \
        echo ">>> Phase 5: ENABLE_ICEBERG=false，跳过 Iceberg JAR 安装（保持 Spark ${SPARK_VERSION} 兼容）"; \
    fi

# ── 非 root 用户（UID/GID 自适应，与 K8s/securityContext 约定一致） ─
# Spark Master/Worker 日志写到 ${SPARK_HOME}/logs/，需对 spark 用户可写。
# 基础镜像（Ubuntu）可能已有 UID 1000 的用户（如 ubuntu）：此时 spark 复用
# 默认 UID 而非固定 1000，避免 useradd 冲突导致构建失败。
#
# 关键修复（2026-08-26）：chown -R /opt/spark 走符号链接时，在 containerd
# snapshotter 下出现过 logs/work 子目录属主未生效（root:root）的问题——
# 运行时 spark-daemon.sh 对日志目录 touch 探测失败后尝试 chown 又因非 root
# 失败，导致 Master/Worker "failed to launch"。因此这里显式 readlink -f
# 解析真实路径后再 mkdir+chown，并以 spark 身份做写入自验证（失败即中止
# 构建，避免带病镜像）。
RUN set -eux; \
    if ! id -u spark >/dev/null 2>&1; then \
        if id -u 1000 >/dev/null 2>&1; then \
            useradd -m spark; \
        else \
            useradd -m -u 1000 spark; \
        fi; \
    fi; \
    SPARK_REAL_HOME=$(readlink -f /opt/spark); \
    echo ">>> Spark real home: ${SPARK_REAL_HOME}"; \
    mkdir -p "${SPARK_REAL_HOME}/logs" "${SPARK_REAL_HOME}/work"; \
    chown -R spark:spark "${SPARK_REAL_HOME}"; \
    chown -h spark:spark /opt/spark; \
    su -s /bin/bash spark -c "touch '${SPARK_REAL_HOME}/logs/.rwtest' && rm '${SPARK_REAL_HOME}/logs/.rwtest' && touch '${SPARK_REAL_HOME}/work/.rwtest' && rm '${SPARK_REAL_HOME}/work/.rwtest'"; \
    ls -ld "${SPARK_REAL_HOME}" "${SPARK_REAL_HOME}/logs" "${SPARK_REAL_HOME}/work"

# ── 入口脚本 ────────────────────────────────────────────────────
COPY --chown=spark:spark entrypoint.sh /opt/entrypoint.sh
RUN chmod +x /opt/entrypoint.sh

USER spark

# ── 端口 ────────────────────────────────────────────────────────
# 7077 = Spark Master RPC
# 8080 = Master Web UI
# 8081 = Worker Web UI
EXPOSE 7077 8080 8081

# ── 入口点（tini 作为 init 系统） ──────────────────────────────
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/entrypoint.sh"]
