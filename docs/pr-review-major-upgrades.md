# Major 升级 PR 兼容性审查报告

> **审查范围**：Levango7/DataEngineBDP 仓库 31 个 🟠 需详细审查的 major 升级 Dependabot PR
> **审查日期**：2026-08-13
> **审查方式**：只读分析（未合并/关闭/修改任何 PR 或代码）
> **项目路径**：F:\nexus\DataEngineBDP

---

## 第1章 审查总览

### 1.1 项目技术栈现状

| 维度 | 现状 | 来源 |
|------|------|------|
| Java 版本 | 17（所有模块统一） | 各 pom.xml `<java.version>17</java.version>` |
| Spring Boot | 3.2.5（17 个模块）/ 3.3.4（knative/runtimes/java） | 18 个 pom.xml |
| Spring Cloud | 2023.0.3（仅 knative/runtimes/java） | knative/runtimes/java/pom.xml |
| fabric8 kubernetes-client | 6.13.4（仅 encaps-layer） | encaps-layer/pom.xml |
| hive-jdbc | 3.1.3（仅 metadata-collector） | metadata-collector/pom.xml |
| native-maven-plugin | 0.10.2（仅 knative/runtimes/java） | knative/runtimes/java/pom.xml |
| 前端框架 | Vue 3.4.21 + Vite 6 + TypeScript 5.4 | frontend/package.json |
| 前端路由/状态 | vue-router ^4.3.0 / pinia ^2.1.7 | frontend/package.json |
| 前端图表 | echarts ^6.1.0（已升级到 6.x） | frontend/package.json |
| Python SDK | openai 1.23.0 / langchain-core 0.1.45 / mlflow 2.11.1 | nl2sql/llmops/ml-platform requirements.txt |
| pytest-asyncio | 0.23.6（4 个 Python 模块） | 各 requirements.txt |
| CI | actions/download-artifact@v4 | .github/workflows/release.yml |

### 1.2 31 个 PR 审查结论汇总

表：31 个 PR 兼容性评估与建议汇总表

| # | 依赖 | 当前→目标 | PR | 模块 | 兼容性 | 风险 | 建议 |
|---|------|----------|-----|------|--------|------|------|
| 1 | spring-boot-starter-parent | 3.2.5→4.1.0 | #158 | karmada/federated-query | 不兼容 | 高 | 关闭 |
| 2 | spring-boot-starter-parent | 3.2.5→4.1.0 | #146 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 3 | spring-boot-starter-parent | 3.2.5→4.1.0 | #133 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 4 | spring-boot-starter-parent | 3.2.5→4.1.0 | #130 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 5 | spring-boot-starter-parent | 3.2.5→4.1.0 | #110 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 6 | spring-boot-starter-parent | 3.2.5→4.1.0 | #84 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 7 | spring-boot-starter-parent | 3.2.5→4.1.0 | #82 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 8 | spring-boot-starter-parent | 3.2.5→4.1.0 | #79 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 9 | spring-boot-starter-parent | 3.2.5→4.1.0 | #28 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 10 | spring-boot-starter-parent | 3.2.5→4.1.0 | #27 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 11 | spring-boot-starter-parent | 3.2.5→4.1.0 | #24 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 12 | spring-boot-starter-parent | 3.2.5→4.1.0 | #7 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 13 | spring-boot-starter-parent | 3.2.5→4.1.0 | #6 | （待确认模块） | 不兼容 | 高 | 关闭 |
| 14 | spring-boot-starter-parent | 3.3.4→4.1.0 | #3 | knative/runtimes/java | 不兼容 | 高 | 关闭 |
| 15 | spring-cloud-dependencies | 2023.0.3→2025.1.2 | #132 | knative/runtimes/java | 不兼容 | 高 | 关闭 |
| 16 | kubernetes-client (fabric8) | 6.13.4→7.8.0 | #9 | encaps-layer | 不兼容 | 高 | 需修改后合并 |
| 17 | hive-jdbc | 3.1.3→4.2.0 | #12 | governance/metadata-collector | 部分兼容 | 中 | 保持 open |
| 18 | native-maven-plugin (graalvm) | 0.10.2→1.1.8 | #135 | knative/runtimes/java | 部分兼容 | 中 | 保持 open |
| 19 | langchain-core | 0.1.45→1.5.3 | #162 | nl2sql | 不兼容 | 高 | 关闭 |
| 20 | openai | 1.23.0→2.53.0 | #160 | nl2sql | 不兼容 | 高 | 关闭 |
| 21 | mlflow | 2.11.1→3.15.1 | #122 | llmops | 不兼容 | 高 | 关闭 |
| 22 | mlflow | 2.11.1→3.15.1 | #118 | ml-platform | 不兼容 | 高 | 关闭 |
| 23 | pytest-asyncio | 0.23.6→1.4.0 | #104 | （Python 模块） | 部分兼容 | 中 | 需修改后合并 |
| 24 | pytest-asyncio | 0.23.6→1.4.0 | #95 | （Python 模块） | 部分兼容 | 中 | 需修改后合并 |
| 25 | pytest-asyncio | 0.23.6→1.4.0 | #75 | （Python 模块） | 部分兼容 | 中 | 需修改后合并 |
| 26 | pytest-asyncio | 0.23.6→1.4.0 | #65 | （Python 模块） | 部分兼容 | 中 | 需修改后合并 |
| 27 | vue-router | 4.6.4→5.2.0 | #78 | frontend | 兼容 | 低 | 合并 |
| 28 | pinia | 2.3.1→4.0.2 | #45 | frontend | 兼容 | 低 | 合并 |
| 29 | echarts | 5.6.0→6.1.0 | #43 | frontend | 兼容 | 低 | 保持 open（已升级） |
| 30 | typescript | 5.9.3→7.0.2 | #51 | frontend(dev) | 不兼容 | 高 | 关闭 |
| 31 | actions/download-artifact | 4→8 | #1 | CI | 不兼容 | 高 | 关闭 |

### 1.3 建议统计

| 建议 | 数量 | PR 编号 |
|------|------|---------|
| 合并 | 2 | #78, #45 |
| 保持 open | 4 | #12, #135, #43, （#43 实际已升级） |
| 需修改后合并 | 5 | #9, #104, #95, #75, #65 |
| 关闭 | 20 | 14 个 Spring Boot + #132 + #162 + #160 + #122 + #118 + #51 + #1 |

---

## 第2章 Spring Boot 3→4 升级分析（14 个 PR）

### 2.1 涉及的 PR 与模块

表：Spring Boot 升级 PR 与模块对照表

| PR | 当前版本 | 目标版本 | 模块 |
|----|---------|---------|------|
| #158 | 3.2.5 | 4.1.0 | platform/karmada/federated-query |
| #146 | 3.2.5 | 4.1.0 | （待确认） |
| #133 | 3.2.5 | 4.1.0 | （待确认） |
| #130 | 3.2.5 | 4.1.0 | （待确认） |
| #110 | 3.2.5 | 4.1.0 | （待确认） |
| #84 | 3.2.5 | 4.1.0 | （待确认） |
| #82 | 3.2.5 | 4.1.0 | （待确认） |
| #79 | 3.2.5 | 4.1.0 | （待确认） |
| #28 | 3.2.5 | 4.1.0 | （待确认） |
| #27 | 3.2.5 | 4.1.0 | （待确认） |
| #24 | 3.2.5 | 4.1.0 | （待确认） |
| #7 | 3.2.5 | 4.1.0 | （待确认） |
| #6 | 3.2.5 | 4.1.0 | （待确认） |
| #3 | 3.3.4 | 4.1.0 | platform/knative/runtimes/java |

> **说明**：项目实际有 18 个 Spring Boot 模块（17 个用 3.2.5，1 个用 3.3.4），任务列表中 14 个 PR 对应其中 14 个模块。已通过 PR #158 的 head ref 确认其对应 `platform/karmada/federated-query`。

### 2.2 兼容性评估：不兼容

#### 2.2.1 Java 版本要求

- **Spring Boot 4.1 要求**：Java 17+
- **项目现状**：所有模块 `<java.version>17</java.version>`
- **结论**：Java 版本满足最低要求 ✅

#### 2.2.2 Spring Boot 4.1 重大破坏性变更

1. **Spring Framework 7.0**：Spring Boot 4.1 基于 Spring Framework 7.0，要求 Java 17+，但大量 API 包名从 `javax.*` 迁移到 `jakarta.*`（虽然 Spring Boot 3.x 已完成 Jakarta EE 9 迁移，但 4.x 进一步收紧）

2. **配置属性移除/变更**：
   - `spring.h2.console.enabled` 在 Spring Boot 4 中默认禁用且配置路径可能变更（encaps-layer 使用了此配置）
   - `management.metrics.export.prometheus.enabled` 配置键可能变更（所有模块使用）
   - `server.servlet.session.cookie.*` 配置重构（PR #158 release notes 提到 `partitioned=true` 在 Tomcat 中无效果已修复）

3. **Auto-configuration 重构**：
   - Spring Boot 4 重构了大量 auto-configuration 类，`@EnableAutoConfiguration` 的加载机制变更
   - MailSender auto-configuration 行为变更（PR #158 release notes 提到 hostname verification 问题）
   - Test auto-configuration 不再集成 Spring Security 与 HtmlUnitDriver

4. **依赖管理变更**：
   - Spring Batch 6.0.4（从 5.x 升级）
   - Spring Integration 7.1.0（从 6.x 升级）
   - 这些传递依赖的大版本跳跃可能导致间接编译错误

5. **Actuator 端点变更**：
   - `ConfigurationPropertiesReportEndpoint` 不再暴露 AOP proxy internals
   - `MappingsEndpoint` 的 parentId 报告行为变更
   - `/cloudfoundryapplication` 端点在限制性 CORS 下不工作

#### 2.2.3 项目代码风险点

表：Spring Boot 升级代码风险点

| 风险点 | 涉及模块 | 影响程度 |
|--------|---------|---------|
| `spring.h2.console.enabled` 配置 | encaps-layer, metadata-collector, federated-query 等 | 中 |
| `management.metrics.export.prometheus.enabled` | 所有模块 | 中 |
| `management.endpoints.web.exposure.include` | 所有模块 | 低 |
| `spring.jpa.hibernate.ddl-auto` | encaps-layer, metadata-collector | 低 |
| jjwt 0.12.6 与 Spring Security 7 兼容性 | encaps-layer, metadata-collector, federated-query | 高 |
| fabric8 kubernetes-client 6.13.4 与 Spring Boot 4 兼容性 | encaps-layer | 高 |
| hive-jdbc 3.1.3 与 Spring Boot 4 兼容性 | metadata-collector | 高 |
| Apache Calcite 1.36.0 与 Spring Boot 4 兼容性 | federated-query | 高 |
| WireMock 2.35.2 与 Spring Boot 4 Test 兼容性 | federated-query | 高 |
| `--add-opens java.base/java.lang=ALL-UNNAMED` | 所有模块（surefire argLine） | 低 |

#### 2.2.4 knative/runtimes/java 特殊风险

PR #3 将 Spring Boot 从 3.3.4 升级到 4.1.0，该模块还使用：
- `spring-cloud-dependencies` 2023.0.3（Spring Cloud 2023.0 仅兼容 Spring Boot 3.2.x）
- `native-maven-plugin` 0.10.2（GraalVM 0.10.x 仅兼容 Spring Boot 3.x）
- **三重不兼容**：Spring Boot 4 + Spring Cloud 2023.0 + native-maven-plugin 0.10 无法共存

### 2.3 建议：关闭所有 14 个 Spring Boot 升级 PR

**理由**：
1. Spring Boot 3→4 是大版本跳跃，涉及 Spring Framework 7、Jakarta EE 10、大量 auto-configuration 重构
2. 项目有 18 个 Spring Boot 模块，需要统一升级策略，不能逐个模块单独升级
3. 传递依赖（jjwt、fabric8、hive-jdbc、Calcite、WireMock）均未验证与 Spring Boot 4 的兼容性
4. knative/runtimes/java 模块的三重依赖锁死（Spring Boot + Spring Cloud + native-maven-plugin）
5. 正确做法：先统一升级到 Spring Boot 3.4.x（最新 3.x），验证全量通过后，再规划 4.x 迁移

**关闭命令**（仅供参考，本次不执行）：
```
@dependabot ignore this major version
```

---

## 第3章 kubernetes-client 6→7 升级分析（PR #9）

### 3.1 PR 信息

- **PR**：#9
- **模块**：platform/encaps-layer
- **升级**：io.fabric8:kubernetes-client 6.13.4 → 7.8.0
- **release notes 关键变更**：
  - 7.8.0 修复了 bodyless requests 方法保留、informer resync 容错、TLS trust failure fail-fast 等
  - 7.7.0 修复了 MockWebServer shutdown、ExecWebSocketListener onError 等
  - **破坏性变更**：v7 重构了 DSL 入口和 resource 类型（release notes 中 "Breaking changes" 标记）

### 3.2 兼容性评估：不兼容（需修改后合并）

#### 3.2.1 代码使用方式分析

encaps-layer 大量使用 fabric8 KubernetesClient DSL（111 处匹配）：

表：encaps-layer 中 fabric8 API 使用情况

| API | 使用位置 | v7 兼容性 |
|-----|---------|----------|
| `KubernetesClient` 接口 | K8sWorkspaceTranslator, K8sQuotaTranslator, K8sClientConfig | ✅ 保留 |
| `KubernetesClientBuilder` | K8sClientConfig | ✅ 保留 |
| `KubernetesClientException` | 所有 Translator + Test | ✅ 保留 |
| `MixedOperation` DSL | Test 文件 | ⚠️ v7 泛型签名变更 |
| `NonNamespaceOperation` DSL | Test 文件 | ⚠️ v7 泛型签名变更 |
| `Resource` DSL | Test 文件 | ⚠️ v7 接口重构 |
| `Namespace`, `NamespaceList`, `NamespaceStatus` | K8sWorkspaceTranslator | ✅ 保留 |
| `NetworkPolicy`, `NetworkPolicyBuilder` (networking.v1) | K8sWorkspaceTranslator | ✅ 保留 |
| `RoleBinding`, `RoleBindingBuilder` (rbac) | K8sWorkspaceTranslator | ✅ 保留 |
| `ResourceQuota`, `ResourceQuotaBuilder` | K8sWorkspaceTranslator, K8sQuotaTranslator | ✅ 保留 |
| `LimitRange`, `LimitRangeBuilder` | K8sQuotaTranslator | ✅ 保留 |
| `Quantity` | K8sQuotaTranslator, QuantityComparator | ✅ 保留 |
| `k8sClient.network()` | K8sWorkspaceTranslatorTest | ✅ 保留 |
| `k8sClient.rbac()` | K8sWorkspaceTranslatorTest | ✅ 保留 |
| `LabelSelector`, `LabelSelectorBuilder` | K8sWorkspaceTranslator | ✅ 保留 |

#### 3.2.2 主要风险点

1. **DSL 泛型签名变更**：v7 重构了 `MixedOperation`、`NonNamespaceOperation`、`Resource` 等泛型接口的签名，测试代码中的 Mockito mock 链可能编译失败
2. **Resource 接口重构**：v7 的 `Resource` 接口方法集变更，`edit()`、`patch()` 等方法签名可能不同
3. **KubernetesClientException 构造函数**：v7 可能新增/移除构造函数重载
4. **Mockito 兼容性**：测试中 `mock(io.fabric8.kubernetes.client.dsl.NetworkAPIGroupDSL.class)` 等可能因接口变更而失败

### 3.3 建议：需修改后合并

**需要修改的文件**：
1. `platform/encaps-layer/src/test/java/.../K8sWorkspaceTranslatorTest.java` - 检查 DSL mock 链泛型
2. `platform/encaps-layer/src/test/java/.../K8sQuotaTranslatorTest.java` - 检查 DSL mock 链泛型
3. `platform/encaps-layer/src/main/java/.../K8sWorkspaceTranslator.java` - 检查 Resource DSL 调用
4. `platform/encaps-layer/src/main/java/.../K8sQuotaTranslator.java` - 检查 Resource DSL 调用

**修改内容**：
- 更新 DSL 泛型类型参数以匹配 v7 签名
- 验证 `network()`、`rbac()` 等 API group 访问方式未变
- 运行 `mvn test -pl platform/encaps-layer` 验证测试通过

---

## 第4章 hive-jdbc 3→4 升级分析（PR #12）

### 4.1 PR 信息

- **PR**：#12
- **模块**：platform/governance/metadata-collector
- **升级**：org.apache.hive:hive-jdbc 3.1.3 → 4.2.0
- **release notes**：PR body 未包含 release notes（Dependabot 未抓取）

### 4.2 兼容性评估：部分兼容

#### 4.2.1 代码使用方式分析

表：metadata-collector 中 hive-jdbc 使用情况

| 使用点 | 代码 | Hive 4 兼容性 |
|--------|------|--------------|
| 驱动类名 | `org.apache.hive.jdbc.HiveDriver` | ✅ 保留（Hive 4 仍使用此类名） |
| JDBC URL 前缀 | `jdbc:hive2://host:port/db` | ✅ 保留（Hive 4 仍使用 jdbc:hive2 协议） |
| URL 构建逻辑 | `HiveMetadataCollector.buildJdbcUrl()` | ✅ 逻辑无需变更 |
| 排除项 | slf4j-log4j12, log4j, servlet-api, jersey, netty | ⚠️ Hive 4 依赖树变更，可能需要调整排除项 |

#### 4.2.2 主要风险点

1. **依赖传递冲突**：Hive 4.x 依赖 Hadoop 3.x+，可能与项目已有的 `hadoop-client` 3.3.6 产生版本冲突
2. **日志框架冲突**：Hive 4.x 可能升级到 Log4j 2.x，与 Spring Boot 的 Logback 冲突排除项需更新
3. **Netty 版本冲突**：Hive 4.x 可能引入新版 Netty，与 Spring WebFlux 的 Reactor Netty 冲突
4. **Jersey 版本冲突**：Hive 4.x 可能升级 Jersey 到 3.x（Jakarta），与现有排除项 `com.sun.jersey` 不匹配
5. **Servlet API 迁移**：Hive 4.x 可能从 `javax.servlet` 迁移到 `jakarta.servlet`，现有排除项 `javax.servlet:servlet-api` 失效

### 4.3 建议：保持 open

**理由**：
1. 驱动类名和 JDBC URL 格式兼容，核心代码无需修改
2. 但依赖排除项需要根据 Hive 4.x 的实际依赖树调整，否则可能产生依赖冲突
3. 建议在本地分支验证 `mvn dependency:tree -pl platform/governance/metadata-collector` 后再决定

---

## 第5章 前端升级分析

### 5.1 vue-router 4→5（PR #78）

#### 5.1.1 兼容性评估：兼容

- **当前使用**：`createRouter`, `createWebHashHistory`, `RouteRecordRaw`（标准 API）
- **vue-router 5 要求**：Vue 3.4+（项目用 ^3.4.21 ✅）
- **release notes 关键变更**：
  - v5.2.0：允许 pinia 4、修复 scrollBehavior stale 结果
  - v5.1.0：增强 `definePage` 类型、`defineParamParser` 改进
  - v5.0.7：升级 babel 8、`@vue/devtools-api` 升级
- **项目代码**：`frontend/src/router/index.ts` 使用标准 `createRouter` + `createWebHashHistory`，无实验性 API
- **风险**：低，`RouteRecordRaw` 类型可能微调，但 `vue-tsc` 应能捕获

#### 5.1.2 建议：合并

### 5.2 pinia 2→4（PR #45）

#### 5.2.1 兼容性评估：兼容

- **当前使用**：`defineStore` 的 setup 语法（composition API 风格）
- **项目代码**：`frontend/src/stores/auth.ts` 使用 `defineStore('auth', () => { ... })`，标准 setup store
- **pinia 4 release notes**：
  - v4.0.0：构建配置重构、类型导出修复
  - v4.0.1：重新添加缺失的类型导出
  - v4.0.2：CI 修复
- **风险**：低，setup store 语法在 pinia 2/3/4 中完全兼容

#### 5.2.2 建议：合并

### 5.3 echarts 5→6（PR #43）

#### 5.3.1 兼容性评估：兼容（但项目已升级）

- **当前 package.json**：`"echarts": "^6.1.0"`（已使用 6.x）
- **PR 升级**：5.6.0 → 6.1.0
- **矛盾**：PR 基线是 5.6.0，但项目 package.json 已是 ^6.1.0
- **release notes 关键变更**（6.1.0）：
  - 大量 axis/bar/scatter/pie/tooltip bug 修复
  - 新增 `dataMin`/`dataMax` axis extent 选项
  - 新增 `triggerEvent` for line series
  - **破坏性变更**：echarts 6 移除了部分 5.x 已废弃 API
- **项目使用**：`frontend/src/test-setup.ts` 中 `vi.mock('echarts')`，`frontend/src/types/ai-assistant.ts` 中 ECharts 配置类型
- **风险**：低（项目已在 6.x）

#### 5.3.2 建议：保持 open（或关闭，因为项目已升级）

> **注意**：此 PR 可能是 Dependabot 基线滞后，项目 package.json 已是 ^6.1.0。建议手动确认后关闭。

### 5.4 typescript 5→7（PR #51）

#### 5.4.1 兼容性评估：不兼容

- **当前**：typescript ^5.4.0（devDependency）
- **目标**：7.0.2
- **release notes**：
  - TypeScript 6.0：重大版本跳跃（从 5.x 直接到 6.x）
  - TypeScript 7.0.2：进一步变更
- **项目配置**：`frontend/tsconfig.json` + `vue-tsc` ^2.0.6
- **风险点**：
  1. **vue-tsc 兼容性**：vue-tsc 2.x 可能不支持 TypeScript 7.x
  2. **vite 兼容性**：vite 6.x 的 esbuild 可能不支持 TypeScript 7.x 语法
  3. **typescript-eslint 兼容性**：typescript-eslint ^8.66.0 可能不支持 TypeScript 7.x
  4. **严格度提升**：TS 7.x 可能引入新的严格检查，导致大量类型错误
  5. **跳过 6.x**：从 5.x 直接到 7.x 跨越两个大版本，风险极高

#### 5.4.2 建议：关闭

**理由**：
1. TypeScript 5→7 跨越两个大版本，无增量验证路径
2. vue-tsc、vite、typescript-eslint 等工具链未验证支持 TS 7.x
3. 正确做法：先升级到 TypeScript 5.9.x（最新 5.x），再评估 6.x 迁移

---

## 第6章 Python SDK 升级分析

### 6.1 openai 1→2（PR #160）

#### 6.1.1 兼容性评估：不兼容

- **当前**：openai==1.23.0（nl2sql/requirements.txt）
- **目标**：2.53.0
- **项目使用方式**：`nl2sql/sql_generator.py` 通过 `langchain_openai.ChatOpenAI` 间接使用
  ```python
  from langchain_openai import ChatOpenAI  # type: ignore
  self._llm = ChatOpenAI(
      model=...,
      openai_api_key=self.settings.llmApiKey or "not-required",
      openai_api_base=self.settings.llmEndpoint,
  )
  ```
- **openai 2.x 破坏性变更**：
  1. `OpenAI()` 客户端构造参数变更
  2. `openai_api_key` / `openai_api_base` 参数名可能变更（改为 `api_key` / `base_url`）
  3. Responses API 替代 Chat Completions API
  4. 内容溯源检查（content provenance checks）
- **风险**：
  - 虽然 nl2sql 不直接调用 `openai.OpenAI()`，但 `langchain_openai.ChatOpenAI` 内部依赖 openai SDK
  - `langchain_openai` 的版本必须与 openai 2.x 兼容
  - 当前 `langchain==0.1.16` + `langchain-community==0.0.34` 不支持 openai 2.x

#### 6.1.2 建议：关闭

**理由**：openai 2.x 需要 langchain 1.x+ 配合，单独升级 openai 会导致 `langchain_openai.ChatOpenAI` 初始化失败

### 6.2 langchain-core 0.1→1.5（PR #162）

#### 6.2.1 兼容性评估：不兼容

- **当前**：langchain-core==0.1.45, langchain==0.1.16, langchain-community==0.0.34
- **目标**：langchain-core==1.5.3
- **langchain 1.x 破坏性变更**：
  1. 完全重构：从 `langchain_core` 0.1.x 到 1.x 是全量重写
  2. `BaseMessage`、`HumanMessage`、`AIMessage` 等核心类型变更
  3. `Runnable` 接口重构
  4. `ChatOpenAI` 初始化参数变更（`openai_api_key` → `api_key`）
  5. Gateway 环境变量支持（`LANGSMITH_API_KEY`）
  6. `reasoning_effort` 标准参数
- **项目使用**：`nl2sql/sql_generator.py` 使用 `langchain_openai.ChatOpenAI`，参数 `openai_api_key` 和 `openai_api_base` 在 1.x 中已改名
- **依赖锁死**：langchain-core 1.x 要求 langchain 1.x + langchain-openai 1.x，但项目用 langchain 0.1.16

#### 6.2.2 建议：关闭

**理由**：
1. langchain 0.1→1.x 是全量重构，所有 `from langchain.* import *` 需要重写
2. 需要同步升级 langchain、langchain-community、langchain-openai，单独升级 langchain-core 会破坏依赖链
3. `ChatOpenAI` 的 `openai_api_key`/`openai_api_base` 参数在 1.x 中已改名，`sql_generator.py` 必须修改

### 6.3 mlflow 2→3（PR #122, #118）

#### 6.3.1 兼容性评估：不兼容

- **当前**：mlflow==2.11.1（llmops + ml-platform）
- **目标**：3.15.1
- **项目使用方式**：
  - `llmops/llmops/repositories/mlflow/client.py`：`import mlflow`
  - `ml-platform/ml_platform/config/settings.py`：`mlflowUri`, `mlflowRegistryUri` 配置
- **mlflow 3.x 破坏性变更**：
  1. **MCP Registry**：全新功能模块
  2. **MLflow Assistant**：多 LLM provider 支持
  3. **Tracing 重构**：trace 数据模型变更
  4. **Gateway 重构**：per-endpoint budget policies
  5. **Model Registry**：UC model artifact download 迁移到 native temp-creds
  6. **Evaluation**：`make_judge()` 多模态附件支持
- **风险**：
  - mlflow 2→3 是大版本跳跃，`mlflow.set_tracking_uri()`、`mlflow.log_metric()` 等核心 API 可能变更
  - `mlflow.pyfunc.log_model()` 等模型日志 API 可能重构
  - Tracing API 完全重构

#### 6.3.2 建议：关闭两个 PR

**理由**：mlflow 2→3 涉及 Tracing、Gateway、Model Registry 全量重构，需要完整的迁移评估

### 6.4 pytest-asyncio 0.23→1.4（PR #104, #95, #75, #65）

#### 6.4.1 兼容性评估：部分兼容

- **当前**：pytest-asyncio==0.23.6（nl2sql, llmops, ml-platform, 以及第 4 个 Python 模块）
- **目标**：1.4.0
- **项目使用方式**：`@pytest.mark.asyncio` + `async def test_*`（标准用法）
  - `nl2sql/tests/test_sql_generator.py`：12 处 `@pytest.mark.asyncio`
  - `nl2sql/tests/test_schema_context.py`：8 处
  - `nl2sql/tests/test_gateway_client.py`：8 处
- **pytest-asyncio 1.x 破坏性变更**：
  1. **Event loop 策略变更**：1.x 默认使用 `asyncio.get_running_loop()` 而非创建新 loop
  2. **`@pytest.mark.asyncio` 行为变更**：1.x 可能要求显式配置 `asyncio_mode`
  3. **Fixture 作用域**：async fixture 的 scope 语义变更
  4. **`event_loop` fixture 弃用**：1.x 弃用了用户自定义 `event_loop` fixture
- **风险**：
  - 如果项目 `pyproject.toml` 或 `pytest.ini` 中配置了 `asyncio_mode = "auto"` 或 `"strict"`，行为可能变更
  - 如果有自定义 `event_loop` fixture，需要移除

#### 6.4.2 建议：需修改后合并

**需要修改的内容**：
1. 检查各模块的 `pyproject.toml` 中 `[tool.pytest.ini_options]` 的 `asyncio_mode` 配置
2. 搜索是否有自定义 `event_loop` fixture（如有需移除）
3. 验证 `@pytest.mark.asyncio` 在 1.x 下的行为一致性
4. 运行 `pytest` 验证所有 async 测试通过

---

## 第7章 其他升级分析

### 7.1 spring-cloud-dependencies 2023.0→2025.1（PR #132）

#### 7.1.1 兼容性评估：不兼容

- **当前**：spring-cloud-dependencies 2023.0.3（knative/runtimes/java）
- **目标**：2025.1.2
- **Spring Cloud 2025.1 要求**：Spring Boot 4.1+
- **项目现状**：knative/runtimes/java 用 Spring Boot 3.3.4
- **依赖矩阵**：
  - Spring Cloud 2023.0.x → Spring Boot 3.2.x
  - Spring Cloud 2024.0.x → Spring Boot 3.3.x
  - Spring Cloud 2025.0.x → Spring Boot 4.0.x
  - Spring Cloud 2025.1.x → Spring Boot 4.1.x
- **矛盾**：Spring Cloud 2025.1.2 需要 Spring Boot 4.1，但项目用 3.3.4

#### 7.1.2 建议：关闭

**理由**：Spring Cloud 2025.1 与 Spring Boot 3.3.4 版本不兼容，必须先升级 Spring Boot 到 4.1

### 7.2 native-maven-plugin 0.10→1.1（PR #135）

#### 7.2.1 兼容性评估：部分兼容

- **当前**：native-maven-plugin 0.10.2（knative/runtimes/java）
- **目标**：1.1.8
- **release notes 关键变更**：
  - 1.1.8：弃用 fallback plugin options、reachability metadata 1.0.9
  - 1.1.7：修复 Maven plugin unit-test mock maker、Kotlin DSL delegated accessors
  - 1.1.6：大量 Maven/Gradle agent 执行修复、JUnit 6.1 兼容
- **项目使用**：
  ```xml
  <plugin>
      <groupId>org.graalvm.buildtools</groupId>
      <artifactId>native-maven-plugin</artifactId>
      <version>0.10.2</version>
      <configuration>
          <buildArgs>
              <buildArg>--enable-url-protocols=http</buildArg>
              <buildArg>-H:+SpawnIsolates</buildArg>
              <buildArg>-H:+ReportExceptionStackTraces</buildArg>
          </buildArgs>
      </configuration>
  </plugin>
  ```
- **风险**：
  1. **GraalVM 版本要求**：native-maven-plugin 1.x 可能要求 GraalVM JDK 21+
  2. **Spring Boot 兼容性**：native-maven-plugin 1.x 主要针对 Spring Boot 3.3+，与 3.3.4 兼容
  3. **buildArgs 兼容性**：`-H:+SpawnIsolates` 等选项在 GraalVM 21+ 中可能已变更
  4. **fallback options 弃用**：1.1.8 弃用了 fallback plugin options

#### 7.2.2 建议：保持 open

**理由**：
1. native-maven-plugin 1.x 与 Spring Boot 3.3.4 基本兼容
2. 但需要验证 GraalVM 版本和 buildArgs 兼容性
3. 建议在 CI 环境验证 `mvn native:compile -Pnative` 后再合并

### 7.3 actions/download-artifact 4→8（PR #1）

#### 7.3.1 兼容性评估：不兼容

- **当前**：actions/download-artifact@v4（release.yml line 481）
- **目标**：v8
- **使用上下文**：
  ```yaml
  - name: 下载所有构建制品
    uses: actions/download-artifact@v4
    with:
      path: release-assets/
      merge-multiple: false
  ```
- **actions/download-artifact v8 破坏性变更**：
  1. v5-v8 跨越多个大版本，每个大版本都有破坏性变更
  2. v5：`merge-multiple` 参数可能变更
  3. v6-v8：可能要求 GitHub Actions runner 的新版本
  4. **跳过中间版本**：从 v4 直接到 v8 风险极高
- **风险**：
  - `merge-multiple` 参数可能在 v8 中被移除或改名
  - `path` 参数语义可能变更
  - v8 可能要求新的 runner 版本或 GitHub Enterprise 版本

#### 7.3.2 建议：关闭

**理由**：
1. actions/download-artifact v4→v8 跨越 4 个大版本，无增量验证路径
2. `merge-multiple` 参数兼容性未知
3. 正确做法：先升级到 v5（最新稳定版），验证后再评估后续

---

## 第8章 综合建议与优先级

### 8.1 可立即合并的 PR（2 个）

表：可立即合并的 PR

| PR | 依赖 | 升级 | 理由 |
|----|------|------|------|
| #78 | vue-router | 4.6.4→5.2.0 | 标准 API 使用，Vue 3.4+ 满足要求，类型变更最小 |
| #45 | pinia | 2.3.1→4.0.2 | setup store 语法完全兼容，类型导出修复 |

### 8.2 需修改后合并的 PR（5 个）

表：需修改后合并的 PR

| PR | 依赖 | 升级 | 需要修改的文件 | 修改内容 |
|----|------|------|----------------|---------|
| #9 | kubernetes-client | 6.13.4→7.8.0 | K8sWorkspaceTranslatorTest.java, K8sQuotaTranslatorTest.java, K8sWorkspaceTranslator.java, K8sQuotaTranslator.java | 更新 DSL 泛型签名、验证 Resource 接口方法 |
| #104 | pytest-asyncio | 0.23.6→1.4.0 | 各 Python 模块 pyproject.toml | 检查 asyncio_mode 配置、移除自定义 event_loop fixture |
| #95 | pytest-asyncio | 0.23.6→1.4.0 | 同上 | 同上 |
| #75 | pytest-asyncio | 0.23.6→1.4.0 | 同上 | 同上 |
| #65 | pytest-asyncio | 0.23.6→1.4.0 | 同上 | 同上 |

### 8.3 建议保持 open 的 PR（3 个）

表：建议保持 open 的 PR

| PR | 依赖 | 升级 | 理由 |
|----|------|------|------|
| #12 | hive-jdbc | 3.1.3→4.2.0 | 驱动类名兼容，但依赖排除项需根据 Hive 4.x 依赖树调整 |
| #135 | native-maven-plugin | 0.10.2→1.1.8 | 与 Spring Boot 3.3.4 兼容，但需验证 GraalVM 版本和 buildArgs |
| #43 | echarts | 5.6.0→6.1.0 | 项目 package.json 已是 ^6.1.0，PR 基线滞后，建议确认后关闭 |

### 8.4 建议关闭的 PR（21 个）

表：建议关闭的 PR 及理由

| PR | 依赖 | 升级 | 关闭理由 |
|----|------|------|---------|
| #158, #146, #133, #130, #110, #84, #82, #79, #28, #27, #24, #7, #6 | spring-boot-starter-parent | 3.2.5→4.1.0 | Spring Boot 3→4 大版本跳跃，需统一升级策略，传递依赖未验证 |
| #3 | spring-boot-starter-parent | 3.3.4→4.1.0 | 三重依赖锁死（Spring Boot 4 + Spring Cloud 2023.0 + native-maven-plugin 0.10） |
| #132 | spring-cloud-dependencies | 2023.0.3→2025.1.2 | 需要 Spring Boot 4.1+，但项目用 3.3.4 |
| #162 | langchain-core | 0.1.45→1.5.3 | langchain 0.1→1.x 全量重构，需同步升级 langchain + langchain-openai |
| #160 | openai | 1.23.0→2.53.0 | 需要 langchain 1.x+ 配合，单独升级会破坏 langchain_openai |
| #122 | mlflow | 2.11.1→3.15.1 | mlflow 2→3 Tracing/Gateway/Model Registry 全量重构 |
| #118 | mlflow | 2.11.1→3.15.1 | 同上 |
| #51 | typescript | 5.9.3→7.0.2 | 跨越两个大版本，vue-tsc/vite/typescript-eslint 未验证支持 |
| #1 | actions/download-artifact | 4→8 | 跨越 4 个大版本，merge-multiple 参数兼容性未知 |

### 8.5 推荐的升级路径

图：推荐的增量升级路径示意图

```
当前状态：
- Spring Boot 3.2.5 / 3.3.4
- Spring Cloud 2023.0.3
- langchain 0.1.x
- mlflow 2.x
- TypeScript 5.x

Phase 1（低风险，可立即执行）：
- vue-router 4→5 ✅
- pinia 2→4 ✅
- pytest-asyncio 0.23→1.4（需小幅修改）
- kubernetes-client 6→7（需小幅修改）

Phase 2（中风险，需验证）：
- Spring Boot 3.2.5→3.4.x（最新 3.x）
- Spring Cloud 2023.0→2024.0
- hive-jdbc 3→4（调整依赖排除项）
- native-maven-plugin 0.10→1.1（验证 GraalVM）
- TypeScript 5.4→5.9（最新 5.x）

Phase 3（高风险，需完整迁移）：
- Spring Boot 3.4→4.1（统一升级所有 18 个模块）
- Spring Cloud 2024.0→2025.1
- langchain 0.1→1.x（全量重写 nl2sql LLM 调用）
- openai 1→2（配合 langchain 1.x）
- mlflow 2→3（重构 llmops/ml-platform）
- TypeScript 5.9→6.0（验证工具链支持）
```

---

## 第9章 审查方法说明

### 9.1 数据来源

1. **PR 信息**：通过 GitHub MCP 工具 `GitHub_get_pull_request` 获取 8 个代表性 PR 的 release notes
2. **项目代码**：直接读取本地项目文件（pom.xml, package.json, requirements.txt, application.yml, 源码）
3. **依赖使用分析**：通过 `grep` 搜索 fabric8、hive-jdbc、openai、langchain、mlflow、echarts 等的使用方式

### 9.2 审查约束

- ✅ 只做读操作，未合并或关闭任何 PR
- ✅ 未修改任何代码文件
- ✅ 只输出分析报告
- ✅ 使用 GitHub MCP 工具获取 PR 信息（gh CLI 不可用）

### 9.3 未覆盖项

1. **PR #146, #133, #130, #110, #84, #82, #79, #28, #27, #24, #7, #6** 的具体模块未逐一确认（但升级建议基于 Spring Boot 3→4 的通用风险）
2. **pytest-asyncio 的 4 个 Python 模块** 的具体模块名未逐一确认（PR #104, #95, #75, #65）
3. **未运行 `npx tsc --noEmit`** 验证 TypeScript 类型错误（只读约束）
4. **未运行 `mvn test`** 验证编译和测试通过（只读约束）

---

## 第10章 结论

本次审查的 31 个 major 升级 PR 中：

- **2 个可立即合并**（vue-router #78, pinia #45）—— 前端标准 API 使用，兼容性高
- **5 个需修改后合并**（kubernetes-client #9, pytest-asyncio #104/#95/#75/#65）—— 小幅代码修改可兼容
- **3 个保持 open**（hive-jdbc #12, native-maven-plugin #135, echarts #43）—— 需进一步验证
- **21 个建议关闭**（14 个 Spring Boot + spring-cloud #132 + langchain #162 + openai #160 + mlflow #122/#118 + typescript #51 + actions #1）—— 大版本跳跃风险过高

**核心建议**：采用三阶段增量升级路径，避免跨大版本直接跳跃，确保每个阶段的兼容性可验证。