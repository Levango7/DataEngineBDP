# syntax=docker/dockerfile:1.6
# Model Finetuning Engine Dockerfile
# 数据引擎大数据平台 · T032 LoRA/QLoRA/全参微调引擎
#
# 构建方式：
#   docker build -t sq/model-finetuning:0.1.0 .
#
# 运行方式（Mock 模式，无需 GPU）：
#   docker run -p 8095:8095 -e FINETUNE_MOCK_MODE=true sq/model-finetuning:0.1.0
#
# 运行方式（GPU 模式，需 nvidia-container-toolkit）：
#   docker run --gpus all -p 8095:8095 -e FINETUNE_MOCK_MODE=false \
#     -v /data/finetune:/data/finetune sq/model-finetuning:0.1.0

# 基础镜像：选择 CUDA 12.1 镜像以支持 GPU 训练
# Mock 模式下也可使用 python:3.11-slim 减小体积
FROM python:3.11-slim AS base

WORKDIR /app

# 系统依赖（curl 用于健康检查）
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# 安装 Python 依赖
# 注：torch / deepspeed / bitsandbytes 体积较大，
# 生产 GPU 镜像建议使用预装这些库的基础镜像以加速构建
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip wheel setuptools \
    && pip install --no-cache-dir -r requirements.txt

# 创建非 root 运行用户（安装阶段保持 root，仅运行时降权）
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

# 拷贝源码
COPY --chown=app:app . .

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -fsS http://localhost:8095/api/v1/health || exit 1

EXPOSE 8095

# 运行时不烘焙 Mock（组件代码默认 mock_mode=true，生产由 Helm values
# 显式注入 FINETUNE_MOCK_MODE=false 覆盖，见 design/deploy/charts/model-finetuning）；
# 本地降级由部署环境显式注入，不在镜像内烘焙——防止生产拿到假实现而无感知。
ENV FINETUNE_HOST=0.0.0.0 \
    FINETUNE_PORT=8095 \
    FINETUNE_WORK_DIR=/tmp/finetune \
    FINETUNE_SCHEDULER_BACKEND=volcano \
    FINETUNE_LOG_LEVEL=info \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

# 工作目录 FINETUNE_WORK_DIR=/tmp/finetune 对非 root 天然可写；
# 确保 app 用户对 /app 可写后切换非 root 运行
RUN chown -R app:app /app
USER app

CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8095"]

# ============================================================
# GPU 镜像（多阶段，可选）
# ============================================================
# 如需 GPU 镜像，使用以下基础镜像并取消注释：
# FROM nvidia/cuda:12.1.1-cudnn8-runtime-ubuntu22.04 AS gpu
# ...（安装 Python + 依赖 + 源码）