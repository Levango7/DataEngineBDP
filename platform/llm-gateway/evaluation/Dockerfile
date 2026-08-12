# T031 模型评测平台 Dockerfile
# 数据引擎大数据平台 · 模型评测任务引擎 + A/B 对比报告
#
# 构建：docker build -t shuqing/evaluation:0.1.0 -f platform/llm-gateway/evaluation/Dockerfile platform/llm-gateway/evaluation
# 运行：docker run -p 18086:8086 -e LLM_GATEWAY_URL=http://it-llm-gateway:8084 shuqing/evaluation:0.1.0

FROM python:3.11-slim AS base

# 设置工作目录
WORKDIR /app

# 安装系统依赖（datasets 需要）
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# 先复制依赖文件，利用 Docker 层缓存
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# 复制应用代码
COPY app/ ./app/

# 暴露端口
EXPOSE 8086

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8086/health', timeout=3)" || exit 1

# 启动命令
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8086"]