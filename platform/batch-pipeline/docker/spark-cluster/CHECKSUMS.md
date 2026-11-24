# docker/spark-cluster 供应链校验和清单（CHECKSUMS.md）

> 任务 #79（二轮审查 H-5：供应链风险）。本文件记录 `Dockerfile` 中所有外部下载文件的
> 钉扎校验和及其官方来源，供审计与版本升级时更新。
>
> 原则：
>
> - 下载源（清华/阿里云镜像）仅为加速，**校验值一律取自官方源**，
>   防止镜像源与下载源同时被污染。
> - 任一文件校验失败，构建立即 `exit 1`（fail-fast）。
> - 升级版本 ARG 时必须同步更新对应 SHA 值（Dockerfile 内 ARG / case 映射），
>   否则构建会在校验步骤失败——这是有意的 fail-safe。

获取日期：2026-08-27（通过官方 .sha512 / .sha1 端点在线获取）；4.2.1 与 3.5.9 两行获取于 2026-11-03；3.5.10 行获取于 2026-11-24

## Spark tarball（SHA512，构建期 `sha512sum -c`）

| 文件 | 版本 | 钉扎 SHA512 | 校验和来源 URL | 备注 |
|---|---|---|---|---|
| spark-4.2.1-bin-hadoop3.tgz | 4.2.1 | `2f76b113ebb2f3677766e38b6dc78f27756c5ae5a7c103f1a76dac523dba8e5f82077b7f480360391c0cceee79ad712d7843f23389fbfeb18bc018fb85e27866` | https://downloads.apache.org/spark/spark-4.2.1/spark-4.2.1-bin-hadoop3.tgz.sha512 | 2026-11-03 起的当前缺省版本；除官方 .sha512 外还用 tuna 实下载 529MB 跑 `sha512sum` 交叉核对一致 |
| spark-4.2.0-bin-hadoop3.tgz | 4.2.0 | `3a6559e8546ff387db8fe7a04b8fe4008853b467b972c2e343df8e14a81450534777719fc5d4998dd96519a86fdf9de140cee753c61e2e94db044d5f9555ddc4` | https://downloads.apache.org/spark/spark-4.2.0/spark-4.2.0-bin-hadoop3.tgz.sha512 | 历史钉扎保留（2026-11-03 起 tuna/aliyun/downloads 三条快源实测均 404，只剩 archive 有 ⇒ 不再是缺省） |
| spark-4.1.0-bin-hadoop3.tgz | 4.1.0 | `fff7f929d98779b096a2d2395b1b9db1ce277660f3852dd45e1457c373013f2669074315252181a3cea291d8f3a726c70f7f9b247e723edbf8080f40888edde1` | https://archive.apache.org/dist/spark/spark-4.1.0/spark-4.1.0-bin-hadoop3.tgz.sha512 | ENABLE_ICEBERG=true 所需；downloads.apache.org 同路径值与之相同（双源交叉验证一致） |
| spark-3.5.10-bin-hadoop3.tgz | 3.5.10 | `45b47b81e28e78ec9604f42615e9dd751f5bfad83cce7e5400d11409bd5425a86748e996496981284312c5f75697606c85c75bca7c7aa09a692b6d4594353e88` | https://downloads.apache.org/spark/spark-3.5.10/spark-3.5.10-bin-hadoop3.tgz.sha512 | 2026-11-24 起的回退版本；downloads 与 archive 官方值一致，tuna/aliyun/archive/downloads 四源 Range GET 均 206 |
| spark-3.5.9-bin-hadoop3.tgz | 3.5.9 | `66097faf48b12f8673dc13256a44d731c27c86a5831cd8ca930c6fd9dd9bdf31d40593095e959461351d55da1a8e3ad5ed95e0b4b51bea87ca3989ab002ea8ce` | https://archive.apache.org/dist/spark/spark-3.5.9/spark-3.5.9-bin-hadoop3.tgz.sha512 | 历史回退版本（2026-11-23 CI 实测 tuna/aliyun 均 404，archive 仍 206；现回退用 3.5.10） |
| spark-3.5.5-bin-hadoop3.tgz | 3.5.5 | `ec5ff678136b1ff981e396d1f7b5dfbf399439c5cb853917e8c954723194857607494a89b7e205fce988ec48b1590b5caeae3b18e1b5db1370c0522b256ff376` | https://archive.apache.org/dist/spark/spark-3.5.5/spark-3.5.5-bin-hadoop3.tgz.sha512 | 历史回退版本（快源已挤出，现回退用 3.5.10） |

下载源（2026-11-15 起由 Dockerfile 的 `ARG SPARK_MIRRORS` 承载：逗号分隔的**源根**，
按书写顺序尝试；前 `ARG SPARK_FAST_MIRRORS=2` 个是快源，其余是兜底源）：

1. https://mirrors.tuna.tsinghua.edu.cn/apache/spark（快源）
2. https://mirrors.aliyun.com/apache/spark（快源）
3. https://archive.apache.org/dist/spark（兜底源）

取数阶段有总预算 `ARG SPARK_DOWNLOAD_BUDGET_SECONDS=420`：按「剩余预算 ÷ 剩余尝试数」
切片给每个源，**单次 curl 的墙钟上限 = 切片 ÷ (1 + curl 内部重试次数)**（取数段内部重试为 0，
重试职责交给跨源冗余 ⇒ 本源实耗上限恒等于切片），预算用尽即由 Dockerfile 自己判红
（不被外层挂起预算 SIGKILL）；并带停滞检测（低于 50 KB/s 持续 30s 即放弃该源）。
2026-11-15 CI 实测（`Docker Build Verify` job 132689363767，failed）反证过旧写法的问题：
取数段 curl 带 `--retry 2` 时 curl 的 `--max-time` 是**每次尝试**的上限，切片 140s 实际烧掉
426.5s，把 420s 预算吃到 −6s，源 2/3 与回退版本全被跳过；该形态现由
`scripts/test-spark-download-budget.sh` 做成回归测试（并挂进 CI 的 `Bash Syntax Check` 作业）。
预检脚本 `scripts/check-pinned-downloads.sh` 会把本清单与 Dockerfile 的源清单逐项重建
比对，任一侧改了而另一侧没改即判红（fail-closed）。

## 回退路径的端到端演练（2026-11-24 新增，台账 #112 续）

回退分支自设计以来**从未在真实构建里跑过**（主版本一直可取）⇒「回退版本的钉扎 SHA512
是否还能过」「装出来的 Spark 是不是真是 3.5.10」都曾是未验证假设。本节登记该缺口怎么补。

- **开关**：Dockerfile 的 `ARG SPARK_FORCE_FALLBACK=0`（缺省 0 ⇒ 生产构建行为逐字不变；
  它是构建期开关，不进镜像运行时环境）。取值 1 时，取数段**跳过主版本**（一个源都不试），
  直接走回退版本这条路径。
- **演练脚本**：`scripts/test-spark-fallback-drill.sh`。它从 Dockerfile **按内容锚点截断**
  出「取数 + 供应链校验 + 解包」段（锚点 = 写入 `/opt/spark/.version` 的那一行，是那段 RUN
  的末行 ⇒ 截断点不依赖行号），用 `--build-arg SPARK_FORCE_FALLBACK=1` 构建它，
  再断言：构建退出码 0、日志含演练标记、日志**不含主版本的取数尝试痕迹**、
  日志含回退版本取数成功与安装完成、日志含钉扎 SHA512 校验通过行、
  镜像内 `/opt/spark/.version` 等于回退版本、镜像内 `/opt/spark` 软链指向回退版本目录。
- **CI 接线**：`Docker Build Verify` 作业里按收敛闸的第二个判定 `spark_drill_relevant`
  触发（Spark 面四文件：回退逻辑本体 / 本钉扎清单 / 演练脚本自身 / 回退桩测脚本）。
- **本清单与演练的关系**：演练验的就是本表里 **3.5.10 行的 SHA512**能否在真实构建里通过
  ⇒ 若日后抬回退版本，本表的 SHA512 与 Dockerfile 的 `case` 映射必须同步更新，
  否则演练会当场判红（这正是它要抓的形态）。
- **不覆盖的范围（显式声明）**：演练只到「解包 + 写版本文件」为止，
  **不装 pyspark、不装 Maven JAR、不装 Iceberg**（完整镜像实测 224s 到约 27 分钟，
  会顶穿 `Docker Build Verify` 的作业预算）。pyspark 版本取自 `/opt/spark/.version`
  （Dockerfile 的 `PYSPARK_VER="$(cat /opt/spark/.version)"`）⇒ 那份文件正是演练断言的对象，
  「回退后 pyspark 跟着降」由此闭合，但 pyspark 安装段本身仍只有桩测覆盖。
- **已知盲区（必须记住）**：本机到 Docker Hub 全域不可达 ⇒ 该演练的**真 docker build
  只能在 GitHub Actions runner 上执行**，本机只能跑 `--self-test` 与 `--dry-run`。

## Maven Central JAR（SHA1，构建期 `sha1sum -c`）

| 文件 | 版本 | 钉扎 SHA1 | 校验和来源 URL（repo1.maven.org 官方） | Dockerfile ARG |
|---|---|---|---|---|
| hadoop-aws-3.5.0.jar | 3.5.0 | `9e594525d264c0db653c7f68da98b245f7d61ea5` | https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-aws/3.5.0/hadoop-aws-3.5.0.jar.sha1 | HADOOP_AWS_SHA1 |
| bundle-2.35.4.jar（AWS SDK v2 Bundle） | 2.35.4 | `7252265e3970b214708e68a8b74a8fa8c875af1e` | https://repo1.maven.org/maven2/software/amazon/awssdk/bundle/2.35.4/bundle-2.35.4.jar.sha1 | AWS_SDK_V2_BUNDLE_SHA1 |
| analyticsaccelerator-s3-1.3.1.jar | 1.3.1 | `6c9bd0f6c440c9a78e82d272f5f0252d942419f6` | https://repo1.maven.org/maven2/software/amazon/s3/analyticsaccelerator/analyticsaccelerator-s3/1.3.1/analyticsaccelerator-s3-1.3.1.jar.sha1 | ANALYTICS_ACCELERATOR_SHA1 |
| iceberg-spark-runtime-4.1_2.13-1.11.0.jar | 1.11.0 | `f9b1e4a18797c58ae406104ef5c75d1b64a373c5` | https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-spark-runtime-4.1_2.13/1.11.0/iceberg-spark-runtime-4.1_2.13-1.11.0.jar.sha1 | ICEBERG_SPARK_RUNTIME_SHA1 |
| iceberg-aws-bundle-1.11.0.jar | 1.11.0 | `afe5b22dd5bc2d2c0639049b06a92dae2545cac7` | https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-aws-bundle/1.11.0/iceberg-aws-bundle-1.11.0.jar.sha1 | ICEBERG_AWS_BUNDLE_SHA1 |

JAR 下载源（Dockerfile 保持不变，按优先级）：

1. https://maven.aliyun.com/repository/central/<路径>（内网加速）
2. https://repo1.maven.org/maven2/<路径>（官方兜底）

JAR 取数记账（2026-11-15 起，与上面的 Spark 取数段同一治理口径）：
5 处取数的 curl 形态由 `--retry 2 --retry-delay 3 --max-time N` 改为
`--retry 0 --retry-delay 0 --speed-limit 51200 --speed-time 30 --max-time N`，
**`--max-time` 的 N 一律保持原值（120 / 600 / 60 / 120 / 120），传输容差未放宽**。
改动的理由：curl 带 `--retry` 时 `--max-time` 是**每次传输尝试**的墙钟上限（计时器按次重置），
于是单源最坏实耗 = 3N+6 秒；aws-sdk（N=600）单源最坏 1806 秒，**远超**外层
`scripts/build-retry.sh` 的 per-attempt 600 秒总墙钟 ⇒ 取数段还没判红就被外层 SIGKILL 成
`context canceled`，面板上长得像编译失败。改后**重试职责交给跨源冗余**（上面的两源依次试），
单源最坏 = N 秒（标称上限不变），并带停滞检测（低于 50 KB/s 持续 30s 即放弃该源）；
每源带 `[attempt]`/`[ok]`/`[miss]` 诊断（本源墙钟上限 / 本源实耗 / curl 退出码 + 释义），
两源都失败时沿用既有的 `>>> [FATAL] ... 下载失败！` + exit 1。
该形态由 `scripts/test-maven-download-budget.sh` 做成回归测试（并挂进 CI 的 `Bash Syntax Check` 作业）。
**下载源与钉扎 SHA1 未变** ⇒ 本节表格与「JAR 下载源」清单不受本次改动影响。

Iceberg 两个 JAR 仅在 `--build-arg ENABLE_ICEBERG=true` 时下载；
钉扎 SHA1 对应缺省组合 `ICEBERG_VERSION=1.11.0` + `4.1_2.13`，
覆盖这些 ARG 时必须同步更新 SHA1。

## 范围外说明（已知残留项）

- `pip install pyspark==<版本>`：PyPI 安装未启用 hash-pinning
  （需 `--require-hashes` 且覆盖 pyspark 全部依赖的哈希，维护成本高），
  本次任务范围（Spark tarball + 5 类 JAR）之外，列入后续改进项。
- 基础镜像 `eclipse-temurin:17-jre`：依赖 Docker Hub 内容寻址 digest，
  如需更强保障可在 FROM 行钉扎 `@sha256:<digest>`（本次不改，避免影响现有构建）。
