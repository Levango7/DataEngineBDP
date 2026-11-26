# syntax=docker/dockerfile:1.6
# Asset Exchange Platform Dockerfile
# 数据引擎大数据平台 L5.6 数据资产交易市场 - 资产交换平台

ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/python:3.12-slim

WORKDIR /app

# 系统依赖（curl 用于健康检查）
# ① curl 是「安装」：python:3.12-slim 基座不带 curl，必须用普通 install（--only-upgrade 对
#    未安装包会跳过——11-06 实测教训：写成 --only-upgrade curl 导致 curl 缺席、HEALTHCHECK
#    恒 unhealthy、nightly compose 栈起不来，见台账 #115）。
# ② apt/libapt-pkg7.0 是「定向升级」（台账 #115，承 #74/#98 C-1 口径，Debian 侧对应动作）：
#    基座浮动 tag 曾以 3.0.3 形态漂入 CVE-2026-109518（HIGH，fixed 3.0.4）。门禁扫的是
#    FROM 基座引用、看不见构建期升级，故出货镜像必须自升到修复版；基座引用那一面由
#    docs/trivy-image-ignore.yaml 限期豁免覆盖（到期后未清自动重新判红）。
#    --only-upgrade 只动已安装包：目标版本已在基座内时为无操作成功（真容器实测 rc=0）。
# ③ zlib1g 同族定向升级（2026-11-08，CVE-2026-76844 / CVE-2026-85091，均 HIGH）：
#    基座 dpkg 实测 zlib1g 1:1.3.dfsg+really1.3.1-1+b1，修复版 1+deb13u1 在 trixie-security
#    仓（基座 /etc/apt/sources.list.d/debian.sources 含 trixie-security 行，layer 直读实证），
#    main 仓无 ⇒ --only-upgrade zlib1g 可取到修复版；基座引用那一面由同一豁免账覆盖。
# ④ Debian 系统包定向升级（台账 #137，2026-11-25）：基座漂至 trixie 后 CI 报 54 条
#    （去重 14 id）HIGH 于 util-linux/acl/ncurses/sqlite/ssl/systemd/zstd/perl 等系统包。
#    出货侧按 #115 同法 --only-upgrade 涉及包升至最新可用补丁（只动已安装包，
#    无更新版时为无操作成功）；基座引用那一面由同一豁免账按 CVE id 限期覆盖。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && apt-get install -y --no-install-recommends --only-upgrade apt libapt-pkg7.0 \
    && apt-get install -y --no-install-recommends --only-upgrade zlib1g \
    && apt-get install -y --no-install-recommends --only-upgrade \
        bsdutils libacl1 libblkid1 liblastlog2-2 libmount1 libncursesw6 libsmartcols1 \
        libsqlite3-0 libssl3t64 libsystemd0 libtinfo6 libudev1 libuuid1 libzstd1 \
        login mount ncurses-base ncurses-bin openssl openssl-provider-legacy perl-base util-linux \
    && rm -rf /var/lib/apt/lists/*

# 安装依赖
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip wheel setuptools \
    && pip install --no-cache-dir -r requirements.txt

# 创建非 root 运行用户（安装阶段保持 root，仅运行时降权）
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# 拷贝源码
COPY --chown=app:app . .

# 安装包（editable 模式，使 main.py 能 import asset_exchange 包）
RUN pip install --no-cache-dir -e .

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8087/api/v1/health || exit 1

EXPOSE 8087

# 默认 SQLite 模式；生产通过环境变量切换到 mock
ENV ASSET_EXCHANGE_HOST=0.0.0.0 \
    ASSET_EXCHANGE_PORT=8087 \
    ASSET_EXCHANGE_LOG_LEVEL=info \
    ASSET_EXCHANGE_STORE_TYPE=sqlite \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

# 确保 app 用户对 /app 可写（SQLite 数据文件需落盘），随后切换非 root 运行
RUN chown -R app:app /app
USER app

CMD ["python", "main.py"]