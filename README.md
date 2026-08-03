# 数擎大数据平台 · ShuqingBigDataPlatform（项目统一根）

> 单一项目根：设计文档 + 自研 K8s 发行版（SKE）运行代码**全部收纳于此**，**单目录、不跨盘、不散乱**。
> 拼音：数擎 = shù qíng → **Shuqing**（SKE = Shuqing Kubernetes Engine），非 Shuqian。

## 目录结构

```
ShuqingBigDataPlatform/
├── design/                      # 设计 + 部署文档（纯文档 / 设计稿）
│   ├── 多平台多租户大数据平台_产品原型设计_v0.4.md   # 总体方案（五层 + 41 模块）
│   ├── 数擎大数据平台_控制台原型_v0.3.html           # 最新 UI 原型（小众线性 SVG 图标）
│   ├── 数擎大数据平台_控制台原型_v0.1.html           # 历史版本
│   ├── 详细设计/                                       # 13 份模块详细设计
│   └── deploy/                  # 部署设计态骨架（早期设计稿，含运营后台代码 services/operations）
├── ske/                         # 数擎云核 SKE 自研 K8s 发行版（见 ske/README.md）
├── platform/                    # 平台引导 bootstrap.sh + in-cluster MinIO
├── examples/                    # 端到端 PoC（运行态，seed/cleanup，非硬编码）
└── docs/                        # SKE 设计文档索引（即 docs/README.md）
```

## 运行态 vs 设计态
- `ske/ platform/ examples/` 是**真正可拉起**的运行实现（SKE 发行版 + 平台引导 + PoC）。
- `design/deploy/` 是**设计态骨架**（早期部署设计稿，与运行态并行演进，不作为实际部署入口）。

## 快速开始（在你笔记本执行；沙箱内 Docker/WSL2 不可达）
详见 `ske/WSL2-QUICKSTART.md`。WSL2 真 kubeadm 路：
```bash
cd /mnt/f/Agent/workbuddy/workspace/ShuqingBigDataPlatform
sudo bash ske/wsl2/setup-host.sh
sudo bash ske/ske.sh tune-host
sudo bash ske/ske.sh up --target wsl2 --profile local
bash platform/bootstrap.sh --profile local
bash examples/run-demo.sh
```

## 关键约束
- K8s 一律自建，禁用云托管（ACK/EKS/TKE/CCE）；SKE 是深度定制封装的自研发行版，非 kubeadm/k3s/kind 原样。
- 验证环境：笔记本 x86_64 + Docker Desktop + kubectl；无信创机器（信创 Profile 仅交付用）。
