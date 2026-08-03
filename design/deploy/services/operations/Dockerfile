# 数擎运营后台 operations-api 镜像
# 多 arch: 由 deploy/ci/build-images.yaml 以 buildx 产出 amd64 + arm64 (信创额外 openEuler 变体)
FROM python:3.11-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

ENV PROMETHEUS_URL="http://prometheus.monitoring:9090" \
    ENCAPS_URL="http://encaps-layer:8080" \
    ADMIN_TOKEN="change-me"

EXPOSE 8080
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]
