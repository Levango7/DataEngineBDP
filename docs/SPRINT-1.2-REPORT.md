# Sprint 1.2 执行报告

**日期**：2026-09-02
**范围**：治理层模块验证 + Doris 链路 + 基础设施加固

## 1. 重大结论：治理层三件套均为真实实现（更正前期审计）

前期审计曾将治理层模块判为「骨架」，本轮逐类通读代码后更正：

| 模块 | 判定 | 依据 |
|------|------|------|
| real-time-pipeline | 真实实现（非骨架） | 8 个 service/controller 类 + 5 个单测 + application.yml（仅主类 30 行造成误判） |
| metadata-collector | 真实实现 | 6 类 Collector + repository/service/controller + 11 个测试 |
| lineage-analyzer | 真实实现 | ColumnLineage/TableLineage 抽取器 + Nebula 图写 + 9 个测试（含 IT） |

**影响**：无需 @Profile("stub") 标记，无需删除测试。详见 `docs/realtime-pipeline-implementation-status.md`。

## 2. 交付物

| 任务 | 内容 | 状态 |
|------|------|------|
| 1.2.1 | docs/realtime-pipeline-implementation-status.md | ✅ 已创建 |
| 1.2.2 | test_realtime_governance.py 对齐验证 | ✅ 4 通过/28 跳过（无容器时按设计跳过），保留 |
| 1.2.3 | scripts/doris-lite-verify.sh | ✅ 新建，FE 端口 18030 对齐 lite 栈 |
| 1.2.4 | scripts/smoke-local.sh | ✅ 新建，bash -n 通过 |
| 1.2.5 | java-test-logs 清理 | ✅ 删除 9 个陈旧未跟踪日志 |
| 1.2.6 | metadata-collector 测试编译验证 | ✅ mvn test-compile 通过 |
| 1.2.7 | Doris 链路 | ✅ 无需改 compose（见 §3） |
| 1.2.8 | lineage-analyzer 测试编译验证 | ✅ 根 reactor -pl -am 通过 |
| 1.2.9 | 健康检查端点 | ✅ 三模块均已具备 /api/v1/health + actuator |

## 3. 任务变更说明

- **1.2.7（Doris 入 compose）取消**：Doris 本就排除在 16GB 核心栈之外，且已有
  `docker-compose.infra.yml`（根）与 `design/deploy/dev/docker-compose-doris-lite.yml`
  双套启动方案 + `docker/infra/doris/{fe,be}` 配置 + `scripts/infra/start-infra.sh`。
  真正缺口是缺少独立验证脚本 → 已由 1.2.3 补齐。
- **1.2.9 无需改动**：三模块 application.yml 均已配置 management.actuator，
  且各自 HealthController 提供 GET /api/v1/health。

## 4. 发现的问题

### 4.1 lineage-analyzer 构建依赖（已解决）
`lineage-analyzer/pom.xml` 依赖同级模块 `sql-gateway:jar:0.1.0`，本地仓库未安装时
单模块 `mvn -f ...` 直接构建失败。正确方式：根 reactor `mvn -pl platform/governance/lineage-analyzer -am`。

### 4.2 健康端点契约
三模块健康端点路径统一为 `/api/v1/health`，已纳入 smoke-local 源码契约抽查。

## 5. 冒烟门禁

新增 `scripts/smoke-local.sh`（本地快速门禁，无需集群）：
1. Mock 生产卫生检查（check-prod-mock-hygiene.sh，实测通过）
2. Go 模块编译（catalog/vector-engine/observability）
3. Java 治理模块编译（三件套）
4. Doris Lite 探测（可选不阻断）
5. 健康端点源码契约抽查

## 6. DoD 状态

- [x] P0 全部完成
- [x] P1 完成（1.2.6/1.2.7/1.2.8 验证通过）
- [x] CI 冒烟门禁脚本就绪（smoke-local.sh）
- [x] 文档更新（状态报告 + 本报告）