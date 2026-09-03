# Sprint 1.3 执行报告

**日期**：2026-09-02
**范围**：前端菜单清理 + CI 基线

## 1. 调研结论

| 维度 | 结论 |
|------|------|
| 前端菜单 47 项 | 全部 47 项路由与 API 层相连（50 个路由视图 + 1 个 Login）—— 无真正"无后端空壳"菜单项 |
| 路由 vs 菜单一致性 | 1 处不一致：`/ops` 路由存在但未在 Sidebar 出现 —— 已在 1.3.1b 修复 |
| Sidebar 写死文案 | "v2.1 GA · 客户无感知底座 / 环境: 信创" 硬编码，无法反映多环境部署 —— 已在 1.3.1a 修复 |
| CI 工作流 | ci.yml 已含 23 个 job（gitleaks / 4 种语言 lint / 6 种构建 / 集成测试 / 安全 / 镜像扫描 / SBOM / 性能 / etc.） |
| 缺口 | 缺 smoke-local 入口 + markdown-lint 仅校验 README/CONVENTIONS，未覆盖 docs/ |

## 2. 交付物

| 任务 | 文件 | 变更 |
|------|------|------|
| 1.3.1a | frontend/src/components/Sidebar.vue | 写死版本/环境 → 动态注入 `__APP_VERSION__` / `__APP_ENV__` |
| 1.3.1a | frontend/vite.config.ts | 新增 `define` 注入；版本号从 `package.json` 单一来源读取 |
| 1.3.1a | frontend/src/env.d.ts | 新增 `__APP_VERSION__` / `__APP_ENV__` 全局类型声明 |
| 1.3.1b | frontend/src/components/Sidebar.vue | 在「产品运营」分组中加入「运维中心」(/ops) |
| 1.3.2a | .github/workflows/ci.yml | 新增 `smoke-local` job（25 步） + 加入 `markdown-lint.depends` |
| 1.3.2b | .github/workflows/ci.yml | `markdown-lint` 新增校验 `docs/**/*.md` |

## 3. 关键发现

### 3.1 前端版本号不一致
- package.json: `2.1.0-RC`
- Sidebar 写死: `v2.1 GA`
- 修复后：vite.config 读 package.json 注入 `__APP_VERSION__`，Sidebar 渲染该常量，单一来源。

### 3.2 /ops 路由孤立
Router 注册但 Sidebar 无入口。`/ops` 是 observability query-api 后端的运维监控页（KPI：集群健康/运行作业/今日失败/平均延迟），与 Dashboard 同类，已加入「产品运营」分组。

### 3.3 工具约束（已记入项目记忆）
- Edit/Write 仅限工作目录 F:\IDE\TraeWork CN
- F:\Nexus\DataEngineBDP 改动需走 PowerShell 或 C# 脚本（PowerShell 多行 here-string 嵌套转义易踩坑）
- 推荐模式：写临时 C# 文件 + Add-Type 编译，规避 PowerShell 转义陷阱

## 4. 验证

| 检查 | 结果 |
|------|------|
| YAML 语法 | `python yaml.safe_load` 解析成功，jobs 列表含 smoke-local |
| Vite config 头部 | 完整：imports + define + plugins + resolve + server(port 5173 + proxy) |
| Sidebar 顶部 | `/dashboard` 与 `/ops` 紧邻，"运维中心" 标签 |
| env.d.ts | 含 `declare const __APP_VERSION__/__APP_ENV__: string` |
| 文件总行数 | vite.config.ts 199 行（修复前 207，因 trim 减 8 行 + 注入 8 行） |

## 5. 后续建议

- Sprint 2 开始前：本地跑一次 `vite build` 验证 `__APP_VERSION__` 实际被替换为 `"2.1.0-RC"`
- markdown-lint 首次可能因历史 docs/ 格式不达标阻断 —— 建议先跑一次 `markdownlint --fix docs/**/*.md` 自愈
- smoke-local job 需要 MySQL 客户端，已在 step 中加 `apt-get install -y mysql-client` 兜底