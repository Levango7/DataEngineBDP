# T031 模型评测平台 Dockerfile
# 数据引擎大数据平台 · 模型评测任务引擎 + A/B 对比报告
#
# 构建：docker build -t shuqing/evaluation:0.1.0 -f platform/llm-gateway/evaluation/Dockerfile platform/llm-gateway/evaluation
# 运行：docker run -p 18086:8086 -e LLM_GATEWAY_URL=http://it-llm-gateway:8084 shuqing/evaluation:0.1.0

ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/python:3.12-slim AS base

# 设置工作目录
WORKDIR /app

# 安装系统依赖（datasets 需要）+ 定向补包（2026-11-08：基座 zlib1g CVE-2026-76844/85091，
# 修复版在 trixie-security 仓；门禁扫 FROM 基座引用看不见构建期升级 ⇒ 出货镜像自升）
# 同族补包（2026-11-26：基座 libzstd1 1.5.7+dfsg-1 漂入 CVE-2026-124851（HIGH），修复版
# 1.5.7+dfsg-1+deb13u1 在 trixie-security 仓）⇒ 与 zlib1g 并列进同一条 --only-upgrade。
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && apt-get install -y --no-install-recommends --only-upgrade zlib1g libzstd1 \
    && rm -rf /var/lib/apt/lists/*

# 先复制依赖文件，利用 Docker 层缓存
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# 创建非 root 运行用户（安装阶段保持 root，仅运行时降权）
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# 复制应用代码
COPY --chown=app:app app/ ./app/

# 暴露端口
EXPOSE 8086

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8086/health', timeout=3)" || exit 1

# 确保 app 用户对 /app 可写，切换非 root 运行
RUN chown -R app:app /app
USER app

# 启动命令
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8086"]