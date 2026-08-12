# Mock 成员集群 Dockerfile
# ============================================================================
# 轻量：基于 python:3.12-slim 运行 mock 集群服务
# 用途：集成测试中模拟 Karmada 成员集群
# ============================================================================

FROM python:3.12-slim

# 安装 wget（用于 healthcheck）。
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 拷贝 mock 集群服务。
COPY server.py .

# 暴露端口。
EXPOSE 8090

# 运行。
CMD ["python3", "server.py"]