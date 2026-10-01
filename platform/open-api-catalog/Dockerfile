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
# SQLite 数据目录
# 应用以相对路径 'data' 访问（WORKDIR=/app解析为 /app/data），
# 且 store.py 用 path.parent.mkdir(parents=True) 自动建目录 —— 但 /app 归主后
# 非 root 用户无权在 /app 下新建目录，mkdir 直接抛
# PermissionError: [Errno 13] Permission denied: 'data'。
# 预先创建并 chown，避免依赖运行期 mkdir。
RUN mkdir -p /app/data \
    && chown -R app:app /app
USER app

HEALTHCHECK --start-period=30s --interval=10s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8090/api/v1/health')" || exit 1

CMD ["python", "main.py"]