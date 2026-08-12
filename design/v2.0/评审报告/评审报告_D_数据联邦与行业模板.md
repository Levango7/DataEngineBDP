# 评审报告D：V2.0数据联邦与行业模板

> 评审员：评审员D
> 评审日期：2026-08-06
> 评审对象：
> 1. `design/v2.0/详细设计/V2.0_数据联邦与实时数仓详细设计.md`（2669行）
> 2. `design/v2.0/详细设计/V2.0_行业模板与其他增强详细设计.md`（3342行）
> V1.0基准：交互查询(L2.4 Trino)、流计算(L2.3 Flink)、OLAP(L2.5 Doris)、湖仓集一体(L2.6)、统一SQL网关(L2.7)、行业模板(L5.3)、资产流通(L5.6)、开放API(L5.5)、安全合规(X2)、身份权限(X1)、运维观测(X3)

## 一、架构一致性

- [严重度: High] V2.0行业模板基线描述与V1.0实际不符
  - 位置：V2.0_行业模板与其他增强详细设计.md 第1章 §1.1（第12行）、§1.3（第53行）
  - 问题：V2.0§1.1声明"V1.0 基线：industry-templates（3 行业）"，§1.3演进类型标注"扩展（新增 5 行业 + 增强现有 3 行业）"。但V1.0 L5.3《行业应用模板详细设计》§3模板清单实际包含9个模板覆盖5个行业：金融（fin-aml-graph, fin-risk-scorecard）、政务（gov-pop-econ, gov-one-net-gov）、制造（mfg-line-quality, mfg-supply-chain）、零售（retail-traffic-heat, retail-inventory-opt）、物联网（iot-device-health）。V2.0将V1.0基线错记为"3行业"，会导致演进对照、兼容性承诺、升级路径均失真。
  - 建议：将§1.1修正为"V1.0 基线：industry-templates（5 行业 9 模板）"，§1.3演进类型修正为"扩展（新增能源行业 + 增强现有金融/制造/零售/政务4行业 + 物联网模板去留说明）"，并在§1.1或§1.3补充物联网模板在V2.0的处置策略（保留/废弃/合并）。

- [严重度: High] V2.0行业范围与V1.0不一致，物联网模板去留未声明
  - 位置：V2.0_行业模板与其他增强详细设计.md §1.1（第12行）、第2-6章章节标题
  - 问题：V1.0 L5.3含物联网行业（iot-device-health 设备健康度模板），V2.0§1.1声明扩展"金融、制造、零售、能源、政务"五大行业，第2-6章仅覆盖这5行业，物联网章节缺失。V2.0既未声明物联网模板保留兼容，也未声明废弃，造成V1.0租户已安装的iot-device-health模板升级路径不明，违反V2.0§12.4"V1.0 → V2.0 支持 Helm upgrade，保留租户定制"的兼容承诺。
  - 建议：在第1章补充"物联网行业模板（iot-device-health）在V2.0保留并增强，与能源行业模板共享IoTDB时序底座"，或明确声明废弃并提供迁移指南；同时在第6章后补"第6.5章 物联网行业模板（V2.0增强）"。

- [严重度: Medium] V2.0声称新增IoTDBAdapter，但V1.0 SQL网关已有IoTDB路由
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §2.4（第335行）、V1.0 L2.7 统一SQL网关§2架构图（第40行）
  - 问题：V2.0§2.4联邦适配器清单标注"IoTDBAdapter | 新增，时序联邦查询"和"ESAdapter | 新增，检索联邦查询"。但V1.0 L2.7统一SQL网关§2总体架构图已含"[IoTDB] (时序)"路由，§3组件职责表也已列出IoTDB Adapter。V2.0将IoTDBAdapter标为"新增"与V1.0基线矛盾，会使读者误以为V1.0无IoTDB联邦能力。
  - 建议：将§2.4 IoTDBAdapter的"V2.0增强"列改为"增强（V1.0已有路由，V2.0新增谓词/投影/降采样下推）"，ESAdapter可保留"新增"。同时核实V1.0 12个Adapter清单是否含IoTDB，统一口径。

- [严重度: Medium] Karmada是V2.0全新引入，V1.0无Karmada基线，与SKE兼容性未声明
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §3.2（第587行）、§3.5.2（第746行）、§3.8（第851行）
  - 问题：V2.0§3跨集群联邦查询引入Karmada控制面（PropagationPolicy/OverridePolicy），§3.8集成方案提到"复用 L0.5 跨环境供给抽象"和"L0.11 封装层"，但V1.0 L0.5跨环境供给抽象是"NodePool/StoragePool/NetworkPool三原语"，未涉及Karmada多集群调度。Karmada是CNCF项目，对宿主K8s版本有要求，V2.0未声明Karmada与V1.0自研SKE的K8s版本兼容性验证结果。
  - 建议：在§3.8或§9.4多环境一致性中补充"Karmada版本X.Y + SKE K8s版本A.B兼容性已验证"声明，或在风险表§9.3新增"Karmada与SKE兼容性"风险项及对策（如"Karmada降级为多集群注册+手动路由"）。

- [严重度: Low] V2.0可观测增强对V1.0基线描述不准确
  - 位置：V2.0_行业模板与其他增强详细设计.md §11.8（第3223行）
  - 问题：V2.0§11.8演进表标注V1.0可视化为"Grafana 单视图"，但V1.0 X3统一运维观测§2总体架构已明确"视图层（双视图）：平台方/客户方"，§1定位价值也提到"平台方看全局水位、客户方只看业务健康"。V2.0将V1.0误记为单视图，会低估V1.0能力、夸大V2.0增强价值。
  - 建议：将§11.8"可视化"行的V1.0列改为"Grafana 双视图（平台方+客户方）"，V2.0列改为"增强双视图（业务指标+SLO+错误预算燃尽图）"，如实反映增强点。

- [严重度: Low] V2.0数据虚拟化与V1.0资产目录逻辑资产关系未明确
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §4.7（第1062行）
  - 问题：V2.0§4.7虚拟表作为资产目录"逻辑资产"，与物理资产建立"逻辑-物理"血缘。V1.0 L3.5资产目录是否已有"逻辑资产"类型未在V2.0中说明，若V1.0资产目录仅支持物理资产，V2.0需扩展资产目录Schema。
  - 建议：在§4.7补充"V1.0 L3.5资产目录新增VIRTUAL_TABLE资产类型，Schema扩展字段：mapping/materialization/cache"，明确资产目录的V2.0演进。

## 二、可行性

- [严重度: High] 跨集群Shuffle Join的Karmada集成实现路径不明确
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §3.5.1（第720行）、§3.5.2（第746行）
  - 问题：V2.0§3.5.1描述"数据不动查询动"，将查询计划分发至各集群本地执行，仅跨集群传输Join结果。§3.5.2提到"利用Karmada的PropagationPolicy与OverridePolicy实现跨集群查询作业调度"。但Karmada是K8s多集群资源调度器（PropagationPolicy调度K8s资源到成员集群），本身不提供数据面Shuffle Join能力。跨集群Shuffle Join需要Trino跨集群Connector（自研）或Karmada+自定义CRD+数据面Worker，V2.0未明确这条实现路径，仅靠Karmada的PropagationPolicy无法完成跨集群Shuffle Join的数据面编排。
  - 建议：在§3.5补充"跨集群Shuffle Join实现方案"小节，明确：(1) Trino跨集群Connector（自研，通过Karmada分配Shuffle Worker）或(2) Karmada+自定义FederatedQuery CRD+数据面Shuffle Worker Pod，并给出技术选型和PoC验证计划。

- [严重度: High] BouncyCastle国密实现是否通过商用密码产品认证未明确
  - 位置：V2.0_行业模板与其他增强详细设计.md §10.3-10.5（第2615-2725行）、§10.10（第2900行）
  - 问题：V2.0§10.3-10.5的SM2/SM3/SM4实现代码使用BouncyCastle的SM2Engine/SM3Digest/SM4Engine（`new SM2Engine()`等）。§10.10提到"KEK存储于KMS（硬件密码机或软件KMS）"。但V1.0 X2安全合规§4密评落地明确"信创环境使用商用密码产品认证组件"，密评要求使用通过国家密码管理局认证的密码产品。BouncyCastle是开源库，其国密实现未通过国密局商用密码产品认证，信创环境强制使用会无法通过密评。
  - 建议：在§10.3-10.5补充"信创环境实现：对接国产密码机（卫士通/信安世纪等）通过PKCS#11/SDF接口调用，BouncyCastle仅用于非信创环境兜底"，并在§10.10明确"信创环境KEK必须存储于通过认证的硬件密码机，BouncyCastle软件实现仅用于非信创环境"。

- [严重度: Medium] 实时入仓端到端延迟≤10s目标与Checkpoint间隔60s矛盾
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §5.5.1（第1316行）、§5.7.2（第1417行）
  - 问题：V2.0§5.5.1延迟分解图显示"Checkpoint提交延迟1-2s"，目标端到端延迟≤10s。但§5.7.2Checkpoint配置`interval: 60s`，即每60s才触发一次Checkpoint。Iceberg快照在Checkpoint提交时才可见，因此从binlog产生到快照可见的实际延迟 = CDC捕获(1-2s) + Flink处理(2-3s) + 等待Checkpoint(0-60s) + 写Iceberg(2-3s) + 快照可见(1s) = 6-69s，最坏情况远超10s目标。§5.5.1的延迟分解未计入"等待Checkpoint"环节。
  - 建议：将§5.7.2Checkpoint间隔改为`interval: 10s`或`5s`以匹配≤10s延迟目标，并在§5.5.1延迟分解图中补充"等待Checkpoint：0-interval秒"环节，重新核算端到端延迟；或在§5.11 SLA表中将端到端延迟目标调整为"P99 ≤ 30s（含Checkpoint间隔）"。

- [严重度: Medium] 完整Calcite联邦优化器自研规则集成熟度风险
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §2.3（第155行）、§2.3.2-2.3.5（第193-296行）
  - 问题：V2.0§2.3引入"完整Calcite联邦优化器"，基于VolcanoPlanner + 自研联邦规则集，实现四类下推+CBO。V1.0 L2.7 SQL网关§3组件职责表显示Planner为"Apache Calcite；基于catalog统计"的基础优化。从"基础优化"升级到"完整联邦优化器 + 谓词/投影/Join/聚合下推 + CBO最优计划选择"是大量自研工作（§2.3.3投影下推规则代码示例仅是冰山一角）。自研规则集的成熟度、正确性、性能需充分测试，否则可能导致查询计划错误（下推到不支持的Source）或优化器计划生成慢（§9.3风险表已列）。
  - 建议：在§2.3补充"自研联邦规则集成熟度评估"：(1) 规则集单元测试覆盖率目标≥90%，(2) TPC-DS跨源版本回归测试，(3) 计划生成时间P99 ≤ 500ms，(4) 与Starburst/Trino原生优化器的性能基准对照；并在§9.6演进路线中将"Calcite联邦优化器GA"推迟到V2.0.0-rc或V2.0.0，alpha/beta版本标注"优化器可能产生次优计划"。

- [严重度: Medium] 流批并发写冲突OCC重试在高温场景可能频繁
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §6.3（第1597行）、§6.10（第1770行）
  - 问题：V2.0§6.3流批写冲突处理采用Iceberg OCC乐观锁，"后提交者重试，基于snapshot重写"。§6.10 SLA目标"流批并发写冲突率 < 1%"。但在高频流写+批回算并发场景（如每秒流写+每小时批回算同一表），OCC重试可能频繁触发，导致流写延迟升高或批回算失败。§6.3提到"批回算覆盖流写：标记回算区间，流写暂停或重定向"，但未说明"暂停流写"对实时性的影响。
  - 建议：在§6.3补充"OCC重试退避策略 + 最大重试次数 + 重试失败告警"，明确"批回算期间流写重定向到分支表（backfill-分支），回算完成后合并"；并在§6.10 SLA中补充"OCC重试延迟P99 ≤ 1s"指标。

- [严重度: Medium] 资产流通未涉及隐私计算，敏感数据跨组织流通可行性不足
  - 位置：V2.0_行业模板与其他增强详细设计.md §7.7.1（第1865行）、§7.10（第1985行）
  - 问题：V2.0§7.7.1安全交付"不copy不download，通过安全沙箱执行查询，结果脱敏返回"。对于敏感数据（如金融风控特征、政务人口数据）跨组织流通场景，"安全沙箱查询+结果脱敏"可能不满足合规要求（数据提供方可能要求"数据不可见查询结果"的隐私计算，如联邦学习/多方安全计算MPC/差分隐私）。V2.0未涉及任何隐私计算框架，资产流通的合规边界不清晰。
  - 建议：在§7.7补充"隐私计算可选集成"小节，说明：(1) V2.0安全沙箱适用于"可披露结果"场景，(2) 对于"不可见数据"场景，V2.1引入联邦学习（基于Flink联邦）或MPC（基于SecretFlow/PrivPy），(3) 在§12.1演进路线新增Phase 7"隐私计算集成"。

- [严重度: Medium] Doris 2.1物化视图自动增量刷新成熟度风险
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §7.4.3（第1910行）、§7.8（第2039行）
  - 问题：V2.0§7.4.3使用`REFRESH ON COMMIT INCREMENTAL`语法，§7.8基于外表建物化视图自动增量刷新。Doris 2.1的物化视图自动刷新（特别是基于Iceberg External Catalog的增量刷新）是较新特性，社区版成熟度需验证。Doris 2.1官方文档显示物化视图自动刷新主要支持Internal Catalog，基于External Catalog的增量刷新可能有限制（如不支持跨Catalog Join物化视图自动刷新）。
  - 建议：在§7.4补充"Doris 2.1物化视图自动刷新能力边界"：(1) 明确支持的场景（Internal Catalog增量刷新、External Catalog全量刷新），(2) 不支持的场景（跨Catalog Join自动增量刷新，需退化为定时全量刷新或Flink CDC直接写Doris），(3) 在§9.3风险表新增"Doris MV自动刷新限制"风险及对策。

- [严重度: Medium] 5行业模板第三方系统对接依赖未明确
  - 位置：V2.0_行业模板与其他增强详细设计.md 第2-6章各行业模板§x.8集成方案
  - 问题：5行业模板均依赖客户第三方系统：金融需监管报送系统（EAST/1104/反洗钱报送接口）、制造需MES/ERP系统（工单/物料数据）、零售需POS/CRM系统（订单/会员数据）、能源需SCADA/EMS系统（能耗采集）、政务需政务数据汇聚平台。V2.0各行业§x.8集成方案仅提到"industry-templates集成：新增xxx.py模板文件"，未明确第三方系统对接规范（API契约/数据格式/网络打通），模板无法真正"开箱即用"。
  - 建议：在每个行业模板§x.8补充"第三方系统对接规范"小节：(1) 数据源API契约（如MES工单API、POS订单API），(2) 网络打通方案（NetworkPolicy放行、VPN/专线），(3) 数据格式适配（SeaTunnel Connector或自定义Adapter），(4) 在values.yaml中参数化数据源连接信息。

- [严重度: Low] 实时治理质量规则流式校验实时性依赖窗口大小
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §8.6.2（第2336行）
  - 问题：V2.0§8.6.2流式质量规则示例2"用户数波动不超过15%"使用`HOP(event_time, INTERVAL '5' MINUTE, INTERVAL '1' HOUR)`跳跃窗口，窗口聚合结果需等待窗口关闭才能输出，实际质量校验延迟 = 窗口长度(1小时) + Flink处理延迟，远超§8.8"质量校验延迟 < 10s"目标。简单规则（如金额非负）可秒级校验，但窗口聚合类规则延迟取决于窗口大小。
  - 建议：在§8.6.2补充"流式质量规则延迟分级"：(1) 简单规则（行级断言）延迟<10s，(2) 窗口聚合规则延迟=窗口长度+处理延迟，(3) 在§8.8 SLA表将"质量校验延迟"细分为"简单规则<10s / 窗口规则=窗口长度+10s"。

## 三、依赖风险

- [严重度: High] Karmada与V1.0自研SKE的K8s版本兼容性未验证
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §3.5.2（第746行）、§9.4（第2573行）
  - 问题：V2.0§3.5.2使用Karmada的`policy.karmada.io/v1alpha1` API，Karmada对宿主K8s版本有要求（Karmada 1.x要求K8s 1.22+）。V1.0自研SKE的K8s版本未在V2.0中声明，若SKE K8s版本低于Karmada要求，跨集群联邦查询无法落地。§9.4多环境一致性表标注"跨集群联邦 | Karmada多集群 | 同 | 同 | 同"，但未验证Karmada与SKE的实际兼容性。
  - 建议：在§9.4补充"Karmada版本 + SKE K8s版本兼容性矩阵"，明确Karmada X.Y要求K8s A.B+，SKE当前K8s版本满足；或在风险表§9.3新增"Karmada与SKE不兼容"风险及对策（如"SKE升级K8s到1.22+或Karmada降级为多集群注册+手动路由"）。

- [严重度: High] BouncyCastle国密实现信创认证状态未明确
  - 位置：V2.0_行业模板与其他增强详细设计.md §10.3-10.5（第2615-2725行）
  - 问题：V2.0§10.3-10.5国密实现代码使用BouncyCastle（`SM2Engine/SM3Digest/SM4Engine`来自BouncyCastle或其分支）。国家密码管理局《商用密码产品认证》要求信创环境使用通过认证的密码产品（硬件密码机或认证的软件密码模块）。BouncyCastle是开源库，其国密实现未通过国密局商用密码产品认证，信创环境强制使用会无法通过密评（GM/T 0054）。V1.0 X2§4已明确"信创环境使用商用密码产品认证组件"，V2.0代码示例与V1.0合规要求矛盾。
  - 建议：在§10.3-10.5代码示例前补充"实现说明：信创环境通过SDF/PKCS#11接口调用国产密码机（卫士通/信安世纪），BouncyCastle代码仅用于非信创环境兜底"，并在§10.10明确"信创环境必须使用认证硬件密码机，BouncyCastle软件实现不得用于信创生产环境"。

- [严重度: Medium] Iceberg V1→V2升级需确认Spark/Trino/Doris支持版本
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §5.4（第1276行）、§5.10（第1507行）
  - 问题：V2.0§5.4升级Iceberg V1→V2表格式（行级UPSERT + equality-delete + position-delete）。§5.10提到"Spark/Trino/Doris自动识别V2表"。Iceberg V2表格式（format-version=2）需要：Iceberg 0.14+（V2表格式稳定）、Spark 3.3+（V2读写）、Trino 417+（V2读取）、Doris 2.0+（V2 External Catalog读取）。V1.0基线是Iceberg V1 + Spark 3.5 + Trino 428 + Doris 2.1，引擎版本满足，但V1.0 Iceberg库版本未声明，若V1.0 Iceberg < 0.14则V2表格式不可用。
  - 建议：在§5.10补充"Iceberg V2依赖版本矩阵"：Iceberg 0.14+ / Spark 3.5 / Trino 428 / Doris 2.1，并明确V1.0 Iceberg库版本是否满足，若不满足需在V2.0升级Iceberg库。

- [严重度: Medium] Flink CDC 3.0与Flink 1.18兼容性需确认
  - 位置) V2.0_数据联邦与实时数仓详细设计.md §5.3.1（第1214行）、§5.10（第1509行）
  - 问题：V2.0§5.3.1使用Flink CDC 3.0连接器（MySQL/PG/Oracle CDC 3.0），§5.10提到"复用V1.0 Flink Kubernetes Operator，CDC连接器从V1升级到3.0"。Flink CDC 3.0（2024年发布）要求Flink 1.17+，V1.0 Flink 1.18满足。但Flink CDC 3.0是较大版本升级（从2.x到3.0 API变更），V1.0 CDC作业（基于Flink CDC 2.x）迁移到3.0需重写作业SQL/Connector配置，V2.0未说明迁移路径。
  - 建议：在§5.10补充"Flink CDC 2.x→3.0迁移指南"：(1) API差异（3.0支持增量快照无锁全量），(2) 作业SQL兼容性（3.0语法变更），(3) V1.0 CDC作业升级到V2.0的自动迁移工具或手动改写步骤。

- [严重度: Medium] Doris 2.1物化视图自动刷新限制未明确
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §7.4.3（第1910行）、§7.8（第2039行）
  - 问题：V2.0§7.8基于Iceberg External Catalog建物化视图`REFRESH ON COMMIT INCREMENTAL`。Doris 2.1物化视图自动刷新有以下已知限制：(1) 基于Internal Catalog的物化视图支持自动增量刷新，(2) 基于External Catalog（Iceberg/Hive）的物化视图自动刷新在Doris 2.1社区版可能仅支持全量刷新或不支持ON COMMIT触发，(3) 跨Catalog Join物化视图（§7.8示例Iceberg JOIN Doris）自动刷新可能不支持。V2.0未明确这些限制，可能导致物化视图自动同步功能在落地时受限。
  - 建议：在§7.4补充"Doris 2.1物化视图自动刷新能力矩阵"：(1) Internal Catalog + 增量刷新：支持，(2) External Catalog + 全量刷新：支持，(3) External Catalog + 增量刷新 + ON COMMIT：需验证或降级为定时刷新，(4) 跨Catalog Join物化视图：需Flink CDC直接写Doris替代。

- [严重度: Medium] 5行业模板第三方系统对接依赖客户系统API
  - 位置：V2.0_行业模板与其他增强详细设计.md 第2-6章各行业模板
  - 问题：5行业模板均依赖客户第三方系统API/数据库：金融（监管报送系统EAST/1104接口）、制造（MES工单API/ERP物料API）、零售（POS订单API/CRM会员API）、能源（SCADA能耗API/EMS系统）、政务（政务数据汇聚平台API）。V2.0各行业DDL使用`iceberg.\`tenant/{tid}/industry\`.dwd_xxx`表，但未说明这些表的源数据如何从第三方系统采集到ODS层（SeaTunnel Connector？自定义Adapter？）。模板的"开箱即用"承诺与第三方对接现实存在差距。
  - 建议：在每个行业模板补充"数据采集对接"小节：(1) 第三方系统API/数据库清单，(2) SeaTunnel Connector选型（如MySQL CDC/JDBC Source）或自定义Adapter，(3) values.yaml中参数化数据源连接，(4) 模板README中明确"需客户提供数据源访问权限"。

- [严重度: Low] Elasticsearch适配器新增依赖
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §2.4（第346行）
  - 问题：V2.0§2.4新增ESAdapter（Elasticsearch联邦适配器），需Elasticsearch Java客户端依赖。V1.0 L2.10多模型引擎已含搜索（ES/OpenSearch）能力，但V1.0 SQL网关是否已有ES Adapter未明确。若V1.0无ES Adapter，V2.0新增需引入ES客户端依赖（elasticsearch-rest-high-level-client或new Java Client），增加sql-gateway镜像体积和依赖管理复杂度。
  - 建议：在§2.4补充"ESAdapter依赖：Elasticsearch Java Client 8.x，与V1.0 L2.10多模型搜索引擎复用ES集群"，并确认V1.0 L2.10是否已提供ES联邦能力，避免重复建设。

- [严重度: Low] NebulaGraph版本需与V1.0一致
  - 位置：V2.0_数据联邦与实时数仓详细设计.md §8.5.2（第2285行）
  - 问题：V2.0§8.5.2使用NebulaGraph存储血缘，nGQL语法（INSERT VERTEX/INSERT EDGE）。V1.0修复组5已将Neo4j改为NebulaGraph（L4.5.4），但V1.0 NebulaGraph版本未在V2.0中声明。nGQL语法在不同NebulaGraph版本（2.x vs 3.x）有差异（如3.x的INSERT EDGE语法），V2.0代码示例需明确版本。
  - 建议：在§8.5.2补充"NebulaGraph版本：3.x（与V1.0 L4.5.4一致）"，并确认nGQL语法示例与NebulaGraph 3.x兼容。

## 四、总结

- High问题数：5
- Medium问题数：11
- Low问题数：5
- 总体评价：V2.0数据联邦与实时数仓、行业模板与其他增强两份详细设计文档整体架构与V1.0演进路径清晰，跨源联邦查询增强（Calcite联邦优化器）、实时入仓（Flink CDC→Iceberg V2）、流批一体、5行业模板、国密CryptoSpiFactory、安全合规SecurityFacade、可观测增强等设计均复用V1.0基线组件（Trino/Flink/Doris/Iceberg/Keycloak/ rule-engine/asset-exchange/open-api-catalog），向后兼容性考虑充分（API保留V1.0端点+新增V2.0端点，Helm Chart兼容V1.0 values）。但存在5个High问题需重点修复：(1) V2.0行业模板基线描述与V1.0实际5行业9模板不符（误记为3行业），(2) 物联网模板去留未声明违反兼容承诺，(3) 跨集群Shuffle Join的Karmada集成实现路径不明确（Karmada本身不提供数据面Shuffle），(4) BouncyCastle国密实现未通过商用密码产品认证，信创环境无法通过密评，(5) Karmada与V1.0自研SKE的K8s版本兼容性未验证。可行性方面，实时入仓≤10s延迟目标与Checkpoint间隔60s矛盾、完整Calcite联邦优化器自研成熟度、Doris 2.1物化视图自动刷新限制、资产流通未涉及隐私计算等Medium问题需在落地前补充技术选型验证和PoC。建议V2.0在alpha/beta阶段重点验证跨集群Shuffle Join实现、Calcite优化器正确性、Doris MV自动刷新边界、国密硬件密码机对接，并修正行业模板基线描述和物联网模板去留声明以确保V1.0→V2.0兼容承诺可信。