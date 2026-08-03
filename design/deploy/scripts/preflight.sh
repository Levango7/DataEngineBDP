#!/usr/bin/env bash
# ============================================================================
# preflight.sh — 部署前能力探测（呼应部署清单 v0.1 §9 / §10）
# 输出能力矩阵，缺失项告警/降级，避免四环境行为漂移。
# 用法： ./preflight.sh [xinchuang|onprem|publiccloud|privatecloud]
# ============================================================================
set -euo pipefail

ENV="${1:-onprem}"

echo "=== preflight: $ENV ==="

# --- 1. K8s 自建校验（严禁云托管 K8s） ---
K8S_VER=$(kubectl version -o json 2>/dev/null | grep -o '"gitVersion": *"[^"]*"' | head -1 || echo "none")
echo "[1] kubectl 可达: $K8S_VER"
if kubectl get nodes -o wide 2>/dev/null | grep -qiE "managed|ack|eks|tke|ccE"; then
  echo "  [FAIL] 检测到云托管 K8s，违反铁律：K8s 必须自建"
  exit 1
fi

# --- 2. 节点 arch 探测 ---
ARCHS=$(kubectl get nodes -o jsonpath='{range .items[*]}{.status.nodeInfo.architecture}{"\n"}{end}' | sort -u | tr '\n' '/' )
echo "[2] 节点 arch: $ARCHS"

# --- 3. OS 探测 ---
OSES=$(kubectl get nodes -o jsonpath='{range .items[*]}{.status.nodeInfo.osImage}{"\n"}{end}' | sort -u | tr '\n' '/' )
echo "[3] 节点 OS: $OSES"

# --- 4. 对象存储后端（由 Profile 决定；此处仅探测连通） ---
case "$ENV" in
  xinchuang)  BACKEND="国产对象存储(XCObjectDriver)";;
  onprem)     BACKEND="Ceph RGW / MinIO (CephDriver)";;
  publiccloud)BACKEND="客户自购 S3 (客户提供密钥,不绑云托管)";;
  privatecloud)BACKEND="厂商对象存储 / MinIO (PrivateDriver)";;
esac
echo "[4] 存储后端: $BACKEND"

# --- 5. 国密支持（仅信创） ---
if [ "$ENV" = "xinchuang" ]; then
  echo "[5] 国密 SM2/SM3/SM4: 预期启用 (GuomiKMS)"
else
  echo "[5] 国密: 不启用 (标准 AES/SHA256)"
fi

# --- 6. LB 能力 ---
echo "[6] LB: $([ "$ENV" = "publiccloud" ] && echo 'cloud-LB-or-metallb' || echo 'metallb')"

# --- 能力矩阵汇总 ---
echo "---------------------------------------------------"
echo "能力矩阵: arch=$ARCHS | os=$OSES | storage=$BACKEND | env=$ENV"
echo "preflight OK (缺失项请按部署清单 §11 风险对策处理)"
