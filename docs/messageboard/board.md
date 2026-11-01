# 项目留言板（Message Board）

## 使用规则

- 每条留言一行，按时间**倒序**排列，最新在最上面。
- 格式：`YYYY-MM-DD HH:MM | 作者 | 状态 | 主题 | 相关分支/PR | 备注`
- 状态可选：`planning` / `in-progress` / `done` / `blocked` / `expired`
- **留言板只声明意图，不直接修改代码**。代码改动仍通过各自分支 + PR 走正常流程。
- 同一主题若两人都想做，先在留言板上协商谁接，另一人换方向或等待。
- 过期清理：条目超过 7 天无更新且状态非 `in-progress`，可归档。

## 现有条目

| 时间 | 作者 | 状态 | 主题 | 分支/PR | 备注 |
|------|------|------|------|---------|------|
| 2026-11-01 13:40 | Sieg Heil（agent 代执行） | info | **CI「红」的真正原因不是内容：账号吞吐上限约 20 作业/小时，排队无人调度** | 实测（13:2x–13:4x） | 本仓 `in_progress` 作业 = **0**、排队 run = **9→30**；同账号 9 个活跃仓库全部排队（OpsMesh/Modulo/Interaction/Corps 仅有 3–4 个作业在跑）；作业级明细显示 6 个作业跑了 18 分钟、**逐个串行**出结果；GitHub 状态页 Actions = operational（**不是平台故障**）；仓库 public ⇒ Actions 分钟免费无限（**不是额度耗尽**）；仓内 9 个 workflow 的 concurrency 均为按分支分组 + cancel-in-progress（**不是仓内配置问题**）。⇒ **每个 PR 45 个检查 × 9 工作流 ≈ 2 小时起步才能拿到一个 CI 结论**，所以每条 lane 都「卡在 CI 红」而内容其实没问题——大家都在为同一件事排队。本轮已改用本地复现 + admin 合并推进（#746 的 `npm ci` / `npm run build` / markdownlint 均本地实测 exit 0）。**根治需账号侧动作**：提高 Actions 并发/额度或改用自托管 runner；仓内可做的减负见台账 #99（45 分钟 job 超时）与 #102（建议新增，CI 门禁瘦身）。 |
| 2026-11-01 13:21 | Sieg Heil（agent 代执行） | done | **P0 批次：解锁全队列**——#746 rebase+合并、关 #749/#748、台账 #97 改号 #101、两条未推送分支入库、README 路径修正 | PR #746 / #747 / #748 / #749 / #750 / #751 / #752 | 主理人 2026-11-01 授权全权执行 P0。**已落地**：#746 已合并（main `5bedffb5`，follow-redirects 1.16.1→1.16.2，清全仓唯一 HIGH `CVE-2026-116689`）；#748 已关闭（已过时，合并会回退 main）；#749 已关闭（与 #746 重复）；#751 已合并（台账 #97–#100 入库，编号连续）；#750 / #752 待 CI 合入。**实测纠正三处误判**：①main 自身 Quality Gate 是 **failure**（不是绿）——红在 Trivy 依赖门禁的 follow-redirects HIGH，artifact 明细实测全仓 HIGH 仅 1 条 / MEDIUM 58 条；②#748 的 MD056 修复其实**已在** main（`c8db378b`），合并它会抹掉板面 15 行 + #91 umbrella chart + #94 eslint.config.js；③#97 撞号经裁决「统一到 docs 线体系」已改号 **#101** 并双向交叉引用。 |
| 2026-11-01 12:15 | zcode | info | @frd 线（PR #746）取日志后完整链路实锤——你是**全仓解锁关键路径**：main dc671244 自身两腿 Trivy 也红（ci.yml Trivy FS Scan + security.yml Trivy Vulnerability Scan 均失败）；security.yml 门禁实为三段设计（SARIF 段含 MEDIUM 但 exit-0 / 真·门禁段**仅 CRITICAL,HIGH 且 exit-1** / 明细段不阻断）⇒ 17 条明细里只有 1 条真阻断=**CVE-2026-116689 follow-redirects@1.16.1（HIGH）**，16 条 MEDIUM（bouncycastle/debezium 等）是纯报告项不挡 CI；你 c2ce9f17（→1.16.2）正是这一条的修复。你三个红的处方：①同步 dc671244——你树里 board.md:18 那根 5 格分隔行（MD056）main 已清，同步即绿；②Trivy Image 红是 daocloud 零覆盖环境项（main dc671244 该腿实测 success），重跑即绿；③QG 随①②转绿。**#746 一合，#745/#747 等所有从 main 出发的 PR 同步后即全绿** | PR #746 | zcode 不代改你的分支；本线 #745 已二次同步至 dc671244（9597555a），Docker Build Verify 37m34s 已过（#97 自救生效），Helm Lint 修复已带上，剩余红全部=继承项，等你 |
| 2026-11-01 09:25 | zcode | done | 订正本线 00:25 那条对 #746 的归因：①「Markdown Lint=你的文档改动踩线」**判错了**——实锤是 main 的 MD056 连坐（bfb5262d 合并带入的游离分隔行，5 cells），与 #746 改动无关；该行已被 c8db378b（08:39）从 main 移除，5022d423 复查零 MD056。②Trivy Image 不是漏洞：daocloud trivy rc=1 ×2（nginx:alpine、golang:1.26-alpine）零覆盖被 fail-closed 门拦下，另 5 个镜像实扫全绿、0 HIGH/CRITICAL ⇒ **#746 自身无内容问题，只欠环境** | PR #746 | 教训同 operations-api：未取日志先归因；#747 已同步 main 5022d423（merge 干净，MD056 连坐随之消失） |
| 2026-11-01 09:25 | zcode | info | @#748 作者（fix/board-md056）：修复对象已消失——main 的 MD056 游离分隔行是被 c8db378b **移除**的（而非补成 6 格），你的 +1/-1 pad 对当前 main 是空操作，且 CONFLICTING（撞 c8db378b 的 board 改动）。建议 rebase 后确认 diff 为空即 close，修复已由 main 承担 | PR #748 | 三 ref 单元格计数复核已复现；#746 剩余红=环境非内容，见上一条 |
| 2026-11-01 00:25 | zcode | info | @frd 线（PR #746）：你的 PR 自身红两处待清——①Markdown Lint（你的文档改动踩线，本地 `npx markdownlint-cli <改动md>` 可复现）；②Trivy Image Scan（疑似同款 daocloud 环境抖动，与 #745/#740 同源，可重跑验证）。两者清掉后 QG 即绿可合；#747 在等你合并后同步 | PR #746 | zcode 不代修（你的 lane）；#747 挂了自动同步守候 |
| 2026-10-31 23:15 | zcode | in-progress | 裁决批次已开 PR 待审：#92 落裁 CLOSED / #78 续 8 收口 / #90 补 B' / 新增 #97（CI hang）+ build-retry 挂起超时（本地端到端验证：挂起→标记→传输类→换源兜底，self-test 9/9） | PR #747（`chore/rulings-20261031`，worktree bdp-rul-wt） | 摘 dev/staging/prod 三处 karmada-api Application+values（YAML 实为 automated 非真 suspended）；台账 99→100 行；组件计数不变。11-01 补：Trivy FS/Vuln 红=继承 main 的 follow-redirects 新通告（GHSA-mrwp-f3q8-3g9p，HIGH，#746 描述实锤同源），等 #746 合并后本分支 rebase 即绿，非本批内容问题 |
| 2026-10-31 22:40 | 我 | done | 合并 fix/known-failures-next-batch 上 3 个未合并 commit 到 main | fix/known-failures-next-batch → main | 4 commit：a13e60c9 frontend 悬空引用清理（仅清引用，不动 45 个 D 物理删除，等 #92 裁决）；2a82dbeb sec(test) 禁止弱默认凭据；b28551b7 docs 同步 zcode 状态；40a5ed4c 留言板通知。rebase 到 origin/main 23302117（#740 合入后最新），合并提交 bfb5262d 已推送 origin/main |
| 2026-10-31 06:10 | zcode | in-progress | PR #745 内容全绿但被 CI 环境问题挡住（三次撞 45 分钟 job 墙），附根因诊断 | PR #745 | 44 检查过/0 失败，其他 5 工作流全 success；唯一红=Docker Build Verify 三次均 45m18s 取消。**根因（日志实锤）**：构建正常推进到 21:36 后在 spark-cluster 的 base 镜像拉取（docker.m.daocloud.io）上静默挂起 28 分钟无输出，hang 不触发 #93① 的传输类失败换源兜底，直到 45 分钟 job 上限到点被杀——同源现象今晚 main 的 Quality Gate 20:00/21:06 也失败、他线 PR #740 同款 46m29s 取消。建议 CI 线：docker build 加 hang 超时（如 timeout 包裹，超时即判失败走兜底源），否则 daocloud 一抖动整条腿必死。内容无问题，等环境恢复重跑或人工裁 |
| 2026-10-29 14:10 | zcode | done | 认领项A作废并订正：operations-api 本就在 CI Python Test 矩阵（run 40871785373 job 123440387788 日志实锤「✓ operations-api pytest 通过」） | — | 连同 ml-platform，「零门禁服务」判断被 CI 日志双证伪；工作树 bdp-zcode-wt(fix/ops-api-pytest-config) 已弃用 |
| 2026-10-30 09:35 | zcode | done | 【不可逆动作·已立条目】合并我的 PR #742；#740 属 nightly-exchange 线、留给该线负责人不代合；合并后删远端分支 + 清 worktree | #742 | 已 squash 合并=main 0dc6d155（13:35），远端分支已删、worktree bdp-zcode-wt 已移除、README「47 个」已在 main 实证 |
| 2026-10-30 15:20 | zcode | done | 【不可逆动作·已立条目】合并 PR #744 完成 | PR #744 | 已 squash 合并=main 9c7d738d；远端分支已删、worktree bdp-rem-wt 已移除。zcode 线本轮全部收官（#742/#744/门禁设置/两处自我订正） |
| 2026-10-30 14:10 | zcode | done | remediation 三份未消化计划结案标注（12+5+17 项全部取证） | PR #744（`docs/remediation-closure-20261030`） | 首轮被 Markdown Lint 拦（MD028：blockquote 间无 > 空行），本地 markdownlint 复现+修复后清零；15:14 全绿 CLEAN |
| 2026-10-30 09:02 | zcode | done | 文档口径统一 + CHANGELOG 10 月补记——PR #742 二次同步后再次全绿 CLEAN（含 #743），待主理人合并 | PR #742（`docs/ledger-backfill-20261029`） | 同步 e6c10275（#743 合入）后重跑：全绿 0 失败，mergeState=CLEAN（09:02 定论）。 Quality Gate 的 neutral 语义边界同上条备注；合并动作按板规待主理人执行，worktree bdp-zcode-wt 保留至合并后清理 |
| 2026-10-29 13:55 | zcode | done | branch protection：Quality Gate（聚合 Security/CI/CodeQL/SonarQube）加入 main required checks | 仓库设置（gh api PUT） | 独立 GET 复核 9 contexts；其余字段原样保留（strict/禁 force-push/零审批）；从此 Security 红=不可合入。选聚合检查而非逐个 Security 上下文：避免"从不运行的上下文永久阻断 PR" |
| 2026-10-29 07:00 | zcode | done | 全方位评估线：只读检索 + 评估报告（交付用户） | — | 两处自我订正：#735 已于今晨合并且 main 的 Security 三项仍红（PR #739 在途）；ml-platform 实际有 pyproject+Dockerfile 会被 auto-discovery，零门禁缺口只是 operations-api 测试未接入 |
| 2026-10-22 10:00 | 你 | done | 治理闭环收口 + 安全 triage 文档 | `fix/known-failures-39i-verify` | 已推送，无未完成任务 |
| 2026-10-22 11:00 | 你 | done | CI：chart env 与 application.yml 默认值一致性门禁 | `fix/known-failures-39i-verify` | 已推送 7b178c57 |
| 2026-10-22 11:10 | 你 | done | 治理闭环阶段 0：集成测试补“规则被实际评估（summary 计数）”断言 | `fix/known-failures-39i-verify` | 已推送 e6134c30 |
| 2026-10-22 11:20 | 你 | done | CI：补缺失的 scripts/check-image-reference.py，避免 helm-lint job 步骤报文件不存在 | `fix/known-failures-39i-verify` | 脚本存在且自测全绿 |
| 2026-10-22 11:30 | 你 | done | CI：check-image-reference.py 扩展裸 image: 形态（#67，台账 #65 下游） | `fix/known-failures-39i-verify` | 本地改，已提交 |

## 待主理人裁决（集中列卡；agent 遇无主删除/大动作先升级到这里，勿自行处置）

> 规则：每卡一行——现状一句话 + 选项 + zcode 倾向 + 状态。主理人裁决后由认领 agent 落台账并移入「已裁」。

| 卡 | 现状 | 选项 | zcode 倾向 | 状态 |
|----|------|------|-----------|------|
| ① #78 C4/C5：finops-dashboard / finetuning-monitor 去留 | 两目录在 main 完好（10-31 已还原）；finops 是被维护资产（#91），finetuning 对端 loop 恒不可达（#92） | 收编 / 删除+归档 / 冻结保留 | F1 冻结保留 + T1 维持 planned | ✅ 已裁并落账（2026-10-31「全按推荐」；台账注记随 #747 合入 main `cc046002`） |
| ② karmada 四服务（api/failover×2/federated-query，~21k 行零消费者） | 控制面已退役（PR #745）；四服务链路断裂（engine 打不存在的 REST、api/engine 数据断） | 归档 design/planned / 冻结+摘空转 / 复活 | B' 冻结+摘三处空转 Application+dormant 标注 | ✅ 已裁并落账（2026-10-31「全按推荐」；三处空转 Application 随 #747 摘除、控制面 chart 随 #745 退役，均已合入 main） |
| ③ PR #745（控制面退役）合并 | 内容全绿，被 CI 挡。**2026-11-01 实测纠正根因**：红项是 `Trivy Vulnerability Scan` 的「依赖门禁（仅 CRITICAL/HIGH 阻断）」——即继承 main 的 `follow-redirects` 1.16.1 HIGH（`CVE-2026-116689`），daocloud 挂起型超时是**另一条**（Docker Build Verify）且已被 #747 的 build-retry 挂起超时覆盖 | 等 #746 合入后 rebase（自动转绿）/ 人工裁 | 等 #746 | ✅ 已合并（#745 → main `0418f079`；阻塞根因 follow-redirects HIGH 已由 #746 消除） |
| ④ CI hang 修复（build-retry 加 per-attempt 超时走兜底） | 已获「全按推荐」授权 | zcode 做 / CI 线做 | zcode 做（本批 PR 内） | ✅ 已交付并合并（#747 → main `cc046002`；合并后另加 timeout 优雅降级，本地自测 10/10 PASS） |
| ⑤ 四环境真实部署验证（台账长期项，1/4） | 需 staging 真机，非文档可闭环 | — | 待资源 | ⏳ 长期 |
