# syntax=docker/dockerfile:1.6
# Industry Templates Platform Dockerfile
# 数据引擎大数据平台 L5.7 行业应用模板 - 行业应用模板平台

ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/python:3.12-slim

WORKDIR /app

# 系统依赖（curl 用于健康检查）+ 定向补包（2026-11-08：基座 zlib1g 1:1.3.dfsg+really1.3.1-1+b1
# 漂入 CVE-2026-76844/85091，修复版 1+deb13u1 在 trixie-security 仓；门禁扫 FROM 基座引用
# 看不见构建期升级 ⇒ 出货镜像自升，基座引用那一面由 docs/trivy-image-ignore.yaml 限期豁免覆盖）
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && apt-get install -y --no-install-recommends --only-upgrade zlib1g \
    && rm -rf /var/lib/apt/lists/*

# 安装依赖
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip wheel setuptools \
    && pip install --no-cache-dir -r requirements.txt

# 创建非 root 运行用户（安装阶段保持 root，仅运行时降权）
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# 拷贝源码
COPY --chown=app:app . .

# 安装包（editable 模式，使 main.py 能 import industry_templates 包）
RUN pip install --no-cache-dir -e .

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8091/api/v1/health || exit 1

EXPOSE 8091

# 运行时默认与代码一致（deployMode=helm）；
# 本地降级由部署环境显式注入 INDUSTRY_TEMPLATES_DEPLOY_MODE=mock，
# 不在镜像内烘焙 mock——防止生产拿到假实现而无感知。
ENV INDUSTRY_TEMPLATES_HOST=0.0.0.0 \
    INDUSTRY_TEMPLATES_PORT=8091 \
    INDUSTRY_TEMPLATES_LOG_LEVEL=info \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

# 确保 app 用户对 /app 可写，随后切换非 root 运行
RUN chown -R app:app /app
USER app

CMD ["python", "main.py"]