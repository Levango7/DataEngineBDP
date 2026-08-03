# 数擎云核 SKE · 自定义 kind 节点镜像
# 基于 kindest/base, 烘焙 SKE 深度调优的 kubelet / scheduler 配置
# 构建: kind build node-image --image shuqing-ske:dev ./Dockerfile
FROM kindest/base:v1.30.0

# 烘焙 SKE kubelet 深度调优配置 (CPU 静态/拓扑单NUMA/大页/系统预留)
COPY kubelet-config.yaml /etc/kubernetes/kubelet-config.yaml

# 烘焙 SKE 调度策略 (拓扑感知 + 数据本地性扩展器)
COPY scheduler-policy.yaml /etc/kubernetes/scheduler-policy.yaml

# 预热 Cilium 运行所需内核模块提示(忽略不存在项)
RUN set -eux; \
    echo "SKE node image: kubelet+scheduler 配置已烘焙" > /etc/ske-build-info.txt; \
    cat /etc/ske-build-info.txt
