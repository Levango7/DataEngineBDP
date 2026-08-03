#!/usr/bin/env bash
# 数擎云核 SKE · 存储 IO 调优 (ske tune-host 调用)
# 目标: NVMe 写优化 / IO_uring 就绪 / Local PV 供给 / etcd 专属盘
set -uo pipefail
echo "== [SKE] 存储调优 =="

# ---- 1. 检测 NVMe, 给出 etcd 专属盘建议 ----
echo "-- 块设备 --"
lsblk -d -o NAME,SIZE,ROTA,TRAN 2>/dev/null | head -20 || echo "   lsblk 不可用"

# ---- 2. 挂载选项建议: 数据盘 noatime, discard ----
echo "-- 数据盘挂载建议 (生产) --"
echo "   建议: mount -o noatime,nodiratime,discard /dev/nvmeXnY /var/lib/etcd"
echo "   建议: mount -o noatime,nodiratime /dev/nvmeYnZ /var/lib/ske-data"

# ---- 3. IO_uring 就绪检查 (内核 >= 5.1) ----
KVER=$(uname -r)
echo "-- 内核版本: $KVER --"
if [ "$(printf '%s\n' 5.1 "$KVER" | sort -V | head -1)" = "5.1" ]; then
  echo "   IO_uring 内核支持 OK; 引擎写路径建议启用 io_uring (见统一存储 L2.1)"
else
  echo "   内核 < 5.1, IO_uring 不可用; 建议升级内核或走 libaio 回退"
fi

# ---- 4. etcd 盘调度器: none/mq-deadline + 关闭写回合并争抢 ----
echo "-- 为 NVMe 设置 none 调度器 (示例 /dev/nvme0n1) --"
for d in /sys/block/nvme*/queue/scheduler; do
  echo none > "$d" 2>/dev/null && echo "   $d -> none" || true
done

# ---- 5. Local PV 供给 (若用 openebs/local-path 或 SKE 自带) ----
echo "-- Local PV 供给: 生产由 SKE 内置 storageclass 提供; dev 用 MinIO 对象存储 --"

echo "== [SKE] 存储调优完成 =="
