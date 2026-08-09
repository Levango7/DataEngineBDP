# syntax=docker/dockerfile:1.6
# ML Platform Dockerfile
# 数据引擎大数据平台 L4.5.6 智能数据层 - 机器学习平台

FROM python:3.11-slim AS builder

WORKDIR /build

# 系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml setup.py ./
COPY ml_platform/ ml_platform/

# 安装依赖到虚拟环境
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip wheel setuptools \
    && pip install --no-cache-dir .[sklearn]

# ---------- runtime ----------
FROM python:3.11-slim AS runtime

LABEL org.opencontainers.image.title="sq-ml-platform" \
      org.opencontainers.image.description="Shuqing Big Data Platform - ML Platform (L4.5.6)" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.source="https://github.com/shuqing/sq-ml-platform"

# 拷贝虚拟环境
COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app
COPY --from=builder /build/ml_platform/ ./ml_platform/
COPY main.py ./

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8080/health || exit 1

EXPOSE 8080

# 默认 Mock 模式；生产通过环境变量切换到 sklearn / spark
ENV ML_HOST=0.0.0.0 \
    ML_PORT=8080 \
    ML_LOG_LEVEL=info \
    ML_BACKEND_TYPE=mock \
    ML_FEATURE_STORE_TYPE=mock \
    ML_EXPERIMENT_STORE_TYPE=mock

CMD ["python", "main.py"]