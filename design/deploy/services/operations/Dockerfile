# 数擎运营后台 operations-api 镜像
# 多 arch: 由 deploy/ci/build-images.yaml 以 buildx 产出 amd64 + arm64 (信创额外 openEuler 变体)
# 安全基线:
#   1. 基础镜像钉到具体小版本 + digest
#      （2026-10-17 换掉原先的占位 sha256：那串 "9c6a0c4e6f4f4f4f…" 是设计稿留下的假值，
#        按它解析必然 manifest unknown；现值取自 docker.m.daocloud.io 的
#        Docker-Content-Digest，按 tag 与按 digest 各取一次均 200。
#        注：钉 digest 后换源要同步换——digest 是内容地址，跨宿主只对同一 manifest 成立）
#   2. 非 root 运行 (UID/GID 1000)
#   3. 内置 HEALTHCHECK
#   4. 不再内置 ADMIN_TOKEN 默认值, 必须由 K8s Secret 注入
# syntax=docker/dockerfile:1.6
ARG BASE_REGISTRY=docker.m.daocloud.io

FROM ${BASE_REGISTRY}/library/python:3.11.7-slim-bookworm@sha256:53d6284a40eae6b625f22870f5faba6c54f2a28db9027408f4dee111f1e885a2

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

# 创建非 root 用户 (UID/GID 1000) 并接管 /app 权限
RUN groupadd -r app -g 1000 && useradd -r -g app -u 1000 app \
    && chown -R app:app /app

# 运行时配置: ADMIN_TOKEN 必须由 K8s Secret 注入, 不再提供不安全默认值
ENV PROMETHEUS_URL="http://prometheus.monitoring:9090" \
    ENCAPS_URL="http://encaps-layer:8080"

USER 1000:1000

EXPOSE 8080

# 健康检查: 调用 /healthz (无需鉴权); slim 镜像无 curl, 用 python urllib
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request,sys; r=urllib.request.urlopen('http://127.0.0.1:8080/healthz',timeout=3); sys.exit(0 if r.status==200 else 1)" || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]
