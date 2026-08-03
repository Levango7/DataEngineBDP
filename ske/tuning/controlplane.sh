#!/usr/bin/env bash
# 数擎云核 SKE · 控制面隔离调优 (ske tune-host 调用)
# 目标: etcd 专属 NVMe + 控制面组件 CPU 隔离 + apiserver 高并发落地
set -uo pipefail
echo "== [SKE] 控制面隔离调优 =="

# ---- 1. etcd 专属盘 ----
echo "-- etcd 数据目录落专属 NVMe (生产) --"
echo "   推荐: 独立 NVMe 挂载到 /var/lib/etcd, 文件系统 xfs, 挂载项 noatime"
echo "   本脚本不自动格式化; 请按 storage.sh 建议手动挂载后重试 kubeadm"
if [ -d /var/lib/etcd ]; then
  mount | grep -q "/var/lib/etcd" && echo "   /var/lib/etcd 已挂载" || echo "   警告: /var/lib/etcd 未独立挂载, 性能与隔离不达标"
fi

# ---- 2. 控制面 CPU 隔离建议 (grub isolcpus) ----
echo "-- 控制面 CPU 隔离建议 --"
NCPU=$(nproc 2>/dev/null || echo 0)
echo "   逻辑核数: $NCPU"
if [ "$NCPU" -ge 8 ]; then
  echo "   建议: 预留最后 2~4 核给控制面 (apiserver/scheduler/etcd), grub 固化 isolcpus"
  echo "   示例: GRUB_CMDLINE_LINUX=\"isolcpus=4-7\" 然后 update-grub && reboot"
else
  echo "   核数 < 8, 控制面无法独立隔离; 建议生产机 >= 8 核"
fi

# ---- 3. apiserver/kubelet 高并发参数已在 manifests/kubeadm-config.yaml 固化 ----
echo "-- apiserver 参数 (已固化于 kubeadm-config.yaml) --"
echo "   max-requests-inflight=3000 / max-mutating-requests-inflight=2000 / watch-cache-size=1000"

echo "== [SKE] 控制面隔离调优完成 =="
