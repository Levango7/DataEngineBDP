"""数据引擎大数据平台（DataEngineBDP）跨领域端到端（E2E）集成测试套件。

本包覆盖：
- 28 项需求的 E2E 验收测试（P0/P1/P2 三档）
- 10 个跨领域全链路场景（NL2SQL → 联邦查询 → 物化视图 → AI 解读 等）
- E2E 测试报告生成器（HTML + JSON）
- 一键运行脚本 run_e2e.sh

设计原则：
- 复用 ``tests/integration/docker/conftest.py`` 的服务管理能力；
- 服务不可用时自动 skip，避免在 CI 无 Docker 环境中报错；
- 跨领域测试模拟完整业务流程，串接多个平台模块的 API。
"""