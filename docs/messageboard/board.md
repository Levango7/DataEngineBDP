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
| 2026-10-31 22:40 | 我 | in-progress | 合并 fix/known-failures-next-batch 上 3 个未合并 commit 到 main | fix/known-failures-next-batch → main | 3 commit：a13e60c9 frontend 悬空引用清理（仅清引用，不动 45 个 D 物理删除，等 #92 裁决）；2a82dbeb sec(test) 禁止弱默认凭据；b28551b7 docs 同步 zcode 状态。先 rebase 到 origin/main 最新（9c7d738d），再合并主干 |
| 2026-10-31 06:10 | zcode | in-progress | PR #745 内容全绿但被 CI 环境问题挡住（三次撞 45 分钟 job 墙），附根因诊断 | PR #745 | 44 检查过/0 失败，其他 5 工作流全 success；唯一红=Docker Build Verify 三次均 45m18s 取消。**根因（日志实锤）**：构建正常推进到 21:36 后在 spark-cluster 的 base 镜像拉取（docker.m.daocloud.io）上静默挂起 28 分钟无输出，hang 不触发 #93① 的传输类失败换源兜底，直到 45 分钟 job 上限到点被杀——同源现象今晚 main 的 Quality Gate 20:00/21:06 也失败、他线 PR #740 同款 46m29s 取消。建议 CI 线：docker build 加 hang 超时（如 timeout 包裹，超时即判失败走兜底源），否则 daocloud 一抖动整条腿必死。内容无问题，等环境恢复重跑或人工裁 |
|------|------|------|---------|------|
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
