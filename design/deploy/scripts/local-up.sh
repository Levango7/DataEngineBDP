#!/usr/bin/env bash
# 笔记本一键拉起 PoC (无信创机器, 用 local Profile)
# 前置: 已装 docker; 网络可访问 docker.io 与 ghcr.io
# 流程: 起 K3s/kind → 起 MinIO → 应用 local Profile 核心组件(P0) → 跑 examples PoC
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
export DQCTL_PROFILE=local
MINIO_BUCKET=lakehouse

echo "============================================================"
echo " 数擎 · 笔记本本地验证 (Profile=local, 非信创 amd64)"
echo "============================================================"

echo; echo "==> 1. 启动本地 K3s (若未运行)"
if command -v k3s >/dev/null 2>&1; then
  if ! kubectl get nodes >/dev/null 2>&1; then
    echo "    K3s 已安装但未运行, 请执行: sudo k3s server &"
  fi
elif command -v kind >/dev/null 2>&1; then
  if ! kubectl get nodes >/dev/null 2>&1; then
    kind create cluster --name shuqing-local
  fi
else
  echo "    未检测到 k3s/kind, 请先安装其一:"
  echo "      K3s: curl -sfL https://get.k3s.io | sh -"
  echo "      kind: go install sigs.k8s.io/kind@latest"
  exit 1
fi
kubectl get nodes

echo; echo "==> 2. 启动本地对象存储 (RustFS, 替代已停供的 MinIO 社区版)"
# 凭据 env 必须是 RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY（上游 cli.rs:1551/:1559
# 的 clap env 绑定 + entrypoint.sh:181/:182；MINIO_ROOT_* 上游不读）。
# 建桶客户端换 amazon/aws-cli（minio/mc 已断供；botocore 对自定义 --endpoint-url
# 默认 path-style ⇒ 直连 S3 兼容端点）。
if ! docker ps --format '{{.Names}}' | grep -q '^minio$'; then
  docker run -d --name minio -p 9000:9000 -p 9001:9001 \
    -e RUSTFS_ACCESS_KEY=minio -e RUSTFS_SECRET_KEY=minio123 \
    -e RUSTFS_VOLUMES=/data \
    -e RUSTFS_ADDRESS=0.0.0.0:9000 \
    -e RUSTFS_CONSOLE_ADDRESS=0.0.0.0:9001 \
    rustfs/rustfs:1.0.6
fi
# 建 bucket（需 S3 客户端；此处用一次性 aws-cli 容器）
docker run --rm --network host \
  -e AWS_ACCESS_KEY_ID=minio -e AWS_SECRET_ACCESS_KEY=minio123 -e AWS_DEFAULT_REGION=us-east-1 \
  amazon/aws-cli:2.17.23 \
  s3api create-bucket --endpoint-url http://localhost:9000 --bucket "$MINIO_BUCKET" >/dev/null 2>&1 || true
echo "    对象存储就绪 (RustFS): http://localhost:9000  bucket=$MINIO_BUCKET"

echo; echo "==> 3. 应用 local Profile 核心组件 (P0: 封装层/存储/引擎/统一SQL/控制台)"
echo "    (示意: 实际由 Helm 按 deploy/values-base.yaml + profiles/local.yaml 渲染)"
echo "    helm install shuqing deploy/charts -f deploy/values-base.yaml -f deploy/profiles/local.yaml"
echo "    >> 此处需先生成 charts (deploy/ 当前为骨架, 生产由 helm-sync 产出)"

echo; echo "==> 4. 运行端到端 PoC (示例包, 数据非硬编码, cleanup 可清)"
bash "$DIR/examples/run-demo.sh"

echo; echo "============================================================"
echo " 笔记本本地验证完成 (无信创机器, local Profile, amd64)"
echo " 清理: bash deploy/examples/cleanup.sh"
echo "============================================================"
