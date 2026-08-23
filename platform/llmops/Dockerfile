# syntax=docker/dockerfile:1.6
# LLMOps Platform Dockerfile
# 数据引擎大数据平台 L4.5.3 智能数据层 - LLMOps 运营平台

FROM python:3.11-slim AS builder

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
FROM python:3.11-slim AS runtime

LABEL org.opencontainers.image.title="sq-llmops" \
      org.opencontainers.image.description="Shuqing Big Data Platform - LLMOps (L4.5.3)" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.source="https://github.com/shuqing/sq-llmops"

# 拷贝虚拟环境
COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app
COPY --from=builder /build/llmops/ ./llmops/
COPY main.py ./

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8080/api/v1/health || exit 1

EXPOSE 8080

# ⚠️ LLMOPS_STORE_TYPE 必须由部署环境通过 -e 或 docker-compose 注入
# 不设默认值：未配置时应用应 fail-fast 而非静默降级为 mock
ENV LLMOPS_HOST=0.0.0.0 \
    LLMOPS_PORT=8080 \
    LLMOPS_LOG_LEVEL=info

CMD ["python", "main.py"]