# 规划中组件（Planned）

> 本目录存放从 `platform/` 降级为"规划中"的骨架组件。这些组件在骨架冻结期（v2.1 ~ v2.2）被判定为无近期激活需求，退出 CI 构建矩阵与独立部署单元计数。

## 降级原因与迁移记录

| 组件 | 原路径 | 迁移日期 | 降级原因 | 复活条件 |
| --- | --- | --- | --- | --- |
| model-finetuning | `platform/model-finetuning/` | 2026-09-12 | 真实微调需 GPU 节点池与训练框架环境，当前全 Mock，无近期需求 | 出现真实微调需求且 GPU 资源就绪时迁回 `platform/` |
| registry | `platform/registry/` | 2026-09-12 | DeploymentManager 假闭环（mock_mode 伪造 running），无真实模型部署需求 | 出现真实模型注册 / 部署需求时迁回 `platform/` |
| knative | `platform/knative/` | 2026-09-12 | 函数运行时模板集，需 Knative 就绪集群，当前无 Serverless 真实需求 | 出现事件驱动 / Serverless 真实需求时迁回 `platform/` |

## 与骨架冻结期的关系

本目录是 [骨架冻结期](../../ROADMAP.md#骨架冻结期v21--v22) 处置决策中"降级为规划"类的落地：
- 组件代码保留但不参与 CI 构建（`find platform` 不再发现它们）。
- 组件不计入 [组件成熟度矩阵](../../docs/component-maturity.md) 的活跃组件数。
- v2.2 验收时复审：确认无激活需求方可长期搁置，否则按复活条件迁回 `platform/`。