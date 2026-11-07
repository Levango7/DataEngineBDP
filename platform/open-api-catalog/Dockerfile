ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/python:3.12-slim

WORKDIR /app

# 定向补包（2026-11-08：基座 zlib1g 1:1.3.dfsg+really1.3.1-1+b1 漂入
# CVE-2026-76844/85091，修复版 1+deb13u1 在 trixie-security 仓；门禁扫 FROM 基座引用
# 看不见构建期升级 ⇒ 出货镜像自升，基座那一面由 docs/trivy-image-ignore.yaml 限期豁免覆盖。
# 此前本文件无 apt 段，新增整段——单阶段构建整镜像出货，故补在该段。）
RUN apt-get update && apt-get install -y --no-install-recommends --only-upgrade \
    zlib1g \
    && rm -rf /var/lib/apt/lists/*

# 安装依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 创建非 root 运行用户（安装阶段保持 root，仅运行时降权）
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# 拷贝源码
COPY --chown=app:app . .

# 安装包
RUN pip install --no-cache-dir -e .

EXPOSE 8090

# 确保 app 用户对 /app 可写，切换非 root 运行
# SQLite 数据目录
# 应用以相对路径 'data' 访问（WORKDIR=/app解析为 /app/data），
# 且 store.py 用 path.parent.mkdir(parents=True) 自动建目录 —— 但 /app 归主后
# 非 root 用户无权在 /app 下新建目录，mkdir 直接抛
# PermissionError: [Errno 13] Permission denied: 'data'。
# 预先创建并 chown，避免依赖运行期 mkdir。
RUN mkdir -p /app/data \
    && chown -R app:app /app
USER app

HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8090/api/v1/health')" || exit 1

CMD ["python", "main.py"]