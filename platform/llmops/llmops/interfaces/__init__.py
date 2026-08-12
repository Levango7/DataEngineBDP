"""LLMOps 抽象接口定义.

四大接口对应 L4.5.5 LLMOps 的四个核心能力：
    ModelStore     - 模型注册表（注册/查询/删除/版本）
    ModelTrainer   - 训练/微调（创建任务/状态/取消/列表）
    ModelDeployer  - 部署端点（部署/卸载/状态/列表）
    ModelMonitor   - 监控（指标/延迟/吞吐/错误率）

所有接口均为 async，便于底层对接 MLflow SDK / K8s API 等异步客户端。
"""

from llmops.interfaces.deployer import ModelDeployer
from llmops.interfaces.monitor import ModelMonitor
from llmops.interfaces.store import ModelStore
from llmops.interfaces.trainer import ModelTrainer

__all__ = ["ModelStore", "ModelTrainer", "ModelDeployer", "ModelMonitor"]
