# syntax=docker/dockerfile:1.6
# LLMOps Platform Dockerfile
# 数据引擎大数据平台 L4.5.3 智能数据层 - LLMOps 运营平台

ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/python:3.11-slim AS builder

WORKDIR /build

# 系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml setup.py ./
COPY llmops/ llmops/

# 安装依赖到虚拟环境
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip wheel setuptools \
    && pip install --no-cache-dir .

# ---------- runtime ----------
FROM ${BASE_REGISTRY}/library/python:3.11-slim AS runtime

LABEL org.opencontainers.image.title="sq-llmops" \
      org.opencontainers.image.description="Shuqing Big Data Platform - LLMOps (L4.5.3)" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.source="https://github.com/shuqing/sq-llmops"

# 系统依赖（curl 用于健康检查；本服务此前未装——见下方 HEALTHCHECK 注释）
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 拷贝虚拟环境
COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

# 创建非 root 运行用户（构建/安装阶段保持 root，仅运行时降权）
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
COPY --from=builder --chown=app:app /build/llmops/ ./llmops/
COPY --chown=app:app main.py ./

# 健康检查
# 路径为 /health：本服务健康路由未挂 API 前缀（llmops/api/app.py: include_router(health.router)）。
# 此前两处缺陷叠加——镜像未装 curl + 探不存在的 /api/v1/health——使容器无论应用是否正常
# 都不可能 healthy（2026-10-11 实测：宿主 curl /api/v1/health 得 404、容器内无 curl 可执行）。
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8080/health || exit 1

EXPOSE 8080

# ⚠️ LLMOPS_STORE_TYPE 必须由部署环境通过 -e 或 docker-compose 注入
# 不设默认值：未配置时应用应 fail-fast 而非静默降级为 mock
ENV LLMOPS_HOST=0.0.0.0 \
    LLMOPS_PORT=8080 \
    LLMOPS_LOG_LEVEL=info

# 确保 app 用户对 /app 可写，切换非 root 运行
RUN chown -R app:app /app
USER app

CMD ["python", "main.py"]