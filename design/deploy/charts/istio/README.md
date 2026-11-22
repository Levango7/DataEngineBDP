# istio chart

台账 **#129**「charts 顶层无 istio chart」的部署面落地形态。

## 形态

| 项 | 内容 |
| --- | --- |
| chart | `design/deploy/charts/istio/`（name `istio`，appVersion `1.22.3`） |
| 上游 | Istio 官方 helm chart `base` / `istiod` / `gateway` **1.22.3**，vendored 于 `charts/` |
| 上游来源 | `helm repo add istio https://istio-release.storage.googleapis.com/charts` → `helm pull istio/{base,istiod,gateway} --version 1.22.3` |
| 版本锚点 | 与仓库既有 `ske/k3s/install-istio.sh:70`（`ISTIO_VERSION:-1.22.3`）对齐，避免 chart 路径与 operator 路径版本漂移 |
| 承载策略 | `files/traffic-policies.yaml` = 仓库既有 `design/deploy/istio/traffic-policies.yaml` 的去 BOM 副本，由 `templates/traffic-policies.yaml` 经 `.Files.Get` 渲染 |
| 镜像坐标 | `docker.m.daocloud.io/istio/pilot:1.22.3`、`docker.m.daocloud.io/istio/proxyv2:1.22.3`（sidecar 运行期按 `global.hub`/`global.proxy.image`/`global.tag` 组装） |

上游 `base`/`istiod`/`gateway` 三个 chart 在 1.22.3 **均无 dependencies**，因此可被
`scripts/chart-render-check.sh` / `scripts/lint-all-charts.sh` 的递归 `find` 单独渲染与 lint。

## 安装

```bash
helm install istio design/deploy/charts/istio \
  --namespace istio-system --create-namespace
```

只装控制面、策略另行 `kubectl apply` 时：

```bash
helm install istio design/deploy/charts/istio \
  --namespace istio-system --create-namespace \
  --set trafficPolicies.enabled=false
```

## 镜像坐标口径

Istio 1.22.3 上游 `global.hub` 默认 `docker.io/istio`（`charts/istiod/values.yaml:235`），
本仓统一改写为 `docker.m.daocloud.io/istio`。覆盖写两处：

- `global.hub` / `global.tag` —— 父 chart 口径；
- `istiod.global.hub` / `istiod.pilot.hub` —— 子 chart 口径（父 chart values 对子 chart
  `values.yaml` 具高优先级，该覆盖必然生效，不依赖 Helm 的 global 传播行为）。

`templates/image-anchor-check.yaml` 在渲染期断言：CHARTS 表锚点 `image` 块 == `istiod.pilot`
实际组装出的坐标，且父/子 `global` 宿主与 tag 一致；任一不符即 `fail` ⇒ 不会出现
「漂移门禁全绿但实际拉不到镜像」。

`gateway` 子 chart 渲染出的容器镜像是字面量 `auto`（上游设计，由注入 webhook 运行期填充），
**不产生具体坐标**，故不纳入镜像坐标探针。

## 边界与未验证

- **未验证**：本机无 Istio 集群，本 chart 未做真机安装与 sidecar 注入验证；
  渲染层证据为 `helm lint` / `helm template`（rc=0）。
- **未验证**：镜像 tag 是否在 `docker.m.daocloud.io` 实际存在——本机 registry API 不可达
  （401/超时），由一次性探针 workflow（`.github/workflows/image-coords-probe.yml`）在
  GitHub Actions runner 上判定。
- **非工作负载坐标**：`busybox:1.28` 出现在 `istio-sidecar-injector` ConfigMap 内嵌的
  `grpc-simple` mesh profile 示例中，不是被部署的工作负载镜像；探针仍覆盖它以留证。
- **CRD**：Istio CRD 由 `base` 子 chart 的 `crds/` 提供，`helm install` 自动 apply，
  `helm template` 默认不含（需 `--include-crds`）。
- **副本一致性**：`files/traffic-policies.yaml` 与 `design/deploy/istio/traffic-policies.yaml`
  的逐字节一致性由 `scripts/check-deploy-surface-assets.py` 守护（副本去 UTF-8 BOM，
  其余完全相同，行尾 LF）。

## 不并入 umbrella 的理由

`dataenginebdp-umbrella` 是默认安装面；Service Mesh 控制面属**显式 opt-in** 的部署面，
并入会把 Mesh 控制面变成默认安装路径。故本 chart 独立存在，不进 umbrella。
