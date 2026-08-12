# syntax=docker/dockerfile:1.6
# Industry Templates Platform Dockerfile
# 数据引擎大数据平台 L5.7 行业应用模板 - 行业应用模板平台

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

# 安装包（editable 模式，使 main.py 能 import industry_templates 包）
RUN pip install --no-cache-dir -e .

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8091/health || exit 1

EXPOSE 8091

# 默认 Mock 模式；生产通过环境变量切换到 helm
ENV INDUSTRY_TEMPLATES_HOST=0.0.0.0 \
    INDUSTRY_TEMPLATES_PORT=8091 \
    INDUSTRY_TEMPLATES_LOG_LEVEL=info \
    INDUSTRY_TEMPLATES_DEPLOY_MODE=mock \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

CMD ["python", "main.py"]