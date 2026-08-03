#!/usr/bin/env bash
# 数擎云核 SKE · 宿主机内核调优 (ske tune-host 调用)
# 目标: 大页 / NUMA 绑核 / CPU 隔离 / 网络栈 / 文件句柄
# 适用: 生产 Linux 宿主 (裸金属/VM). 笔记本 dev 模式会尽力执行并提示受限项.
set -uo pipefail
echo "== [SKE] 内核调优 =="

# ---- 1. 大页 (2MB) ----
echo "-- 分配 2MB 大页 (目标 1024 个, 失败降级) --"
for n in 1024 512 256 128; do
  if echo "$n" > /sys/kernel/mm/hugepages/hugepages-2048kB/nr_hugepages 2>/dev/null; then
    echo "   hugepages-2048kB = $n"; break
  else
    echo "   尝试 $n 失败, 降级"
  fi
done
# 关闭 THP (避免后台规整抖动)
if [ -f /sys/kernel/mm/transparent_hugepage/enabled ]; then
  echo never > /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null && echo "   THP=never"
fi

# ---- 2. NUMA / CPU 隔离 (仅提示, 实际隔离在 grub cmdline 固化) ----
echo "-- NUMA 拓扑 (供 grub isolcpus 参考) --"
command -v numactl >/dev/null 2>&1 && numactl -H 2>/dev/null | head -20 || echo "   numactl 未装, 跳过"
echo "   提示: 生产机在 grub 固化 isolcpus=<控制面核>, 重启生效; 本脚本仅做运行期尝试."

# ---- 3. 网络栈 sysctl ----
echo "-- 网络栈 sysctl --"
sysctl -w net.core.somaxconn=65535 2>/dev/null
sysctl -w net.ipv4.tcp_tw_reuse=1 2>/dev/null
sysctl -w net.ipv4.tcp_rmem="4096 87380 16777216" 2>/dev/null
sysctl -w net.ipv4.tcp_wmem="4096 65536 16777216" 2>/dev/null
sysctl -w net.core.rmem_max=16777216 2>/dev/null
sysctl -w net.core.wmem_max=16777216 2>/dev/null
sysctl -w net.ipv4.ip_local_port_range="1024 65535" 2>/dev/null
sysctl -w fs.file-max=2097152 2>/dev/null
sysctl -w vm.swappiness=1 2>/dev/null
sysctl -w vm.max_map_count=262144 2>/dev/null

# ---- 4. 持久化 (systemd 环境) ----
if [ -d /etc/sysctl.d ]; then
  cat > /etc/sysctl.d/99-ske.conf <<'EOF'
net.core.somaxconn=65535
net.ipv4.tcp_tw_reuse=1
net.ipv4.tcp_rmem=4096 87380 16777216
net.ipv4.tcp_wmem=4096 65536 16777216
net.core.rmem_max=16777216
net.core.wmem_max=16777216
net.ipv4.ip_local_port_range=1024 65535
fs.file-max=2097152
vm.swappiness=1
vm.max_map_count=262144
EOF
  echo "   已写入 /etc/sysctl.d/99-ske.conf (持久化)"
fi
echo "== [SKE] 内核调优完成 =="
