# ── batch-pipeline 多阶段构建 ──────────────────────────────────
# Stage 1: builder  — 安装 Python 依赖到独立目录
# Stage 2: runtime  — 仅拷贝必要文件 + 依赖，非 root 运行
# 设计参见 docs/evolution.md §6.x（Docker 化部署）
#
# 基础镜像 python:3.12-slim：与 CI 测试矩阵覆盖的 3.10/3.11/3.12 对齐
# （此前基于 python:3.13.1-slim，该版本组合从未被任何 CI 腿验证，
#   属交付盲区——2026-08 审查后对齐到已验证的 3.12）。
# 注意：镜像内无 JDK，`engine.backend="spark"` 在容器内不可用；
# 容器化运行仅支持 python / polars 计算路径（见 readme「Docker」一节）。
# ────────────────────────────────────────────────────────────────

# ── Stage 1: builder ────────────────────────────────────────────
ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/python:3.12-slim AS builder
WORKDIR /build
COPY requirements.txt .
# 装到 /install（便于 runtime 阶段精确拷贝，不含 pip/setuptools 等工具）
#
# 2026-11-20（台账 #112 的第 ② 项残余边界，本轮闭合）：给本段补**墙钟上限**。
# 为什么要补：本段是仓内**第二处**「pyspark 从 PyPI 取数且无墙钟上限」的取数段
# （第一处是 platform/batch-pipeline/docker/spark-cluster/Dockerfile 的 pyspark 安装段，
# 同批已补上限）。本段的 requirements.txt 里 `pyspark>=4.0,<5.0` ⇒ 实际会从 PyPI 拉
# 455.9 MB 的 sdist（CI 日志实测：`Downloading pyspark-4.3.0.tar.gz (455.9 MB)`，
# PR #810 第四波 job 132775309149 / 第二波 job 132734732611），并**从 sdist 本地构建
# wheel**（同日志：Building wheel 10.31s → 23.29s）⇒ 这一段同时吃网络带宽与 CPU。
# 实测（2026-11-20 当轮）：本机到 PyPI 连续 3 轮 16MB Range GET 实测 43–54 kB/s
# ⇒ 按该速率 pyspark 单包就需 ≈ 9300s；而 scripts/build-retry.sh 的 per-attempt
# 挂起超时（BASE_HANG_TIMEOUT，缺省 600s）会把整条 docker build SIGKILL 掉 ⇒
# BuildKit 报 `failed to solve: Canceled: context canceled` ⇒ 面板上长得像编译失败，
# 实际是取数面速率问题（与台账 #112 的原始事故同族，只是这次落在 pip 段）。
# 处置：本段自己设墙钟上限，预算用尽时**自己**判红并说清是「取数面速率不足」。
# 上限取 300s 的两条依据（都不外推）：
#   ① 与本仓已有的取数容差同量级：Spark tarball 预算 420s 对应 529MB（≈1.26 MB/s
#      的容差），本段 300s 对应 pyspark 单包 455.9MB（≈1.52 MB/s）⇒ 同一档容差，
#      不比仓内现状更严；CI 实测本段整步 32.45s / 32.61s ⇒ 对健康形态有 9 倍余量。
#   ② 与外层 attempt 预算不打架：本段之前的耗时只有基座拉取与层准备，远小于
#      spark-cluster 的 135s ⇒ 300s 与外层 600s 之间留有充足余量。
#      该算术由 scripts/test-pyspark-install-budget.sh 断言钉死（上限调大即转红）。
# 上限可用 --build-arg PY_DEPS_INSTALL_TIMEOUT_SECONDS=... 覆盖。
ARG PY_DEPS_INSTALL_TIMEOUT_SECONDS=300
RUN set -eux; \
    echo ">>> 安装 batch-pipeline 运行时依赖（墙钟上限 ${PY_DEPS_INSTALL_TIMEOUT_SECONDS}s）"; \
    if command -v timeout >/dev/null 2>&1; then \
        PY_DEPS_TIMEOUT_CMD="timeout ${PY_DEPS_INSTALL_TIMEOUT_SECONDS}"; \
    else \
        PY_DEPS_TIMEOUT_CMD=""; \
        echo ">>> [warn] 基座无 timeout 命令 ⇒ 本段不设墙钟上限（退化执行）"; \
    fi; \
    PY_DEPS_START_TS="$(date +%s)"; \
    ${PY_DEPS_TIMEOUT_CMD:+${PY_DEPS_TIMEOUT_CMD}} pip install --no-cache-dir --target=/install -r requirements.txt || { \
        rc=$?; \
        elapsed=$(( $(date +%s) - PY_DEPS_START_TS )); \
        if [ "${rc}" -eq 124 ] || [ "${rc}" -eq 137 ]; then \
            echo ">>> [FATAL] 运行时依赖安装超出墙钟上限 ${PY_DEPS_INSTALL_TIMEOUT_SECONDS}s（实耗 ${elapsed}s，退出码 ${rc}）"; \
            echo ">>> [FATAL] 判定：取数面速率不足（requirements 含 pyspark sdist 约 456MB），不是构建内容坏了 ⇒ 调高 PY_DEPS_INSTALL_TIMEOUT_SECONDS 或改用快源重试"; \
        else \
            echo ">>> [FATAL] 运行时依赖安装失败（退出码 ${rc}，实耗 ${elapsed}s）"; \
        fi; \
        exit 1; \
    }; \
    echo ">>> 运行时依赖安装完成（实耗 $(( $(date +%s) - PY_DEPS_START_TS ))s，墙钟上限 ${PY_DEPS_INSTALL_TIMEOUT_SECONDS}s）"

# ── Stage 2: runtime ────────────────────────────────────────────
FROM ${BASE_REGISTRY}/library/python:3.12-slim AS runtime
WORKDIR /app

# 定向补包（台账 #115，承 #74/#98 C-1 口径）：同 asset-exchange——python:3.12-slim 浮动 tag
# 曾以 apt/libapt-pkg7.0 3.0.3 形态漂入 CVE-2026-109518（HIGH，fixed 3.0.4）；门禁扫的是
# FROM 基座引用、看不见构建期升级，故出货镜像（runtime 段）必须自升修复版，基座引用那一面
# 由 docs/trivy-image-ignore.yaml 限期豁免覆盖。--only-upgrade 只动已安装包，
# 目标版本已在基座内时为无操作成功。builder 段不随出货镜像发布，故不重复此条。
# 2026-11-08 追加 zlib1g（同族 CVE-2026-76844/85091，修复版在 trixie-security，基座含该源）。
# 同族补包（2026-11-26：基座 libzstd1 1.5.7+dfsg-1 漂入 CVE-2026-124851（HIGH），修复版
# 1.5.7+dfsg-1+deb13u1 在 trixie-security 仓）⇒ 与 zlib1g 并列进同一条 --only-upgrade。
RUN apt-get update && apt-get install -y --no-install-recommends --only-upgrade \
    apt \
    libapt-pkg7.0 \
    zlib1g \
    libzstd1 \
    && rm -rf /var/lib/apt/lists/*

# 拷贝 builder 阶段安装的依赖
COPY --from=builder /install /app/vendor
ENV PYTHONPATH="/app/vendor"
ENV PATH="/app/vendor/bin:${PATH}"

# 创建非 root 用户（UID/GID 1000，与 K8s/securityContext 约定一致）
RUN useradd -m -u 1000 batch-pipeline

# 拷贝应用代码（仅包 + config + 入口，排除 tests/benchmarks/docs 等）
COPY --chown=batch-pipeline:batch-pipeline batch_pipeline/ ./batch_pipeline/
COPY --chown=batch-pipeline:batch-pipeline config/ ./config/
COPY --chown=batch-pipeline:batch-pipeline main.py ./
COPY --chown=batch-pipeline:batch-pipeline requirements.txt ./

# 创建运行时目录并赋属主
RUN mkdir -p run data/raw state && chown -R batch-pipeline:batch-pipeline run data state

USER batch-pipeline

# 健康检查：入口模块可导入 + 配置可解析（与 docker-compose.yml 的 healthcheck 语义一致）
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD python -c "import json, batch_pipeline.pipeline; json.load(open('config/pipeline.json', encoding='utf-8-sig'))"

CMD ["python", "-m", "batch_pipeline.pipeline", "--config", "config/pipeline.json"]
