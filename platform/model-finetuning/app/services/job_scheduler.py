"""GPU 节点池调度器.

通过 Kubernetes Volcano 或 Apache YuniKorn 调度 GPU 节点池，
支持多卡亲和性（要求多张 GPU 调度到同一节点）。

调度策略：
1. 根据 GPURequirement（卡数/型号/显存）筛选可用节点
2. 优先选择空闲资源充足的节点（bin-best-fit）
3. 多卡任务要求同节点亲和（避免跨节点 NVLink 缺失）
4. Mock 模式下不实际调用 K8s，返回模拟调度结果

集成方式：
- Volcano：通过 volcano-scheduler.yaml 配置 gpu-bin-packing 策略
- YuniKorn：通过 YuniKorn PodGroup 调度
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

from app.models.finetune_task import GPURequirement


# ============================================================
# 节点信息
# ============================================================
@dataclass
class GPUNode:
    """GPU 节点信息.

    Attributes:
        name: 节点名.
        gpuType: GPU 型号.
        totalGPUs: 节点 GPU 总数.
        freeGPUs: 当前空闲 GPU 数.
        memoryGBPerGPU: 每卡显存 GB.
        labels: 节点标签（用于亲和性调度）.
    """

    name: str
    gpuType: str
    totalGPUs: int
    freeGPUs: int
    memoryGBPerGPU: int = 0
    labels: dict[str, str] = field(default_factory=dict)

    def can_satisfy(self, req: GPURequirement) -> bool:
        """判断节点是否能满足 GPU 需求.

        多卡亲和性：要求单节点空闲 GPU 数 >= 需求卡数。
        """
        if self.freeGPUs < req.count:
            return False
        if req.type != "any" and self.gpuType != req.type:
            return False
        if req.memoryGB > 0 and self.memoryGBPerGPU < req.memoryGB:
            return False
        return True


# ============================================================
# 调度结果
# ============================================================
@dataclass
class ScheduleResult:
    """调度结果.

    Attributes:
        success: 是否调度成功.
        nodeName: 调度到的节点名（失败时为 None）.
        gpuIds: 分配的 GPU ID 列表.
        reason: 失败原因（成功时为空）.
    """

    success: bool
    nodeName: Optional[str] = None
    gpuIds: list[int] = field(default_factory=list)
    reason: str = ""


# ============================================================
# GPU 节点池调度器
# ============================================================
class JobScheduler:
    """GPU 节点池调度器.

    管理集群 GPU 节点池，根据任务 GPU 需求进行调度。
    支持 Volcano / YuniKorn 两种调度后端，Mock 模式下使用内置节点池。
    """

    def __init__(
        self,
        backend: str = "volcano",
        mockMode: bool = True,
        nodes: Optional[list[GPUNode]] = None,
    ):
        """初始化调度器.

        Args:
            backend: 调度后端，"volcano" / "yunikorn" / "mock".
            mockMode: 是否 Mock 模式（不实际调用 K8s）.
            nodes: 初始节点池（Mock 模式下使用）.
        """
        self.backend = backend
        self.mockMode = mockMode
        self.nodes: list[GPUNode] = nodes or self._default_mock_nodes()

    @staticmethod
    def _default_mock_nodes() -> list[GPUNode]:
        """默认 Mock 节点池（用于本地验证）.

        提供充足的 GPU 资源以支持集成测试批量提交任务。
        生产环境通过 K8s 动态发现节点池。
        """
        return [
            GPUNode(
                name="gpu-node-01",
                gpuType="A100-40G",
                totalGPUs=16,
                freeGPUs=16,
                memoryGBPerGPU=40,
                labels={"gpu-type": "a100", "pool": "training"},
            ),
            GPUNode(
                name="gpu-node-02",
                gpuType="A100-40G",
                totalGPUs=16,
                freeGPUs=16,
                memoryGBPerGPU=40,
                labels={"gpu-type": "a100", "pool": "training"},
            ),
            GPUNode(
                name="gpu-node-03",
                gpuType="V100-32G",
                totalGPUs=16,
                freeGPUs=16,
                memoryGBPerGPU=32,
                labels={"gpu-type": "v100", "pool": "training"},
            ),
        ]

    def schedule(self, req: GPURequirement) -> ScheduleResult:
        """为任务调度 GPU 资源.

        调度算法：worst-fit，选择能满足需求且空闲 GPU 数最多的节点，
        保持资源均衡，避免单节点填满导致多卡任务无法调度（减少碎片化）。

        Args:
            req: GPU 资源需求.

        Returns:
            调度结果.
        """
        # 筛选可用节点
        candidates = [n for n in self.nodes if n.can_satisfy(req)]
        if not candidates:
            return ScheduleResult(
                success=False,
                reason=(
                    f"无可用 GPU 节点满足需求: count={req.count}, "
                    f"type={req.type}, memory={req.memoryGB}GB"
                ),
            )

        # worst-fit：选择空闲 GPU 数最多的节点，保持资源均衡
        candidates.sort(key=lambda n: n.freeGPUs, reverse=True)
        chosen = candidates[0]

        # 分配 GPU ID（取前 req.count 个空闲卡）
        # 简化：假设 GPU ID 从 0 开始连续编号，已用 = total - free
        used_count = chosen.totalGPUs - chosen.freeGPUs
        gpu_ids = list(range(used_count, used_count + req.count))

        # 更新节点空闲数
        chosen.freeGPUs -= req.count

        return ScheduleResult(
            success=True,
            nodeName=chosen.name,
            gpuIds=gpu_ids,
        )

    def release(self, nodeName: str, gpuIds: list[int]) -> bool:
        """释放节点上已分配的 GPU.

        Args:
            nodeName: 节点名.
            gpuIds: 要释放的 GPU ID 列表.

        Returns:
            True 表示释放成功.
        """
        for node in self.nodes:
            if node.name == nodeName:
                node.freeGPUs = min(node.totalGPUs, node.freeGPUs + len(gpuIds))
                return True
        return False

    def list_nodes(self) -> list[dict]:
        """列出节点池状态（用于 /api/v1/finetune/nodes 端点）."""
        return [
            {
                "name": n.name,
                "gpuType": n.gpuType,
                "totalGPUs": n.totalGPUs,
                "freeGPUs": n.freeGPUs,
                "memoryGBPerGPU": n.memoryGBPerGPU,
                "labels": n.labels,
            }
            for n in self.nodes
        ]

    def describe(self) -> dict:
        """调度器描述信息."""
        return {
            "backend": self.backend,
            "mockMode": self.mockMode,
            "nodeCount": len(self.nodes),
            "totalGPUs": sum(n.totalGPUs for n in self.nodes),
            "freeGPUs": sum(n.freeGPUs for n in self.nodes),
        }