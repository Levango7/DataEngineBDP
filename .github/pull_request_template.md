<!--
本模板为数据引擎大数据平台 V2.0 Phase 1a 标准 Pull Request 模板。
提交 PR 时请保留所有章节标题，按实际情况填写内容，无适用项填 N/A。
规范依据：design/v2.0/Phase1/启动前准备/E_开发规范与分支策略.md
-->

## 变更描述

### What（做了什么）
<!-- 简明描述本次 PR 的具体变更内容，聚焦"做了什么"，可分点列出。 -->
-

### Why（为什么做）
<!-- 变更动机：解决什么问题 / 满足什么需求 / 对应哪个验收标准。 -->
-

## 关联任务

- 任务 ID：<!-- 形如 T008 / T008-1（子任务） / T000 等保整改准备 -->
- 所属批次：<!-- 批次 1 / 批次 2 / 批次 3 / 批次 4 / 批次 5 -->
- 所属领域组：<!-- 云原生组 / AI 组 / 数据联邦组 / 实时数仓组 / 行业模板组 / 安全合规组 / 横切支持 -->
- 关联 Issue：<!-- Closes #42 / Refs #43，无则填 N/A -->
- 关联 PR：<!-- 依赖的前置 PR 编号，无则填 N/A -->

## 变更类型
<!-- 勾选所有适用项，多选。 -->
- [ ] 新功能（feat）
- [ ] Bug 修复（fix）
- [ ] 重构（refactor）
- [ ] 性能优化（perf）
- [ ] 文档（docs）
- [ ] 测试（test）
- [ ] 构建/CI/工具链（chore / ci / build）
- [ ] 样式（style）
- [ ] 回滚（revert）

## 影响范围
<!-- 勾选所有受影响的组件/模块。 -->
- [ ] platform/encaps-layer（封装层）
- [ ] platform/sql-gateway（统一 SQL 网关）
- [ ] platform/catalog（自研 Catalog）
- [ ] platform/rule-engine（自研规则引擎）
- [ ] platform/dqctl（dqctl CLI）
- [ ] platform/llmops（LLMOps）
- [ ] platform/ml-platform（ML 平台）
- [ ] frontend（前端控制台）
- [ ] ske（自研 SKE 集群）
- [ ] design/deploy（Helm Chart / values）
- [ ] tests（集成 / E2E 测试）
- [ ] scripts（PoC / 运维脚本）
- [ ] docs / design（文档）
- [ ] 其他：<!-- 注明 -->

## 测试方式
<!-- 勾选本次变更所通过的测试类型，并附运行命令与结果摘要。 -->
- [ ] 单元测试
  - 命令：<!-- 如 `mvn -f platform/encaps-layer/pom.xml test` / `pytest tests/unit/` / `go test ./...` / `npm run test:unit` -->
  - 结果：<!-- 通过 / 失败（说明原因），覆盖率：行 xx% / 分支 xx% -->
- [ ] 集成测试
  - 命令：<!-- 如 `pytest tests/integration/test_encaps.py -v` -->
  - 结果：
- [ ] E2E 测试
  - 命令：<!-- 如 `bash scripts/poc/run-poc.sh` / `npx playwright test` -->
  - 结果：
- [ ] 性能测试
  - 命令：<!-- 如 `jmeter -n -t plan.jmx` / `locust -f locustfile.py` -->
  - 结果：<!-- P95 / QPS / 与基线对比 -->
- [ ] 手工验证
  - 步骤与结果：

## 验收标准检查清单
<!-- 关联 Phase1_详细执行计划.md §7.4 验收标准检查清单中本任务对应的序号。逐项勾选并附验证证据（日志/截图/报告链接）。 -->
- [ ] 验收项 #<!-- 序号 -->：<!-- 抄写验收标准原文 --> — 证据：
- [ ] 验收项 #<!-- 序号 -->：<!-- 抄写验收标准原文 --> — 证据：

## 代码审查自检清单
<!-- 提交前逐项确认。所有项必须勾选方可请求 Reviewer。 -->
- [ ] 提交消息遵循 Conventional Commits 规范（`<type>(<scope>): <subject>`）
- [ ] 本地构建通过（Java: `mvn clean package` / Go: `go build ./...` / Python: `pytest` / 前端: `npm run build`）
- [ ] 代码已格式化（Java: `mvn spotless:apply` / Go: `gofmt -s -w .` / Python: `black . && isort .` / 前端: `npm run lint -- --fix`）
- [ ] 单元测试通过且覆盖率不下降（行 ≥80%，关键路径任务 ≥90%，分支 ≥70%）
- [ ] 新增公开 API 有 Javadoc / docstring / TSDoc
- [ ] 未引入新的直接依赖（如必须，已在下方"依赖变更"说明理由）
- [ ] Mock 已清零（真实实现路径已激活，默认配置不走 Mock）
- [ ] 命名遵循 CONVENTIONS.md（套餐 base/standard/flagship、工作空间 ws-\<name>、模块 49、SKE v0.1）
- [ ] V1.0 向后兼容（V1.0 客户端零改动可继续工作，详见下方"Breaking changes"）

## Breaking changes
<!-- 若存在破坏性变更，必须详细说明并给出迁移方案；无则填 N/A。 -->
- [ ] 本 PR 不包含 Breaking changes
- Breaking 变更点：
- 影响范围：
- 迁移方案：
- V1.0 兼容性影响：

## 依赖变更
<!-- 新增 / 升级 / 移除的直接依赖，注明理由与版本。无则填 N/A。 -->
-

## Reviewer 指定建议
<!-- 按 E_开发规范与分支策略.md §4.2 Reviewer 分配规则指定。至少 1 名；架构变更 / 公共 API 变更 / 跨模块接口需 2 名且含首席架构师。 -->
- 推荐 Reviewer 1：<!-- GitHub 用户名 + 领域组 -->
- 推荐 Reviewer 2：<!-- 可选，架构变更 / 公共 API 变更必填 -->
- 升级 review 触发：<!-- 架构变更 / 公共 API 变更 / 跨模块接口 / 关键路径任务 / 安全敏感代码，无则填 N/A -->

## 截图 / 日志
<!-- 前端 UI 变更附截图；CLI / 部署变更附关键日志；性能变更附基线对比图。无则填 N/A。 -->
-

## 部署影响
<!-- 变更是否影响部署配置 / Helm values / K8s 资源 / 运行时依赖。无则填 N/A。 -->
- 是否需要更新 Helm Chart：是 / 否
- 是否需要数据库迁移：是 / 否（附迁移脚本）
- 是否需要配置项变更：是 / 否（附配置说明）
- 是否需要灰度发布：是 / 否（附灰度策略）

## 风险与回滚
<!-- 评估上线风险并给出回滚方案。低风险可简化。 -->
- 风险等级：低 / 中 / 高
- 回滚方式：<!-- 回滚提交 / Helm rollback / 配置回退 -->

---

> 提交 PR 即表示已阅读并遵循 `design/v2.0/Phase1/启动前准备/E_开发规范与分支策略.md` 与 `CONTRIBUTING.md`。
> Review SLA：Reviewer 需在 24 小时内响应；关键路径任务 / 安全敏感代码 12 小时内响应。