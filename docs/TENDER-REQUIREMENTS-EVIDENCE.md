# 招标原文需求侧依据（公开公告摘录）

> 版本 v1.2 · 2026-11-18 · MCode（主理人「再检索试试」令；v1.1 起为三轮当轮实测口径订正）
> **定位**：本件是**需求侧**口径的依据来源（招标方到底要什么），与 `docs/TENDER-CAPABILITY-MAP.md`（**能力侧**口径：我们有什么、缺什么）**分属两册**。能力侧的成熟度判定与缺口编号**不在本件重复**，本件只登记**原文摘录**与**逐条对照**。
>
> **取证方式（当轮实测，如实登记）**：`web_fetch` 直取公告页被反爬拦截（`ztjt.zgdlyzc.com` 返回 JS 混淆壳、`zbtb.com.cn` 返回 `arg1` 加密壳）⇒ 改用 `web_search` 取回**页面 content 正文**，并对同一份公告用**多个镜像站交叉核对同一段落**。抓取时间：2026-11-17（UTC，当轮）。
>
> **边界（重要）**：① 本件登记的是**公开招标公告原文摘录**，**公开公告 ≠ 合同条款**——真正的技术要求以招标文件正文为准；② 本件**不构成对任何标的的应答承诺**；③ 镜像页可能有编辑删改，**以招标人／官方交易平台发布的原文为准**；④ 本件**未做逐条验收**：摘录只是把「需求侧口径」摆到台面上，能否满足由能力侧逐条判定。

---

## 1. 摘录 A｜济南设计院「智能化管理平台云原生底座开发项目」

| 项 | 值 |
|---|---|
| 招标编号 | `ZTSJ-JNY-2025-10` |
| 招标人 | 中铁工程设计咨询集团有限公司济南设计院 |
| 公告日期 | 2025-08-14（镜像站标注；招标文件获取窗口 2025-08-14 ~ 2025-08-20） |
| 来源（双镜像交叉核对） | <https://ztjt.zgdlyzc.com/ywgg/1/1/966246.html>（电力能源招标网）<br><https://www.zbytb.com/s-zb-59781797.html>（招标与采购网） |

### 原文摘录

> 「为践行铁路建设高质量发展的要求，需整合**分布式数据库、容器云运维平台、湖仓一体化平台**新技术，把标准化作为依托、数字化作为途径，让智能化监管渗透到铁路工程勘察设计、施工、运营的整个生命周期。……最后**基于全栈信创基础设施建设湖仓一体大数据平台底座，面向离线、实时等大数据场景提供开箱即用的存储、实时、离线等全流程的算力资源，支持多租户管理，提供一站式大数据存储与计算能力**。」
>
> 「2.2 招标内容：本次招标项目包含**国产分布式数据库、容器云运维平台、湖仓一体化平台**等内容，主要包括**云平台运维、大数据工具开发、系统及数据集成、部署安装、测试调优、培训、试运行、验收、运维**等内容。」
>
> 「3.4 交付周期要求：合同生效后，中标人应在 **30 个工作日**内完成全部服务内容的交付、部署及调试，并达到验收标准。」

### 需求侧口径拆解（只拆条款，不下能力结论）

| 招标原文用语 | 对应本仓口径轴 | 备注 |
|---|---|---|
| 湖仓一体大数据平台底座 | **湖仓** | 与本仓湖仓轴同义 |
| 面向**离线、实时**等大数据场景 | **批** + **流**（并列） | 与本仓「流与批分开」口径一致：招标方也把离线与实时**并列为两类场景** |
| 存储、实时、离线等**全流程算力资源** | 云原生底座 + 湖仓 + 流 + 批 | 「全流程」意味着只给存储或只给批都不满足 |
| **开箱即用** | 交付形态 | 指向预置镜像 / chart / 一键部署，而非自研集成 |
| **支持多租户管理** | 多租户 | 招标方明写的要求 |
| 一站式大数据**存储与计算**能力 | 湖仓 + 计算引擎 | 存储与计算都要 |
| 合同生效后 **30 个工作日**内交付部署调试 | 交付周期 | 硬时限口径 |
| 国产分布式数据库 / 容器云运维平台 / 湖仓一体化平台**三件套** | 三类交付物并列 | 招标方把「数据库 + 容器云运维 + 湖仓」当一个整体采购 |

---

## 2. 摘录 B｜济南设计院姊妹标「铁路工程智能化软件平台开发项目」

| 项 | 值 |
|---|---|
| 招标编号 | `ZTSJ-JNY-2025-19` |
| 招标人 | 中铁工程设计咨询集团有限公司济南设计院（与摘录 A 同一发标人） |
| 来源 | <https://ztjt.zgdlyzc.com/ywgg/1/1/1803286.html>（电力能源招标网） |

### 原文摘录

> 「本次项目计划依托 **PaaS 平台**的强大技术底座（**云原生架构集成能力/微服务治理能力**）实现资源的弹性调度与系统敏捷部署；基于**低代码开发平台**的拖拽式设计，实现业务人员快速搭建审批流、施工追踪等场景应用；通过**研发管理平台**标准化开发流程与数据接口，确保各业务系统的协同开发与版本控制；通过**数据库 SQL 审核平台**对工程数据库进行实时血缘管理与风险扫描，从源头保障计量支付、质量验评等核心数据的准确性与合规性；**依托大数据开发平台整合多源数据，实现数据的集中管控与分类应用**；利用 **AI 大模型管理平台**驱动智能决策，实现从被动响应到主动干预的管理模式跃迁。」
>
> 「2.2 招标内容：本次招标项目包含 PaaS 平台、低代码开发平台、研发管理平台、数据库 SQL 审核平台、**大数据开发平台**、AI 大模型管理平台共**六个底座类平台**。」
>
> 「3.4 交付周期要求：合同生效后，中标人应在 **30 个工作日**内完成全部服务内容的交付、部署及调试，并达到验收标准。」

### 需求侧口径拆解

- **「大数据开发平台」是六个底座类平台之一**，与 PaaS、低代码、研发管理、SQL 审核、AI 大模型**并列**⇒ 招标视角里大数据平台**不是单一组件，而是与研发效能、数据治理并列的一整个平台位**。
- 「**集中管控与分类应用**」⇒ 数据侧的**分层与集中管控**被写进招标条款（对应本仓能力侧「租户侧数据分层不是产品能力」那条缺口）。
- 「依托 PaaS 平台的云原生架构集成能力 / 微服务治理能力」⇒ **云原生底座是其余五个平台的地基**，不是并列第七项。
- 交付周期口径与摘录 A 一致：**30 个工作日**。

---

## 3. 摘录 C｜中船动力「云原生开发底座」（七层技术参数）

| 项 | 值 |
|---|---|
| 招标编号 | `CSSC-03-261003-018` |
| 招标人 | 中船动力（集团）有限公司 |
| 标的 | 设备名称「云原生开发底座」，数量 **1 套** |
| 交货期 | 合同签订后 **60 个自然日**内（公告 2.5 条） |
| 来源（三镜像交叉核对） | <https://csscbidding.com/jyxx/003001/003001001/20260209/ca187b66-b22a-4b53-8bed-ba040fa19d5c.html>（中国船舶电子招标投标平台＝官方发布媒介）<br><http://www.zbtb.com.cn/zb/63938986/>（采购与招标网）<br><https://bj.zhiliaobiaoxun.com/article/90990049>（知了标讯） |

### 原文摘录（七层逐条）

> 「规格尺寸或主要技术参数：
>
> 1. **基础设施层**：需兼容主流云平台（如阿里云 ACK、腾讯云 TKE、亚马逊、华为云），支持多云/混合云管理，具备 IaaS 层资源调度能力，适配 x86、ARM 及国产化硬件架构，满足 TB 级数据实时访问需求。
> 2. **容器编排层**：基于 Kubernetes 构建，支持多集群管理、联邦集群及 CRI-O 容器运行时，版本需兼容主流发行版，并具备节点自愈、批量操作等自动化运维能力。
> 3. **服务治理层**：集成 Istio 等服务网格，实现服务间通信加密、流量管控及混沌工程演练；支持 SpringCloud 微服务框架，具备弹性伸缩（HPA/VPA）、熔断降级及多租户隔离能力。
> 4. **DevOps 工具链**：内置 GitLab、Jenkins 等工具，支持 GitOps 自动化部署及容器镜像安全扫描；需提供金丝雀发布、蓝绿部署等灰度发布策略。
> 5. **监控与日志**：集成 Prometheus+Grafana 实现资源监控，结合 Jaeger/OpenTelemetry 进行分布式追踪；采用 EFK 或 Loki 栈实现日志聚合与审计。
> 6. **网络与存储**：实现网络策略控制，支持文件存储、对象存储等主流存储方案。
> 7. **安全与合规**：实施 RBAC 权限管理、多因素认证，集成 WAF、漏洞扫描工具及容器运行时安全组件。」

### 需求侧口径拆解（逐层对照本仓在册事实）

本仓现状为**当轮实测**（`origin/main @ d850fc77`，2026-11-18 当轮重测，命令见 §4）：

| 招标条款层 | 本仓在册事实（当轮实测） | 差距口径（只陈述事实，不下承诺） |
|---|---|---|
| ① 基础设施层：多云/混合云、x86/ARM/国产化、TB 级实时访问 | 本仓为**单集群**形态（k3s / K8s）；**无 ARM / 国产化硬件实测**；「TB 级」宽口径全仓**仅 1 行**命中，且是**设计稿里的能力宣称**（`design/v2.0/详细设计/V2.0_数据联邦与实时数仓详细设计.md:99`「大表跨源 Join 不再 OOM，TB 级可跑」），**不是实测值**；严口径关键词「TB 级实时」全仓 **0 命中** | 多云、国产化、TB 级三项均**无实测**（TB 级连设计稿都只写了一句宣称，仓内无任何 TB 级吞吐的实测值）；性能损耗台账 L0 档数字**不得**外推 |
| ② 容器编排层：**多集群管理、联邦集群**、CRI-O | **代码与 chart 层在册**：`karmada-api` **13 文件**、`failover-api` **13**、`failover-engine` **12**、`federated-query` **13**（README：「跨集群查询路由与归并 - federated SQL 下推与结果归并」，端口 **8094**）；`platform/karmada/` **139 文件**。**部署面**：`dataenginebdp-umbrella` 依赖里这四服务 **0 命中**；ArgoCD `applications.yaml`（1904 行 / 39 个 `kind: Application`）里 karmada **0 行**（karmada-api 的 Application 已摘除），failover-api / failover-engine / federated-query 在 dev/staging/prod 各 1 条共 **9 条**、**全部 automated + selfHeal + prune + sync-wave 2**；IT compose `karmada-extra` profile 四个服务在 `.github/workflows/ci.yml:2346/2359` 的 Integration Test 里真跑。**上游 Karmada 控制面未部署**（台账 #90 裁决「四服务一律冻结在原地（不迁不删）」，不引入控制面） | 招标明写「联邦集群」：**代码 / chart / 应用清单三处都在册，缺的是上游 Karmada 联邦控制面这一部署面** ⇒ **控制面部署面缺口**，**不是「能力已裁撤」**（台账 #90 本行为 CLOSED，冻结保留） |
| ③ 服务治理层：**Istio 服务网格**、SpringCloud、HPA/VPA、多租户隔离 | **Istio 配置面与安装脚本在册**：`ske/k3s/install-istio.sh`（190 行）、`ske/k3s/istio-operator-minimal.yaml`（65 行）、`ske/k3s/namespace-istio-system.yaml`（16 行）、`ske/k3s/peer-authentication-mtls.yaml`、`ske/k3s/destination-rule-mtls.yaml`、`ske/k3s/sample-mesh-test-{deploy,sa,svc}.yaml`、`design/deploy/istio/traffic-policies.yaml`（840 行，含命名空间级 PeerAuthentication **mTLS STRICT** / DestinationRule / VirtualService）；`charts/argo-rollouts` 引用 `networking.istio.io/v1beta1`。但 charts 顶层**无 `istio` chart**（目录名零命中），**不在 Helm 一键部署面**；`cni-cilium` 在册（14 文件）；`platform/` 下 25 个 pom.xml **全部**含 spring-boot | Istio **配置与脚本在册、Helm 一键部署面缺位** ⇒ **可部署面缺口**（不是「能力不存在」）；HPA/VPA 与多租户隔离的成熟度见能力侧表 |
| ④ DevOps：GitOps、镜像安全扫描、**金丝雀/蓝绿** | charts 含 `argo-rollouts`（金丝雀/蓝绿）、`external-secrets`；仓内 CI 含镜像扫描与 SBOM 腿 | 该层**在册**，但成熟度判定见能力侧表 |
| ⑤ 监控与日志：Prometheus+Grafana、Jaeger/OpenTelemetry、EFK 或 Loki | charts 含 `prometheus`(18 文件) / `grafana`(17 文件) / `loki`(13 文件) / `tempo`(13 文件)；**应用侧 OTel SDK 已接入**：Go `platform/catalog/internal/middleware/tracing.go`（OTel SDK + otlptracegrpc exporter，16 行），Java `platform/encaps-layer/pom.xml`（`opentelemetry-api` / `opentelemetry-sdk`，12 行，另 `rule-engine` / `sql-gateway` 等模块 pom 同）；**测试栈有 Jaeger**：`tests/integration/docker-compose.yml:1022-1028`（`jaegertracing/all-in-one:1.54`，UI 16686）。但**采集侧无部署面**：charts 顶层无 `otel` / `opentelemetry` / `jaeger` 目录（路径名零命中），`design/deploy/` 内 `otel-collector` / `opentelemetry-collector` **0 命中** | 监控与日志栈**在册**、**应用侧追踪 SDK 在册**；缺口是**采集面（OTel collector）与 Jaeger 后端无 chart / 无生产部署面**（Jaeger 仅存在于集成测试栈）⇒ **采集面部署缺口**；指标契约覆盖度见能力侧表 |
| ⑥ 网络与存储：网络策略、文件/对象存储 | charts 含 `cni-cilium` 等 CNI；存储侧在册 | 在册 |
| ⑦ 安全与合规：RBAC、**多因素认证**、WAF、漏洞扫描、容器运行时安全 | 仓内 CI 有 `Trivy` / `Semgrep` / `CodeQL` / `Gitleaks` 等扫描腿。**MFA**：关键词全仓 **40 行 / 20 文件**，其中 design/ 设计稿 31 行；非 design 的 9 行里真命中仅 2 处——`docs/compliance/dengbao-assessment-report.md:137`（合规报告**声明**「Keycloak + JWT 多因素认证」）与 `platform/industry-templates/templates/finance/rbac/README.md:253`（行业模板 RBAC 说明），其余 7 处为子串误匹配（`upstreamFailure` 里的 `mfa`、`plan%2Fa` 里的 `2fa`、go.sum 与 package-lock 的 base64 摘要）；`charts/keycloak/` 内 3 行「命中」全是 `IfNotPresent` 的 `otp` 子串 ⇒ **可部署面零 MFA 配置**。**WAF**：**37 行 / 15 文件**，design/ 31 行；非 design 的 6 行里真命中 3 处——`dengbao-assessment-report.md:116` 与 `:277`（合规报告**声明**「APISIX 网关部署 WAF 模块」与术语表）、`tests/security/api-security-tests/injection-tests.test.js:9`（测试**注释**）；charts 内 WAF **0 命中** ⇒ **可部署面零 WAF** | 扫描侧在册；**MFA / WAF 只有文档声明与测试注释，无任何可部署面配置** ⇒ **可部署面缺口**（不是「未核实」，是本轮已实测到零） |

---

## 4. 当轮实测口径与复算命令

> **取证口径声明（v1.2 补）**：本节所有数字均于 **2026-11-18** 在 `origin/main @ d850fc77` 上**当轮重测**。其中「**路径名**」一律用 `git ls-tree -r --name-only`（权威口径），「**内容**」一律用 `git grep`（`git grep` 匹配的是文件**内容**、不是路径名——用它做路径名检索会得出假零命中）。

```powershell
# 事实源基线
git -C <worktree> rev-parse origin/main          # d850fc777dc40daf5d447841fc0e927dc6006622

# charts 顶层：107 个顶层条目，其中 4 个是脚本文件 ⇒ 真实 chart 目录 = 103，全部含 Chart.yaml
git -C <worktree> ls-tree -r --name-only origin/main design/deploy/charts/
#   ⇒ 顶层条目 107 个，其中 4 个是脚本文件（不是 chart 目录）：
#      _apply_security_templates.py / _generate_charts.py / _validate_charts.py / _validate_security_charts.ps1
#      ⇒ 「顶层 107 个 chart 目录」是把这 4 个脚本文件也算成目录的旧计数口径；
#         真实 chart 目录 = 103，全部含 Chart.yaml（与 docs/TENDER-CAPABILITY-MAP.md §1 所记 103/103 一致）

# §3 表内引用的 chart 名与文件数逐条复测（ls-tree 路径名口径）
#   istio = 不在册（0 目录）；karmada = 不在册（0 文件）；karmada-api = 13；failover-api = 13
#   federated-query = 13；failover-engine = 12；prometheus = 18；grafana = 17；loki = 13
#   tempo = 13；keycloak = 11；cni-cilium = 14；argo-rollouts = 17；external-secrets = 13

# ② 多集群联邦的三个部署面（口径：各自所在载体，不做外推）
git -C <worktree> grep -c -i -E 'karmada|failover-api|failover-engine|federated-query' origin/main -- design/deploy/charts/dataenginebdp-umbrella
#   ⇒ 0 行 ⇒ umbrella 默认安装面无这四个服务
git -C <worktree> grep -n -i -E 'karmada|failover|federated' origin/main -- design/deploy/argocd/applications.yaml
#   ⇒ 文件 1904 行 / 39 个 kind: Application；其中 karmada 0 行（karmada-api 的 Application 已摘除），
#      failover-api / failover-engine / federated-query 在 dev/staging/prod 各 1 条、共 9 条，
#      且这 9 条全部 automated + selfHeal + prune + sync-wave 2
git -C <worktree> ls-tree -r --name-only origin/main platform/karmada
#   ⇒ 139 个文件（代码侧骨架 + 独立的 argocd-application.yaml），但上游 Karmada 控制面未部署
#      （台账 #90 裁决：四服务一律冻结在原地（不迁不删），不引入控制面）

# ③ Istio：配置与脚本在册，但 charts 顶层无 istio chart
git -C <worktree> ls-tree -r --name-only origin/main | grep -i 'istio'
#   ⇒ design/deploy/istio/traffic-policies.yaml（840 行）
#      ske/k3s/install-istio.sh（190 行）、ske/k3s/istio-operator-minimal.yaml（65 行）
#      ske/k3s/namespace-istio-system.yaml（16 行）、ske/k3s/peer-authentication-mtls.yaml
#      ske/k3s/destination-rule-mtls.yaml、ske/k3s/sample-mesh-test-{deploy,sa,svc}.yaml
git -C <worktree> grep -n 'networking.istio.io' origin/main -- design/deploy/charts/argo-rollouts
#   ⇒ README.md:86 apiVersion、templates/rbac.yaml:29 apiGroups ⇒ argo-rollouts 依赖 Istio API

# ⑤ 追踪采集面：路径名零命中 ≠ 内容零命中（双口径必须分开取数）
git -C <worktree> ls-tree -r --name-only origin/main | grep -iE 'opentelemetry|otel|jaeger'
#   ⇒ 路径名 0 命中 ⇒ 无 otel-collector / jaeger chart 目录
git -C <worktree> grep -c -i -E 'opentelemetry|otel|jaeger' origin/main
#   ⇒ 内容命中 57 文件 / 222 行 ⇒ 应用侧 OTel SDK 已接入
git -C <worktree> grep -n 'jaegertracing' origin/main -- tests/integration/docker-compose.yml
#   ⇒ :1025 image: jaegertracing/all-in-one:1.54（:1022-1028 整块；UI 16686）
git -C <worktree> grep -c -E 'otel-collector|opentelemetry-collector' origin/main -- design/deploy
#   ⇒ 0 ⇒ 采集侧无部署面

# ⑦ MFA / WAF：关键词命中必须回看命中行本身（子串误匹配高发）
git -C <worktree> grep -n -i -E 'mfa|2fa|totp|webauthn|multi-factor|多因素|双因子' origin/main
#   ⇒ 40 行 / 20 文件：design/ 31 行；非 design 的 9 行里真命中仅 2 处
#      （docs/compliance/dengbao-assessment-report.md:137 合规报告声明；
#        platform/industry-templates/templates/finance/rbac/README.md:253 行业模板说明），
#      其余 7 处是子串误匹配：upstreamFailure 含 mfa、plan%2Fa 含 2fa、go.sum 与 package-lock 的 base64 摘要
git -C <worktree> grep -n -i 'otp' origin/main -- design/deploy/charts/keycloak
#   ⇒ 3 行全是 pullPolicy: IfNotPresent 的 otp 子串 ⇒ 可部署面零 MFA 配置
git -C <worktree> grep -n -i -E 'waf|modsecurity' origin/main -- design/deploy/charts
#   ⇒ 0 行 ⇒ 可部署面零 WAF
git -C <worktree> grep -n -i -E 'waf|modsecurity|web application firewall' origin/main
#   ⇒ 37 行 / 15 文件：design/ 31 行；非 design 的 6 行里真命中 3 处
#      （dengbao-assessment-report.md:116 与 :277 合规报告声明；
#        tests/security/api-security-tests/injection-tests.test.js:9 测试注释），
#      其余 3 处是 package-lock 与 go.sum 摘要里的子串误匹配

# ① TB 级：宽口径 1 行（设计稿宣称），严口径 0 行
git -C <worktree> grep -n -E 'TB/s|TBps|terabit|TB级|TB 级' origin/main
#   ⇒ 1 行：design/v2.0/详细设计/V2.0_数据联邦与实时数仓详细设计.md:99
#      「大表跨源 Join 不再 OOM，TB 级可跑」⇒ 设计稿宣称，不是实测值
git -C <worktree> grep -c -E 'TB/s|TBps|terabit|每秒\s*TB|TB\s*级实时|TB级实时' origin/main
#   ⇒ 0 ⇒ 仓内无「TB 级实时」吞吐的实测值或声明

# platform/ 一级模块数 = 41
git -C <worktree> ls-tree -r --name-only origin/main platform/

# 「数据分层」命中面 = 7 处 / 5 文件（platform/ + frontend/）
git -C <worktree> grep -c "数据分层" origin/main -- platform/ frontend/
#   frontend/src/api/develop.ts:1
#   frontend/src/api/governance.ts:1
#   frontend/src/i18n/locales/modules/develop.zh-CN.json:1
#   platform/encaps-layer/.../controller/DevelopController.java:3
#   platform/industry-templates/industry_templates/models/template.py:1
```

---

## 5. 需求侧口径 ↔ 本仓五轴（速查）

| 招标侧诉求（原文出处） | 本仓对应轴 | 能力侧判定看哪一册 |
|---|---|---|
| 湖仓一体大数据平台底座（摘录 A） | 湖仓 | `docs/TENDER-CAPABILITY-MAP.md` §1 湖仓三行 |
| 面向离线、实时等大数据场景（摘录 A） | 批 + 流（分开） | 同上「流数据处理」/「批数据处理」两组行 + `docs/STREAM-BATCH-DECISION-TABLE.md` |
| 存储、实时、离线**全流程算力资源**、开箱即用（摘录 A） | 云原生底座 | 能力侧 §1「云原生底座」组 |
| 支持**多租户管理**（摘录 A、摘录 C 第 3 层） | 多租户 | 能力侧 §1 + 台账 |
| 六大底座类平台并列，**大数据开发平台**是其一（摘录 B） | 全轴 | 能力侧 §1 全表 |
| **集中管控与分类应用**（摘录 B） | 数据分层 | 能力侧 §1「数据分层」组 + 台账「租户侧数据分层」条 |
| 七层技术参数（摘录 C） | 云原生底座 | 能力侧 §1「云原生底座」组 + 台账 |
| 交付周期 30 个工作日 / 60 个自然日（摘录 A/B/C） | 交付面 | 能力侧 §1「可部署单元」相关行 + 交付面台账条 |

---

## 6. 后续可做（未做，不承诺）

- ① 逐条把 §3 的七层做成**可勾选的应答矩阵**（条款 ↔ 仓内落点 ↔ 成熟度），并把「多集群联邦」「Istio」「MFA/WAF」「TB 级实时」「追踪采集面」五项**部署面 / 可部署面缺口**显式登记进台账（**本轮未登记**：须先裁编号归属——当轮实测台账编号已连续至 **#127**（PR #809 登记并合并，merge `44fd538f`），下一个空闲号为 **#128**；在途 lane 若同时声明新号需协调）。
- ② 抓取**招标文件正文**（PDF）而不是公告页——公告只给大纲，技术细则在第五章「供货要求」与附件三里（摘录 C 原文已写「详见第五章『供货要求』」）。
- ③ 摘录 A/B 的**同一发标人**是否还有第三个「大数据」类标（同类标的横向对比有助于判断真实需求重心）。
- ④ 把 §1 的条款原句做**逐句对标**，与能力侧 §1 的每一行建立引用关系（当前只到速查表粒度）。
