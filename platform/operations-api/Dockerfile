# syntax=docker/dockerfile:1.6
# 数擎运营后台 · 合同管理服务（operations-api）Dockerfile
# P-04 合同交付实体。platform 正版（app.py:5 启动契约为 uvicorn operations_api.app:app
# --host 0.0.0.0 --port 8081；健康端点 /healthz、/readyz）。
#
# 2026-11-10 裁决（#119⑥）：design/deploy/services/operations/ 演示副本已删除，
#    本目录为唯一实现面，本镜像为 charts/operations-api 的部署产物。
#
# ⚠️ JWT_SECRET 启动依赖（jwt_auth 导入即校验，台账 #117 P1）：K8s 环境未显式
#    AUTH_MODE 或 jwt 模式缺 JWT_SECRET（≥32 字节）⇒ 启动即拒；部署侧 secretRef 注入。

ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/python:3.12-slim

WORKDIR /app

# 系统依赖（curl 用于健康检查）——三段式口径（台账 #115 教训）：
# ① curl 属「安装」：python:3.12-slim 基座不带 curl，--only-upgrade 对未安装包会跳过；
# ② apt/libapt-pkg7.0 属「定向升级」：基座浮动 tag 曾漂入 CVE-2026-109518
#    （HIGH，fixed 3.0.4），门禁扫 FROM 引用看不见构建期升级 ⇒ 出货镜像自升，
#    基座引用那一面由 docs/trivy-image-ignore.yaml 限期豁免覆盖；
# ③ musl 同族口径（CVE-2026-107891，1.2.5-r3→r4）。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && apt-get install -y --no-install-recommends --only-upgrade apt libapt-pkg7.0 \
    && apt-get install -y --no-install-recommends --only-upgrade zlib1g \
    && rm -rf /var/lib/apt/lists/*

# 安装依赖（requirements.txt 实测三件：fastapi / pydantic / uvicorn）
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip wheel setuptools \
    && pip install --no-cache-dir -r requirements.txt

# 非 root 运行用户
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# 拷贝源码（合同存储目前为内存仓库——repositories/__init__.py:17-24；持久化属产品范围）
COPY --chown=app:app . .

# 健康检查：/healthz（app.py:35 公开端点，无需鉴权）
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8081/healthz || exit 1

EXPOSE 8081

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    OPERATIONS_API_HOST=0.0.0.0

USER app

# 启动契约为 app.py:5 的 documented 形态（uvicorn operations_api.app:app --port 8081）
CMD ["uvicorn", "operations_api.app:app", "--host", "0.0.0.0", "--port", "8081"]
