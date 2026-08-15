# ============================================================================
# Python 函数运行时 Dockerfile · 数据引擎大数据平台 T025
# ----------------------------------------------------------------------------
# 基础镜像：python:3.12-slim（约 45MB，冷启动友好）
# 冷启动优化策略：
#   1. 多阶段构建：builder 阶段预编译 .pyc，runtime 阶段仅复制产物；
#   2. 依赖预装：requirements.txt 在 builder 阶段安装到 /install；
#   3. 预编译字节码：python -m compileall 生成 .pyc，启动时免编译；
#   4. 非 root 运行：UID 65532（distroless 风格安全）；
#   5. init container 预加载函数代码（见 kservice.yaml）。
# 目标冷启动：≤ 3s（slim 镜像 + 预编译 .pyc + 依赖预装）
# ============================================================================
FROM docker.m.daocloud.io/library/python:3.12-slim AS builder

WORKDIR /build

# 依赖预装（利用 Docker 层缓存）
COPY requirements.txt /build/requirements.txt
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt \
    && python -m compileall /install

# 复制应用代码
COPY app/ /build/app/

# 预编译应用字节码（冷启动时免编译）
RUN python -m compileall /build/app/

# ----------------------------------------------------------------------------
# runtime 阶段：极简镜像
# ----------------------------------------------------------------------------
FROM docker.m.daocloud.io/library/python:3.12-slim AS runtime

# 从 builder 复制预装依赖与预编译字节码
COPY --from=builder /install /usr/local
COPY --from=builder /build/app /app/app

# 函数代码目录（init container 预加载，或构建时 COPY）
COPY app/functions /app/functions

WORKDIR /app

# 非 root 用户
RUN useradd -r -u 65532 -s /usr/sbin/nologin appuser \
    && chown -R appuser:appuser /app
USER appuser

ENV PYTHONPATH=/app \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=0 \
    FUNCTIONS_DIR=/app/functions \
    LOG_LEVEL=INFO

EXPOSE 8080

# 健康检查（Knative readinessProbe 使用）
HEALTHCHECK --interval=5s --timeout=3s --start-period=2s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8080/api/v1/health')" || exit 1

# 启动命令：uvicorn 单 worker（Knative 通过 KPA 横向扩 Pod）
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-access-log"]