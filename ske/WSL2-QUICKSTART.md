# 数擎云核 SKE · WSL2 真 kubeadm 上手手册

> 目标：在一个**独立的 WSL2 Ubuntu 发行版（开 systemd）**里跑**真实的 kubeadm**，完整验证 SKE 的 kubeadm / kubelet / scheduler / Cilium 深度定制。
> 这比 `kind` 更忠实于 SKE「自研 K8s 发行版」的身份，又比独立虚拟机更轻。
> 所有命令都在**你的笔记本**上执行；沙箱内无法运行（Docker/WSL2 不可达）。

---

## 0. 前置认知

- 你的 Docker Desktop 本身就用 **WSL2 后端**。SKE 默认的 `kind` 模式（不传 `--target`）就是把集群建在 Docker Desktop 的 WSL2 VM 里——零额外安装，但集群是"容器里的 kind 节点"。
- 本手册走的是**另一条、更忠实的路**：新建一个独立 WSL2 Ubuntu，在里面装 containerd + kubeadm，**直接 `kubeadm init`**，让 SKE 的调优配置真正落在宿主 Linux 上。
- 二者底层都是 WSL2；区别在"集群是 kind 容器"还是"真 kubeadm 节点"。

---

## 1. 在 Windows 侧安装 WSL2 Ubuntu 并开启 systemd

```powershell
# 管理员 PowerShell
wsl --install -d Ubuntu          # 装 Ubuntu 22.04, 完成后按提示设用户名/密码
wsl --set-default Ubuntu
```

开启 systemd（WSL2 默认可能未开，kubelet 依赖 systemd cgroup 驱动）：

```powershell
# 在 Ubuntu 内执行
sudo tee /etc/wsl.conf > /dev/null <<'EOF'
[boot]
systemd=true
EOF
```

回到 **Windows 管理员 PowerShell** 重启 WSL 使 systemd 生效：

```powershell
wsl --shutdown
wsl -d Ubuntu
```

验证 systemd：

```bash
# 在 Ubuntu 内
ps -p 1 -o comm=     # 应输出 systemd
```

---

## 2. 挂载项目并准备宿主

项目在 Windows 的 `F:\Agent\workbuddy\workspace\ShuqingBigDataPlatform`，在 WSL2 内通常挂载为 `/mnt/f/Agent/workbuddy/workspace/ShuqingBigDataPlatform`。

```bash
cd /mnt/f/Agent/workbuddy/workspace/ShuqingBigDataPlatform

# 一键装 containerd + kubeadm/kubelet/kubectl + cri-tools, 关 swap, 载模块, 设 sysctl
sudo bash ske/wsl2/setup-host.sh
```

---

## 3. 拉起 SKE（真实 kubeadm）

```bash
cd /mnt/f/Agent/workbuddy/workspace/ShuqingBigDataPlatform

# 1) 宿主内核/网络栈/大页尽力调优 (WSL2 部分项受限会提示, 不影响起停)
sudo bash ske/ske.sh tune-host

# 2) 真实 kubeadm 拉起 SKE (单节点, 控制面兼跑负载)
sudo bash ske/ske.sh up --target wsl2 --profile local

# 3) 在 SKE 之上部署平台运行时 (封装层骨架 + MinIO 自动 in-cluster)
bash platform/bootstrap.sh --profile local

# 4) 跑端到端 PoC (数据非硬编码, cleanup 可清)
bash examples/run-demo.sh
```

> 注意：`ske.sh up/tune-host` 涉及写 `/etc/kubernetes`、`/etc/sysctl.d`、kubeadm 等，需要 root，**用 `sudo` 运行**。
> `platform/bootstrap.sh` / `examples/run-demo.sh` 只动 kubectl，普通用户（kubeconfig 在 `$HOME/.kube/config`）即可。

---

## 4. 验证与销毁

```bash
bash ske/ske.sh status                 # 看节点 + Cilium / tuning DaemonSet
kubectl get pods -A | grep -E "cilium|ske-node|minio"

bash ske/ske.sh down                   # kubeadm reset, 清空集群
```

---

## 5. 排错提示

- **Cilium 起不来 / Pod 网络不通**：WSL2 内核需 eBPF 支持（5.15+ 一般满足）。`cilium status` 排查；若环境太老，临时把 `ske/manifests/cilium-values.yaml` 的 `kubeProxyReplacement` 关掉并非 eBPF 模式（性能降级但不阻断功能）。
- **kubeadm preflight 报 br_netfilter / 端口**：`setup-host.sh` 已处理；若仍报，重跑 `sudo sysctl --system`。
- **控制面节点显示 NotReady**：等 Cilium 就绪（约 1~2 分钟）；`kubectl get pods -n kube-system` 看 `cilium` 是否 Running。
- **MinIO 连不上**：确认 `platform-ops` 命名空间下 `minio` Pod 与 `minio-init-bucket` Job 成功；端点为 `http://minio.platform-ops:9000`。
- **资源吃紧**：笔记本建议 ≥ 4 vCPU / 8GB 给 WSL2（Windows 的 `.wslconfig` 可限 `memory=8GB`、`processors=4`）。

---

## 6. 与 kind 模式对比

| 项 | kind（默认） | WSL2 真 kubeadm（本手册） |
| --- | --- | --- |
| 安装量 | 仅 Docker Desktop | 额外装 WSL2 Ubuntu + containerd/kubeadm |
| 集群本质 | kind 容器节点（共享宿主内核） | 真实 kubeadm 节点（systemd 托管 kubelet） |
| SKE 调优落点 | 节点镜像烘焙 + tuning DS 兜底 | kubeadm/kubelet/scheduler 配置真实生效 |
| 内核级调优 | 受限（容器共享内核） | 更接近生产（仍受 WSL2 内核限制） |
| 适用 | 快速看功能/demo | 验证 SKE 发行版深度定制 |
