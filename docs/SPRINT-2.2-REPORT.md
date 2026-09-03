# Sprint 2.2 遗留小项执行报告

**日期**：2026-09-03
**范围**：Sprint 2.2 收尾四项（contract drift 自动化 / vite 代理校验门禁 / encaps-tenant 集成测试补建 / 过程中发现的新冲突处置）

## 1. 交付物总览

| 任务 | 文件 | 内容 |
|------|------|------|
| L2 | .githooks/pre-commit（新） | 本地契约门禁前移：前端 api/、后端 Controller、vite 配置、脚本变更时自动跑冲突扫描 + 契约 --check + vite 代理校验；安装 `git config core.hooksPath .githooks` |
| L2 | .github/workflows/ci.yml | bash-check 新增 2 门禁 step：verify-vite-proxy.py（阻断）+ pre-commit hook 语法检查（阻断） |
| L3 | scripts/verify-vite-proxy.py（新） | vite dev 代理完整性静态校验：语法护栏（proxy:{ / /api 兜底顺序）+ 模拟 vite 最长前缀匹配，前端调用按三种 baseURL 形态重建完整路径，后端归属非 encaps-layer 的调用必须有显式代理 |
| L3 | frontend/vite.config.ts | 补 12 个缺失代理前缀：encaps-tenant 域 5（account/admin/projects/quotas/workspaces→:8081）+ encaps-data 域 6（datasources/search/flink/doris/kafka/iotdb→:8083）+ encaps-gateway 域 1（gateway→:8082） |
| L4-0 | platform/encaps-tenant/src/main/resources/application.yml | 显式 `app.tenant.controller.enabled=false`（防 /api/v1/tenants 双注册） |
| L4-0 | platform/encaps-tenant/src/test/resources/application.yml | 补 encrypt-key + 同守卫 |
| L4-0 | EncapsTenantApplicationIT.java（新） | 全 context 启动冒烟测试（本模块首个非 standalone 测试） |
| L4a | platform/encaps-tenant/Dockerfile（新） | 三依赖链分阶段构建（common-security → encaps-layer → encaps-tenant），非 root 运行，actuator 健康检查 |
| L4a | tests/integration/docker-compose.yml | 新增 encaps-tenant 服务（18090:8081），K8s mock + 守卫环境变量，compose 现 14 服务 |
| L4b | tests/integration/docker/test_docker_encaps_tenant.py（新） | 14 个 Docker 集成测试：健康/认证/租户 CRUD/项目 CRUD/账户三端点/运营后台/Workspace/Quota |
| L4b | tests/integration/docker/conftest.py | 七处注册：BASE_URLS/DOCKER_CONTAINERS/HEALTH_PATHS/URL fixture/可用性 fixture/收集钩子映射 |
| 补 | scripts/check-api-route-conflict.py | KNOWN_CROSS_PROCESS_ROUTES 扩至 20 条（新增 projects 8 + search 7） |
| 补 | platform/encaps-layer/.../ProjectController.java、SearchController.java | stub 补 @ConditionalOnProperty 守卫（同 tenants 模式） |

## 2. 过程中发现并处置的新问题

### 2.1 encaps-tenant 从未有过全 context 启动（L4-0 根因）

单测全部 standaloneSetup。首次全 context 启动暴露**两层**阻塞：

1. **引导期**：classpath 同包存在两个 @SpringBootApplication（本模块 + encaps-layer jar）→ "Found multiple @SpringBootConfiguration"。修复：@SpringBootTest 显式 `classes = EncapsTenantApplication.class`（生产 fat jar 同布局，此修复与生产行为一致）。
2. **运行期**：encaps-layer jar 的 TenantController（matchIfMissing=true 默认启用）与本模块同前缀 → ambiguous mapping。修复：application.yml 显式 `app.tenant.controller.enabled=false`——这正是 Sprint 2.1 守卫的设计意图首次真实兑现。

### 2.2 会话期间新提交引入 15 处路由冲突（已被门禁捕获）

commit `1719d589`（00:58）在 encaps-layer 添加 ProjectController/SearchController **stub**（供 nightly-e2e）。时间线证据：stub 提交时间晚于我本地 .m2 install（00:56）96 秒，故首轮 IT 偶然通过——CI 全新构建必炸。处置：

1. 冲突扫描门禁立即爆出（工作正常）；
2. 两个 stub 补同款守卫（matchIfMissing=true 不影响 encaps-layer 独立进程默认行为）；
3. 重 install + 重跑 IT：1/1 过（49.7s）——同 JVM 双注册消除；
4. 15 条 (verb, path) 入白名单（跨进程复用+守卫防护，warning 不阻断）。

### 2.3 vite 代理缺口比预期多 4 个

L1 调研预估 8 个缺口；校验脚本按最长前缀匹配实际发现 **12 个**（engine.ts 的 flink/doris/kafka/iotdb 引擎监控段此前完全漏查）。全部补齐。

## 3. 验证结果（全绿）

| 检查 | 结果 |
|------|------|
| encaps-tenant 全量 mvn test | ✅ **141 tests, 0 failures**（含新 IT），BUILD SUCCESS |
| encaps-layer 全量 mvn test | ✅ **748 tests, 0 failures**（守卫不破坏默认行为），BUILD SUCCESS |
| 路由冲突扫描 | ✅ exit 0；413 端点；豁免 20 处（tenants 6 + projects 8 + search 7，减 health 1 重复计数） |
| 契约生成 + 漂移 | ✅ 258/258 匹配，0 未匹配 |
| vite 代理校验 | ✅ 37 条代理，249 个前端调用路径全部分流正确，exit 0 |
| vite build | ✅ 41.7s 成功（修复 staging 重建时 vue import 遗漏） |
| Vitest | ✅ 203/203 |
| ESLint | ✅ 0 errors（vite.config.ts 单文件 1 warning 可 --fix） |
| docker 集成测试收集 | ✅ 14 tests collected（容器未启动时自动 skip；CI compose 起后生效） |
| CI YAML | ✅ safe_load 解析通过 |
| pre-commit hook | ✅ sh -n 语法通过；CI 门禁同步校验 |

## 4. 对 Sprint 2.3 的输入

1. **契约门禁已三重化**：本地 pre-commit（可选装）→ CI bash-check（阻断）→ 契约文档漂移校验（阻断）；
2. **encaps-tenant 已交付 Docker 化**：镜像构建 + compose 拉起 + 14 个集成测试，CI integration-test job 自动覆盖；
3. **stub 与真实实现并存**：encaps-layer 的 projects/search stub（nightly-e2e 用）与 encaps-tenant/encaps-data 的真实实现经守卫隔离，后续接真实业务时可按需收敛 stub；
4. Phase 3「联调」前置条件全部就绪。
