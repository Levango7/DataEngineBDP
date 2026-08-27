FROM python:3.11-slim

WORKDIR /app

# 安装依赖
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 创建非 root 运行用户（安装阶段保持 root，仅运行时降权）
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# 拷贝源码
COPY --chown=app:app . .

# 安装包
RUN pip install --no-cache-dir -e .

EXPOSE 8090

# 确保 app 用户对 /app 可写，切换非 root 运行
RUN chown -R app:app /app
USER app

HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 CMD curl -f http://localhost:8090/api/v1/health

CMD ["python", "main.py"]