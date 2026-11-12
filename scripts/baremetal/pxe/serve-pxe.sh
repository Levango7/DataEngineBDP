#!/usr/bin/env bash
# PXE-over-HTTP 根目录服务 + 交付前自检（台账 #121 路径 ③）
#
# 为什么是 HTTP 而不是 TFTP：
#   iPXE 与 UEFI HTTP Boot 都原生支持 HTTP；TFTP 是单线程、并发差、还常被出口策略挡。
#   本脚本只用 python3 标准库起 HTTP 服务，不引入额外依赖。
#
# 用法：
#   bash scripts/baremetal/pxe/serve-pxe.sh --root /srv/pxe --port 8080
#   bash scripts/baremetal/pxe/serve-pxe.sh --root /srv/pxe --check-only
#
# --check-only 只做交付前自检（文件齐不齐、占位符有没有渲染），不启服务；
# 它的退出码可直接挂到 CI / 交付清单上：0 = 齐备，1 = 缺件或占位符未替换。
set -euo pipefail

ROOT=""
PORT="${PXE_HTTP_PORT:-8080}"
BIND_ADDR="0.0.0.0"
CHECK_ONLY=0

PXE_KERNEL="${PXE_KERNEL:-vmlinuz}"
PXE_INITRD="${PXE_INITRD:-initrd.img}"
PXE_USERDATA="${PXE_USERDATA:-user-data.yaml}"
PXE_BOOT_SCRIPT="${PXE_BOOT_SCRIPT:-boot.ipxe}"
PXE_BOOT_CFG="${PXE_BOOT_CFG:-boot.ipxe.cfg}"

usage() {
  cat <<'EOF'
用法: serve-pxe.sh --root <PXE 根目录> [--port 8080] [--bind 0.0.0.0] [--check-only]

必填文件（缺一即 --check-only 判红）:
  boot.ipxe       iPXE 引导入口
  boot.ipxe.cfg   iPXE 引导配置
  vmlinuz         内核（由运维从选定的 netboot 镜像放入）
  initrd.img      initramfs（同上）
  user-data.yaml  cloud-init 配置（占位符必须已渲染）

环境变量可覆盖上述文件名: PXE_KERNEL / PXE_INITRD / PXE_USERDATA /
PXE_BOOT_SCRIPT / PXE_BOOT_CFG / PXE_HTTP_PORT
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --root)
      ROOT="${2:-}"
      shift 2
      ;;
    --port)
      PORT="${2:-}"
      shift 2
      ;;
    --bind)
      BIND_ADDR="${2:-}"
      shift 2
      ;;
    --check-only)
      CHECK_ONLY=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [ -z "$ROOT" ]; then
  echo "FAIL 缺少 --root" >&2
  usage >&2
  exit 1
fi

if [ ! -d "$ROOT" ]; then
  echo "FAIL PXE 根目录不存在: $ROOT" >&2
  exit 1
fi

missing=0
echo "== PXE 根目录自检: $ROOT"
for f in "$PXE_BOOT_SCRIPT" "$PXE_BOOT_CFG" "$PXE_KERNEL" "$PXE_INITRD" "$PXE_USERDATA"; do
  if [ -f "$ROOT/$f" ]; then
    size=$(wc -c < "$ROOT/$f" | tr -d ' ')
    echo "  OK   $f（${size} 字节）"
  else
    echo "  FAIL 缺失 $f"
    missing=$((missing + 1))
  fi
done

# 占位符未渲染 ⇒ 装机时 cloud-init 会拿到 __K3S_TOKEN__ 这种字面量，属交付事故。
if [ -f "$ROOT/$PXE_USERDATA" ]; then
  if grep -q '__[A-Z0-9_]*__' "$ROOT/$PXE_USERDATA"; then
    echo "  FAIL $PXE_USERDATA 仍含未渲染占位符（形如 __K3S_TOKEN__）"
    missing=$((missing + 1))
  else
    echo "  OK   $PXE_USERDATA 占位符已全部渲染"
  fi
fi

if [ "$missing" -ne 0 ]; then
  echo "RESULT: FAIL（缺件/未渲染 ${missing} 项）" >&2
  exit 1
fi
echo "RESULT: OK（文件齐备）"

if [ "$CHECK_ONLY" -eq 1 ]; then
  exit 0
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "FAIL 未找到 python3，无法起 HTTP 服务" >&2
  exit 1
fi

echo "== 启动 HTTP 服务: http://${BIND_ADDR}:${PORT}/  (root=$ROOT)"
echo "   目标机 UEFI/PXE 里指向: http://<本机IP>:${PORT}/boot.ipxe"
exec python3 -m http.server "$PORT" --bind "$BIND_ADDR" --directory "$ROOT"
