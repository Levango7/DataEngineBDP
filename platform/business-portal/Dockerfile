# syntax=docker/dockerfile:1.6
# Business Portal Dockerfile
# 数据引擎大数据平台 L5.4 业务门户 - 多租户业务门户

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

# 拷贝源码
COPY . .

# 安装包（editable 模式，使 main.py 能 import business_portal 包）
RUN pip install --no-cache-dir -e .

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8088/api/v1/health || exit 1

EXPOSE 8088

# 默认 SQLite 模式；生产通过环境变量切换到 mock
ENV BP_HOST=0.0.0.0 \
    BP_PORT=8088 \
    BP_LOG_LEVEL=info \
    BP_STORE_TYPE=sqlite \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

CMD ["python", "main.py"]