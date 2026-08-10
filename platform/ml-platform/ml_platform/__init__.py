"""ML Platform - 数据引擎大数据平台 L4.5.6 机器学习平台.

模块层次：
    interfaces/   - 抽象接口（MLBackend / FeatureStore / ExperimentStore）
    repositories/ - 接口实现（Mock / Sklearn）
    services/     - 业务编排
    api/          - FastAPI 路由
    models/       - Pydantic 数据模型
    config/       - 环境变量配置
"""

__version__ = "0.1.0"
