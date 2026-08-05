#!/usr/bin/env bash
# 数擎云核 SKE · 构建自定义节点镜像 (烘焙 kubelet/scheduler 深度调优配置)
# 前置: docker + kind 可用 (kind 由 ske.sh 负责安装)
#
# 注意: kind build node-image 不接受 Dockerfile 作为位置参数,
#       其位置参数是 Kubernetes 源码树目录 (用于从源码构建 node 镜像)。
#       此处改用 docker build 直接基于 kindest/node:v1.30.0 烘焙 SKE 配置,
#       构建产物作为 kind 节点镜像使用 (与 kindest/node 同构, 仅叠加配置文件)。
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SKE_DIR="$(cd "$DIR/.." && pwd)"
IMG="${SKE_NODE_IMAGE:-shuqing-ske:dev}"

echo "==[SKE] 构建自定义节点镜像: $IMG =="
if ! command -v docker >/dev/null 2>&1; then
  echo "   错误: 未找到 docker. 请先启动 Docker Desktop." >&2
  exit 1
fi

# 构建上下文需包含 manifests/kubelet-config.yaml 与 manifests/scheduler-policy.yaml
# Dockerfile 中通过相对路径 COPY 引用, 因此以 SKE 根目录为构建上下文
if [ ! -f "$SKE_DIR/manifests/kubelet-config.yaml" ]; then
  echo "   错误: 缺少 $SKE_DIR/manifests/kubelet-config.yaml" >&2
  exit 1
fi
if [ ! -f "$SKE_DIR/manifests/scheduler-policy.yaml" ]; then
  echo "   错误: 缺少 $SKE_DIR/manifests/scheduler-policy.yaml" >&2
  exit 1
fi

# 直接 docker build (基于 kindest/node:v1.30.0 烘焙配置)
# 构建上下文为 SKE 根目录, Dockerfile 通过相对路径引用 manifests/ 下的配置
docker build -t "$IMG" -f "$DIR/Dockerfile" "$SKE_DIR"
echo "==[SKE] 节点镜像构建完成: $IMG =="
echo "    后续: bash ../ske.sh up --profile local --mode dev 将使用该镜像"
