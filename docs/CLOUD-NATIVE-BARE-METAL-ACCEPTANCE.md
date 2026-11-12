# 云原生底座 · 裸金属带外管理与自动化装机验收清单

> **口径**：本文只登记**可执行判据、取证方式与未闭环边界**。
>
> **本机无 BMC / 无裸金属**（本机为笔记本，`Win32_IPMIConnection` 类不存在 ⇒ 无 IPMI/BMC）⇒ 文内命令**未在真实硬件上执行过**，属**待执行资产**，不是已通过的验收报告。
>
> 对应台账 **#121** 的硬件面；L0 底座档已由 **#124** 闭环（见 `docs/CLOUD-NATIVE-PERF-LOSS-BASELINE.md` §6.4）。

## 一、约束在哪：哪条验收条款必须 BMC

台账 #121 处置列原文是「一台 x86_64 真机 + BMC/PXE（主理人侧待办）**或**可复现的 CI 集群」。这行把**两件不同的事**捆在了一起：**带外管理**与**自动化装机**。拆开后约束位置如下：

| 验收条款 | 是否必须带外 BMC | 免 BMC 路线（③/④）能否满足 | 对应判据 |
|---|---|---|---|
| 远程开关机 / 远程重启（带外） | 必须 | 不能（无 BMC 即无远程电源控制） | C2 + `manual_power_commands` |
| 带外读硬件状态（温度 / 风扇 / 电源） | 必须 | 不能 | C4 |
| 硬件身份与序列号权威取证 | 建议（BMC 侧权威） | 不能（BMC 侧无此信息） | C3 |
| 带外网与业务网分离 | 必须 | 不能 | C5 |
| 「一台 x86_64 真机」这一形态本身 | 不必然（BMC ≠ 真机） | 需一台可远程装机的 x86_64 物理机 | C6（OS 侧自报架构） |
| 可重复 / 自动化装机 | 不必须 | **能** | `serve-pxe.sh` + `render_user_data.py` |

**结论（约束在哪）**：带外管理类条款**只能**由「云上裸金属 / BMS」或「有 IPMI 的实体机」满足；自动化装机类条款可由 PXE-over-HTTP + iPXE + cloud-init 满足，**不需要 BMC**。若验收条款只要求「一台 x86_64 真机 + 可重复装机」，则 ③/④ 加一台可远程装机的物理机即可闭环，**不必等 BMC**。

## 二、四条候选路径 × 条款映射

| 路径 | 带外管理 | 自动化装机 | 硬判据 / 风险 |
|---|---|---|---|
| ① 云上裸金属 / BMS 实例 | 取决于厂商是否暴露标准 IPMI / redfish，**须按验收条款确认** | 支持（云厂商自带重装） | 厂商 BMC 多为虚拟控制台；若验收要标准 IPMI API，需在实例内自建 BMC 补齐 |
| ② 有 IPMI 的实体机 / 闲置服务器 | **能**（唯一直接满足标准 IPMI 验收的路径） | 支持 | 须确认 BMC 固件版本与 PXE 引导支持 |
| ③ PXE-over-HTTP + iPXE + cloud-init | **不能** | **能** | 只解决装机，不解决带外管理 |
| ④ Ansible / cloud-init 装机 | **不能** | **能** | 同 ③，不解决带外管理 |

## 三、硬判据 C1–C6

判据由 `scripts/baremetal/ipmi_acceptance.py` 实现，**三态**：PASS / FAIL / INCONCLUSIVE。拿不到结论算 **INCONCLUSIVE**，**不算通过**（脚本退出码 3）。

| 判据 | 含义 | 判红条件 | 取证命令 |
|---|---|---|---|
| C1 | 带外管理通道可达 | 探测过但 Redfish 非 200 且 `ipmitool chassis info` 非 0 | `ipmitool -I lanplus -H <host> -U <user> -P <pass> chassis info` |
| C2 | 电源状态可读 | 探测过但拿不到电源状态 | `ipmitool -I lanplus -H <host> -U <user> -P <pass> chassis power status` |
| C3 | 机箱身份可读 | 序列号 / 型号为空 | `ipmitool -I lanplus -H <host> -U <user> -P <pass> chassis info` |
| C4 | 传感器 / 温度可读 | 探测过但读数为 0 | `ipmitool -I lanplus -H <host> -U <user> -P <pass> sensor list` |
| C5 | 带外网络参数可读 | 探测过但拿不到 IP / 管理口信息 | `ipmitool -I lanplus -H <host> -U <user> -P <pass> lan print` |
| C6 | CPU 架构证据 | ——（BMC 侧**不提供**架构，只能 INCONCLUSIVE，除非传入 OS 侧自报值） | 目标机 `uname -m`，用 `--os-arch` 传入 |

**写操作不自动执行**：远程关机 / 重启属写操作，脚本只打印命令（证据 JSON 的 `manual_power_commands` 字段），人工确认后执行，避免误动生产机。

## 四、证据留存格式

`ipmi_acceptance.py --out <file>` 产出 JSON，字段如下：

| 字段 | 内容 |
|---|---|
| `generated_at_utc` | 取证时间（UTC） |
| `target` | 目标地址与 OS 侧自报架构 |
| `overall` | 总判据（PASS / FAIL / INCONCLUSIVE） |
| `criteria` | 六条判据逐条：ID、标题、判据、依据 |
| `raw.redfish` | 各端点 HTTP 状态码与响应片段 |
| `raw.ipmi` | `ipmitool` 逐条命令的 rc / stdout / stderr |
| `manual_power_commands` | 供人工执行的写操作命令 |

**密码不落证据**：脚本在写盘前把密码字面量从所有文本中抹掉（替换为 `***`），命令与响应片段里都不留明文。

**归档约定**：证据文件按 `evidence/bmc-<目标名>-<UTC日期>.json` 存放，**不进版本库**。

## 五、执行顺序

1. **选路**：按第二节矩阵确定用 ① / ② / ③ / ④。若要过带外管理条款，直接走 ① 或 ②。
2. **准备带外**：BMC 装好、放通带外网、确认厂商是否暴露标准 IPMI / redfish（这一条**未验证**，须按验收条款逐条确认）。
3. **取证**：跑 `ipmi_acceptance.py`，退出码 0 才算通过；2 = 有 FAIL；3 = 没结论。
4. **装机**：路径 ③/④ 按 `scripts/baremetal/README.md` 的「渲染 → 自检 → 起服务」三步走。
5. **取证留档**：把 JSON 证据与本文件一起归档，并在台账 #121 行尾追加复核段。

## 六、未闭环项与盲区

- **本机硬件不可达**：本机是笔记本，无 IPMI / BMC ⇒ 本文的 C1–C5 命令**未在硬件上执行过**。
- **本机工具缺失**：`ipmitool`、`ansible`、`cloud-init` 均不在本机 PATH ⇒ `ipmi_acceptance.py` 的 IPMI 腿在本机必然 INCONCLUSIVE（脚本会把「未找到 ipmitool」写进证据，不静默放行）。
- **iPXE 未在真实网络验证**：`boot.ipxe` / `boot.ipxe.cfg` 是人工审读资产；内核与 initrd 由运维从选定发行版的 netboot 镜像放入，仓内不提供这两个二进制，也不写死下载 URL（写死 URL 等于埋一个将来 404 的雷）。
- **厂商 BMC 暴露面未验证**：① 路径「是否暴露标准 IPMI / redfish」需按验收条款逐条确认，本文件**不下结论**。
- **数字不外推**：本文不含任何性能数字；L0 底座档数字**不得**外推为 L1/L2/L3 生产档基线（口径见台账 #124）。

## 七、与台账的对应关系

| 台账编号 | 关系 |
|---|---|
| #121 | 本清单对应其处置列的**硬件面**（真机 + BMC/PXE）；「或可复现的 CI 集群」那半条已由 #124 在 L0 底座档兑现 |
| #124 | L0 底座档（WSL2 + k3s）实测落笔；本文不重复其数字，也不外推 |
| #102 | CI 吞吐口径，与本文无耦合 |
