# 裸金属带外管理 / 自动化装机资产（台账 #121 硬件面）

本目录是台账 **#121**「云原生性能损耗目前零实测基线」处置列里**硬件那一支**的可执行资产。

## 资产清单

| 文件 | 作用 | 本机已实测的部分 |
|---|---|---|
| `ipmi_acceptance.py` | 带外管理验收取证器：Redfish + IPMI over LAN 只读探测，六条硬判据 C1–C6，三态判据（PASS / FAIL / INCONCLUSIVE），产出 JSON 证据 | `--self-test` 绿（15 项断言）；变异校验确认判据抓得住红 |
| `render_user_data.py` | cloud-init 模板渲染器：未解析占位符判红、输出落 git 工作树内默认拒绝 | `--self-test` 绿（4 项断言）；真渲染 / 缺键判红 / git 闸拦截三条路径实测 |
| `pxe/boot.ipxe` | iPXE 引导入口（只负责把控制权交给 `boot.ipxe.cfg`） | 人工审读；**未在真实 PXE 网络里跑过** |
| `pxe/boot.ipxe.cfg` | iPXE 引导配置：拉起内核 + initrd，把 `user-data.yaml` 交给 cloud-init | 同上 |
| `pxe/serve-pxe.sh` | PXE-over-HTTP 根目录服务 + 交付前自检（缺件 / 占位符未渲染 ⇒ 判红） | `bash -n` 与 `shellcheck` 均 RC=0；四种场景实测（空目录红 / 齐备绿 / 占位符未渲染红 / 缺 initrd 红） |
| `cloud-init/user-data.yaml` | cloud-init 模板：装 k3s agent 并加入既有 server，落 `/var/log/bdp-node/bootstrap.log` 证据 | PyYAML 解析通过；**未在真机执行过** |

## 两条路线怎么用

### 路线 ③：免 BMC 的 PXE-over-HTTP + iPXE + cloud-init

满足「可重复装机」，**不满足**带外管理验收（无 BMC 即无法远程开关机 / 读硬件状态）。

1. 选发行版的 netboot 镜像，把 `vmlinuz` 与 `initrd.img` 放进 PXE 根目录（仓内不提供这两个二进制，也不写死下载 URL）。
2. 渲染 user-data：

   ```bash
   python3 scripts/baremetal/render_user_data.py \
     --template scripts/baremetal/cloud-init/user-data.yaml \
     --vars /etc/bdp-node/node.env \
     --out /srv/pxe/user-data.yaml
   ```

   键值文件格式为 `KEY=VALUE` 每行一条，可用键见模板头部清单。
3. 拷贝引导脚本并自检：

   ```bash
   cp scripts/baremetal/pxe/boot.ipxe scripts/baremetal/pxe/boot.ipxe.cfg /srv/pxe/
   bash scripts/baremetal/pxe/serve-pxe.sh --root /srv/pxe --check-only
   ```

4. 起服务并让目标机指向 `http://<本机IP>:8080/boot.ipxe`：

   ```bash
   bash scripts/baremetal/pxe/serve-pxe.sh --root /srv/pxe --port 8080
   ```

### 路线 ②/①：带 BMC 的裸金属（唯一能过带外验收的路径）

1. 在目标机（或云上裸金属实例）装 BMC 并放通带外网。
2. 跑取证器，只读探测：

   ```bash
   printf '%s' "$BMC_PASS" | python3 scripts/baremetal/ipmi_acceptance.py \
     --redfish https://<bmc> --ipmi-host <bmc-ip> \
     --user <u> --password-stdin \
     --ipmi-user <u> --ipmi-password-stdin \
     --os-arch "$(ssh node 'uname -m')" \
     --out evidence/bmc-acceptance.json
   ```

3. 退出码：`0` = 六条判据全 PASS；`2` = 有 FAIL；`3` = 无 FAIL 但有 INCONCLUSIVE（**没结论，不算通过**）。
4. 远程开关机属于写操作，脚本**不自动执行**，命令见证据 JSON 的 `manual_power_commands` 字段，人工确认后执行。

## 盲区声明

- 本机是**笔记本**，无 IPMI/BMC（`Win32_IPMIConnection` 类不存在）⇒ **本目录内所有涉及真实硬件的命令都没在硬件上执行过**，它们是「待执行资产」，不是已通过的验收报告。
- 本机 `PATH` 内**没有** `ipmitool`、没有 `ansible`、没有 `cloud-init` ⇒ `ipmi_acceptance.py` 的 IPMI 腿在本机必然是 INCONCLUSIVE（脚本会把「未找到 ipmitool」写进证据，而不是静默放行）。
- iPXE 脚本**未经真实 PXE/UEFI HTTP Boot 验证**；内核与 initrd 由运维从选定发行版放入，仓内不提供。
- 判定口径与证据格式见 `docs/CLOUD-NATIVE-BARE-METAL-ACCEPTANCE.md`。
