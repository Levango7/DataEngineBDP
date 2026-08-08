"""数擎大数据平台全链路性能压测套件。

本包提供：
- 性能基准压测（``test_performance_benchmark``）：覆盖 13 项非功能指标的 15 个压测用例；
- SLA 验证测试（``test_sla_verification``）：10 个 SLA 达标验证用例；
- 性能压测报告生成器（``perf_report``）：HTML + JSON 双格式报告；
- 调优参数集（``tuning_params.yaml``）：各组件推荐调优参数；
- 一键压测脚本（``run_perf.sh``）：启动服务→运行压测→生成报告→清理。

设计原则：
- 所有压测用例在目标服务不可用时自动 skip，避免误报；
- 阈值参数集中维护在 ``conftest.py`` 的 ``PERF_THRESHOLDS`` 中，便于调优；
- 异步压测基于 ``asyncio + httpx``，无需 locust 即可运行；
- 报告生成器可独立调用：``python perf_report.py --output report.html``。
"""