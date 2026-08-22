# syntax=docker/dockerfile:1.6
# Knowledge Engineering Engine Dockerfile
# 数据引擎大数据平台 L4.5.2 智能数据层 - 知识工程引擎

FROM python:3.11-slim AS builder

WORKDIR /build

# 系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml setup.py ./
COPY knowledge_engine/ knowledge_engine/

# 安装依赖到虚拟环境
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip wheel setuptools \
    && pip install --no-cache-dir .

# ---------- runtime ----------
FROM python:3.11-slim AS runtime

LABEL org.opencontainers.image.title="sq-knowledge-engine" \
      org.opencontainers.image.description="Shuqing Big Data Platform - Knowledge Engineering Engine (L4.5.2)" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.source="https://github.com/shuqing/sq-knowledge-engine"

# 拷贝虚拟环境
COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app
COPY --from=builder /build/knowledge_engine/ ./knowledge_engine/
COPY main.py ./

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8080/api/v1/health || exit 1

EXPOSE 8080

# 默认 Mock 模式；生产通过环境变量切换到 nebula / llm
ENV KE_HOST=0.0.0.0 \
    KE_PORT=8080 \
    KE_LOG_LEVEL=info \
    KE_STORE_TYPE=mock \
    KE_EXTRACTOR_TYPE=mock  # ⚠️ 开发默认mock；生产必须通过环境变量覆盖为nebula/llm

CMD ["python", "main.py"]