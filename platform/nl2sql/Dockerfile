# syntax=docker/dockerfile:1.6
# NL2SQL Core Engine Dockerfile
# 数据引擎大数据平台 · L4.5.4 NL2SQL 核心引擎

FROM python:3.11-slim

WORKDIR /app

# 系统依赖（curl 用于健康检查）
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 安装依赖
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip wheel setuptools \
    && pip install --no-cache-dir -r requirements.txt

# 创建非 root 运行用户（安装阶段保持 root，仅运行时降权）
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# 拷贝源码
COPY --chown=app:app . .

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8093/api/v1/health || exit 1

EXPOSE 8093

# 默认 Mock 模式；生产通过环境变量切换
ENV NL2SQL_HOST=0.0.0.0 \
    NL2SQL_PORT=8093 \
    NL2SQL_LOG_LEVEL=info \
    NL2SQL_LLM_MODE=mock \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

# 确保 app 用户对 /app 可写，随后切换非 root 运行
RUN chown -R app:app /app
USER app

CMD ["python", "-m", "uvicorn", "app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8093"]