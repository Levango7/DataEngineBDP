# Sprint 4.1 执行报告（Phase 4·能源/政务行业模板落地）

**日期**：2026-09-03
**范围**：Phase 4 首个 Sprint——实现 Sprint 3.2 遗留的两项行业模板（能源/政务）

## 1. 目标与决策

Sprint 3.2 遗留输入：P2-27 能源模板 / P2-28 政务模板 仅验证"域级契约"（分类/列表 ≥7），真实专属模板未实现。Sprint 4.1 落地两个真实模板，P2-27/28 从域级契约升级为专属模板验证。

关键决策：以现有 7 模板（trans_traffic 等）为仿写基准，遵循 Template 模型（meta+parameters+dataFlow+computeLogic+visualization+readme+validationSchema）五件套结构。

## 2. 交付物

### 2.1 ENERGY 行业枚举（base.py）

Industry 枚举新增 `ENERGY = "energy"`（已有 GOVERNMENT/IOT）。

### 2.2 能源行业模板（energy_iot.py）

| 项 | 内容 |
|----|------|
| id | energy-iot-monitor |
| 行业 | Industry.ENERGY |
| 场景 | 智能电厂/新能源场站物联监控 + 能耗分析 + 负荷预测 + 告警 |
| 参数 | 5 个（IoTDB 物联时序库 / 温度阈值 / 振动阈值 / 预测时域 / cron） |
| 数据流 | 物联遥测 → 能耗聚合 → 能效分析 → 负荷预测 → 异常告警 |
| 计算逻辑 | SQL 能耗聚合 + 能效计算 + 负荷预测模型 + 告警规则 |
| 可视化 | 能耗趋势/机组能效/负荷预测/设备告警 4 面板 |

### 2.3 政务行业模板（gov_public_services.py）

| 项 | 内容 |
|----|------|
| id | gov-public-services |
| 行业 | Industry.GOVERNMENT |
| 场景 | 政务数据共享 + 一网通办办理分析 + 民生诉求监测 + 效能看板 |
| 参数 | 5 个（政务事项库 / 民生诉求 API / 共享责任部门 / 在线率目标 / cron） |
| 数据流 | 事项接入 → 数据共享目录 → 办理分析 → 民生监测 → 效能看板 |
| 计算逻辑 | 共享目录构建 + 办理分析 + 民生热点挖掘 + 效能评分 |
| 可视化 | 在线率趋势/部门效能/诉求热点/共享目录 4 面板 |

### 2.4 注册与测试断言（模板数 7→9）

- templates/__init__.py 注册两个新模板（get_builtin_templates 返回 9）
- 更新断言（4 处文件）：
  - test_api.py：templateCount 7→9、列表长度 7→9、IDs 集合加 energy/gov、categories 行业集合加 energy/government
  - test_new_templates.py：param 表加 energy/gov、总数 7→9、IDs 集合
  - test_template_engine.py：列表长度、ids/industries/categories 集合、注释
  - test_bugfix_regressions.py：列表长度 7→9

### 2.5 P2-27/28 e2e 断言升级（test_e2e_p2_landed.py）

- P2-27：从"分类/列表 ≥7"升级为验证分类含 energy + 模板列表含 energy-iot-monitor
- P2-28：从"详情端点可路由"升级为验证模板列表含 gov-public-services + 行业归属 government + 详情可路由

## 3. 验证结果（全绿）

| 检查 | 结果 |
|------|------|
| industry-templates pytest | ✅ **111 passed**（含新增 2 模板 param 校验） |
| P2 e2e | ✅ test_e2e_p2_landed.py **3/3 passed**（真实运行，见 3.1） |
| 真实 API 联调 | ✅ industry-templates 返回 **9 模板**（含 energy-iot-monitor + gov-public-services）、分类 9 含 energy/government |
| Playwright 模板页 | ✅ template-market.spec.ts **3/3 通过** |
| 契约生成 --check | ✅ 258/258，0 未匹配（模板非 API 路由，无影响） |
| 路由冲突扫描 | ✅ exit 0，413 端点，豁免 20 |
| vite 代理校验 | ✅ 39 条代理，249 调用路径全分流 |

### 3.1 P2 e2e 真实运行验证（4.1 返工补跑）

Sprint 4.1 交付时 P2 e2e 仅有 collect-only 验证。本节为对**本地真实服务栈**的补跑结果（2026-09-03）：

**本地栈**（4 服务，Start-Process 直启 jar/uvicorn）：

| 服务 | 端口 | 启动要点 |
|------|------|---------|
| industry-templates | 8091 | Python，AUTH_MODE=none（9 模板确认） |
| sql-gateway | 18081 | `-Dserver.port=18081` + `JWT_SECRET=it-test-jwt-secret-at-least-32-bytes-long`（与 conftest 夹具同源），H2 文件模式 |
| rule-engine | 18083 | jar 默认端口 |
| encaps-layer | 18080 | `-Dserver.port=18080` + `APP_SECURITY_LOCAL_AUTH_ENABLED=true` + `JWT_SECRET`/`ENCRYPT_KEY`/`K8S_MOCK_ENABLED`（对齐 compose 环境） |

**结果**：`test_e2e_p2_landed.py` **3 passed**（P2-26 数据虚拟化 + P2-27 能源模板 + P2-28 政务模板，87s）。

**排障过程中修复的环境问题**（对后续本地/CI 排障有复用价值）：
1. sql-gateway fat jar 实为 `sql-gateway-0.1.0-exec.jar`（145MB），`-SNAPSHOT` 命名不存在；
2. 端口覆盖需 `-Dserver.port=18081`（`-DSERVER_PORT` 作为系统属性不被 relaxed binding 识别，仅环境变量 `SERVER_PORT` 有效）；
3. sql-gateway `JWT_SECRET` 无默认值且必填——必须注入与测试夹具一致的密钥（conftest 默认 `it-test-jwt-secret-at-least-32-bytes-long`），否则 401；
4. e2e conftest 的 `industry_templates` 默认探测 18096（nightly 映射端口），本地 8091 需 `INDUSTRY_TEMPLATES_URL=http://localhost:8091`；
5. encaps-layer 登录依赖 Keycloak，本地无 Keycloak 必须 `APP_SECURITY_LOCAL_AUTH_ENABLED=true` 走降级登录（compose 同款配置）。

### 3.2 Playwright 全量回归（encaps-layer jar 陈旧问题顺手修复）

全量跑出 **104 passed + 9 failed + 2 skipped**，9 个失败归因后分两类：

- **7 个环境性失败**（非回归）：依赖本地未启动的 nightly 栈服务——encaps-tenant 的 `/admin/kpi` `/account/plans`（404）、business-portal `/business-lines`（500）、asset-exchange/open-api-catalog（连接失败）。nightly 全栈时通过。
- **2 个真问题（已修复）**：api-format 的"错误密码 401"与"参数类型错误 ApiResponse"用例失败，根因是本地 encaps-layer jar 为 01:52 编译的陈旧版本，不含 e7325be9（AuthController 错误密码状态码）修复。重建 jar（`mvn -pl platform/encaps-layer -am package -DskipTests`）+ 开启本地降级登录后重跑：**api-format 17/17 全绿**（401 状态码 + `{"code":40003,"success":false}` 错误格式均验证通过）。

template-market.spec 在新 encaps-layer 下保持 **3/3 通过**。

## 4. 对后续 Sprint 的输入

1. **行业模板库扩至 9 个**（金融/零售/制造/医疗/交通/教育/农牧/能源/政务），覆盖 9 个 Industry 枚举；
2. **P2-27/28 正式闭环**：能源/政务专属模板已实现，e2e 断言为专属验证；
3. **后续 Phase 4 候选**：剩余模板可扩（如 IOT 跨行业模板）、模板部署流程真实化（helm_executor mock→真 helm）、Playwright 剩余页面扩面（cluster/datasources/vector/llmops）；
4. 模板质量基准：新增模板遵循五件套结构 + validationSchema + 参数校验 JSON Schema，可作后续模板模板范本。