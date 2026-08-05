# 数擎云核 SKE · 自定义 kind 节点镜像
# 基于 kindest/node (kind 官方节点镜像), 烘焙 SKE 深度调优的 kubelet / scheduler 配置
# 构建: docker build -t shuqing-ske:dev -f Dockerfile ../
#   构建上下文为 SKE 根目录 (ske/), 因此 COPY 路径以 manifests/ 开头
#
# 注意: kindest/base 的 tag 是日期格式 (如 20240514), 不存在 v1.30.0 tag;
#       正确的版本化基础镜像是 kindest/node:v1.30.0 (内含 K8s v1.30.0 二进制)。
FROM kindest/node:v1.30.0

# 烘焙 SKE kubelet 深度调优配置 (CPU 静态/拓扑单NUMA/大页/系统预留)
# 源路径相对构建上下文 (ske/), 目标路径为节点镜像内 kubeadm 默认路径
COPY manifests/kubelet-config.yaml /etc/kubernetes/kubelet-config.yaml

# 烘焙 SKE 调度策略 (拓扑感知 + 数据本地性扩展器)
COPY manifests/scheduler-policy.yaml /etc/kubernetes/scheduler-policy.yaml

# 预热 Cilium 运行所需内核模块提示(忽略不存在项)
RUN set -eux; \
    echo "SKE node image: kubelet+scheduler 配置已烘焙" > /etc/ske-build-info.txt; \
    cat /etc/ske-build-info.txt
