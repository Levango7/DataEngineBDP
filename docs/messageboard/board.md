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
| 2026-10-22 10:00 | 你 | done | 治理闭环收口 + 安全 triage 文档 | `fix/known-failures-39i-verify` | 已推送，无未完成任务 |
| 2026-10-22 11:00 | 你 | done | CI：chart env 与 application.yml 默认值一致性门禁 | `fix/known-failures-39i-verify` | 已推送 7b178c57 |
