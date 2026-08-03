#!/usr/bin/env bash
# 数擎云核 SKE · 构建自定义节点镜像 (烘焙 kubelet/scheduler 深度调优配置)
# 前置: docker + kind 可用 (kind 由 ske.sh 负责安装)
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
IMG="${SKE_NODE_IMAGE:-shuqing-ske:dev}"

echo "== [SKE] 构建自定义节点镜像: $IMG =="
if ! command -v kind >/dev/null 2>&1; then
  echo "   错误: 未找到 kind. 请先运行 ske.sh (会自动安装), 或手动安装 kind." >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "   错误: 未找到 docker. 请先启动 Docker Desktop." >&2
  exit 1
fi

# kind build node-image 会从 Dockerfile 构建并导入
kind build node-image --image "$IMG" "$DIR/Dockerfile"
echo "== [SKE] 节点镜像构建完成: $IMG =="
echo "    后续: bash ../ske.sh up --profile local --mode dev 将使用该镜像"
