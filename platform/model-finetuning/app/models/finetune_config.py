"""微调配置模型.

定义三种微调方式（LoRA / QLoRA / 全参）的配置参数，以及
三框架（LLaMA-Factory / PEFT / DeepSpeed）的统一配置入口。

设计要点：
    - LoRA：低秩矩阵分解，仅训练少量参数，支持 rank 8/16/32
    - QLoRA：量化 + LoRA，4bit/8bit 量化基座模型后再做 LoRA
    - 全参（Full）：全量参数微调，通常配合 DeepSpeed ZeRO-3 卸载优化器状态
"""
from __future__ import annotations

from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field, field_validator


# ============================================================
# 枚举：微调方式 / 框架 / 量化精度
# ============================================================
class FinetuneMethod(str, Enum):
    """微调方式枚举."""

    LORA = "lora"          # LoRA 低秩适配
    QLORA = "qlora"        # QLoRA 量化 + LoRA
    FULL = "full"          # 全参微调


class FinetuneFramework(str, Enum):
    """微调框架枚举."""

    LLAMA_FACTORY = "llama_factory"   # LLaMA-Factory CLI
    PEFT = "peft"                     # HuggingFace PEFT
    DEEPSPEED = "deepspeed"           # DeepSpeed 多卡并行


class QuantizationBits(str, Enum):
    """量化位宽枚举（仅 QLoRA 使用）."""

    BIT4 = "4bit"   # 4bit 量化（NF4）
    BIT8 = "8bit"   # 8bit 量化


class DeepSpeedStage(str, Enum):
    """DeepSpeed ZeRO 优化阶段."""

    ZERO_2 = "zero2"   # 切分优化器状态
    ZERO_3 = "zero3"   # 切分优化器状态 + 梯度 + 参数


# ============================================================
# LoRA 配置
# ============================================================
class LoRAConfig(BaseModel):
    """LoRA 低秩适配配置.

    Attributes:
        rank: 秩，常用 8/16/32；越大表达能力越强但显存占用越高。
        alpha: 缩放系数，通常设为 rank 的 2 倍。
        dropout: LoRA 层 dropout 概率。
        targetModules: 应用 LoRA 的模块名，如 ["q_proj","v_proj"]。
    """

    rank: int = Field(default=8, ge=1, le=128, description="LoRA 秩，常用 8/16/32")
    alpha: int = Field(default=16, ge=1, description="LoRA 缩放系数，通常为 rank 的 2 倍")
    dropout: float = Field(default=0.05, ge=0.0, lt=1.0, description="LoRA dropout")
    targetModules: list[str] = Field(
        default_factory=lambda: ["q_proj", "k_proj", "v_proj", "o_proj"],
        description="应用 LoRA 的模块名列表",
    )

    @field_validator("rank")
    @classmethod
    def validate_rank(cls, v: int) -> int:
        """校验 rank 取常用值 8/16/32（也允许其他合理值）."""
        if v not in (8, 16, 32, 64, 128):
            # 不强制报错，仅允许常用档位；其他值给出宽松范围即可
            if v < 1 or v > 128:
                raise ValueError(f"LoRA rank 必须在 1~128 之间，当前 {v}")
        return v


# ============================================================
# QLoRA 配置
# ============================================================
class QLoRAConfig(BaseModel):
    """QLoRA 量化 + LoRA 配置.

    先将基座模型量化为 4bit/8bit，再在其上做 LoRA 微调，
    大幅降低显存占用（7B 模型 4bit 单卡 ~6GB）。

    Attributes:
        quantization: 量化位宽，4bit 或 8bit。
        computeDtype: 计算精度，如 bfloat16 / float16。
        doubleQuantization: 是否启用双重量化。
        lora: LoRA 参数（复用 LoRAConfig）。
    """

    quantization: QuantizationBits = Field(
        default=QuantizationBits.BIT4, description="量化位宽"
    )
    computeDtype: str = Field(default="bfloat16", description="计算精度")
    doubleQuantization: bool = Field(default=True, description="是否双重量化")
    quantStorageDtype: str = Field(default="uint8", description="量化存储类型")
    lora: LoRAConfig = Field(default_factory=LoRAConfig, description="LoRA 参数")


# ============================================================
# 全参微调配置
# ============================================================
class FullFinetuneConfig(BaseModel):
    """全参微调配置.

    全量参数微调，显存占用最大，通常配合 DeepSpeed ZeRO-3 卸载。
    """

    gradientCheckpointing: bool = Field(default=True, description="梯度检查点，省显存")
    freezeEmbeddings: bool = Field(default=False, description="是否冻结 embedding")
    trainableEmbeddings: bool = Field(default=False, description="是否训练 embedding")


# ============================================================
# DeepSpeed 配置
# ============================================================
class DeepSpeedConfig(BaseModel):
    """DeepSpeed 并行配置.

    Attributes:
        stage: ZeRO 优化阶段，zero2 / zero3。
        tensorParallelSize: 张量并行度（TP），1 表示不启用 TP。
        dataParallelSize: 数据并行度（DP）。
        offloadOptimizer: 是否卸载优化器状态到 CPU。
        offloadParam: 是否卸载参数到 CPU（仅 zero3）。
    """

    stage: DeepSpeedStage = Field(default=DeepSpeedStage.ZERO_2, description="ZeRO 阶段")
    tensorParallelSize: int = Field(default=1, ge=1, le=8, description="张量并行度")
    dataParallelSize: int = Field(default=1, ge=1, description="数据并行度")
    offloadOptimizer: bool = Field(default=False, description="卸载优化器到 CPU")
    offloadParam: bool = Field(default=False, description="卸载参数到 CPU")
    configPath: Optional[str] = Field(
        default=None, description="DeepSpeed JSON 配置文件路径"
    )


# ============================================================
# 训练超参
# ============================================================
class TrainingHyperParams(BaseModel):
    """训练超参数.

    所有微调方式共用的训练超参。
    """

    epochs: int = Field(default=3, ge=1, le=100, description="训练轮数")
    batchSize: int = Field(default=8, ge=1, description="每卡 batch size")
    gradientAccumulationSteps: int = Field(default=2, ge=1, description="梯度累积步数")
    learningRate: float = Field(default=2e-4, gt=0, description="学习率")
    weightDecay: float = Field(default=0.01, ge=0, description="权重衰减")
    warmupSteps: int = Field(default=100, ge=0, description="warmup 步数")
    maxSeqLength: int = Field(default=2048, ge=64, description="最大序列长度")
    loggingSteps: int = Field(default=10, ge=1, description="日志打印间隔步数")
    saveSteps: int = Field(default=500, ge=1, description="checkpoint 保存间隔步数")
    evalSteps: Optional[int] = Field(default=None, description="评估间隔步数")
    seed: int = Field(default=42, description="随机种子")
    fp16: bool = Field(default=True, description="是否使用 fp16 混合精度")
    bf16: bool = Field(default=False, description="是否使用 bf16 混合精度")


# ============================================================
# 微调配置聚合
# ============================================================
class FinetuneConfig(BaseModel):
    """微调配置聚合模型.

    根据微调方式（method）选择对应的子配置，并指定使用哪个框架执行。

    Attributes:
        method: 微调方式（lora / qlora / full）。
        framework: 执行框架（llama_factory / peft / deepspeed）。
        lora: LoRA 配置（method=lora 时使用）。
        qlora: QLoRA 配置（method=qlora 时使用）。
        full: 全参配置（method=full 时使用）。
        deepspeed: DeepSpeed 并行配置（framework=deepspeed 时使用）。
        hyperparams: 训练超参。
    """

    method: FinetuneMethod = Field(description="微调方式")
    framework: FinetuneFramework = Field(
        default=FinetuneFramework.PEFT, description="执行框架"
    )
    lora: Optional[LoRAConfig] = Field(default=None, description="LoRA 配置")
    qlora: Optional[QLoRAConfig] = Field(default=None, description="QLoRA 配置")
    full: Optional[FullFinetuneConfig] = Field(default=None, description="全参配置")
    deepspeed: Optional[DeepSpeedConfig] = Field(
        default=None, description="DeepSpeed 并行配置"
    )
    hyperparams: TrainingHyperParams = Field(
        default_factory=TrainingHyperParams, description="训练超参"
    )

    def resolve_effective_config(self) -> dict:
        """根据 method 返回生效的子配置字典.

        用于适配器读取实际生效的参数，避免 None 判断散落各处。

        Returns:
            生效配置字典，键为 config 类型名，值为对应配置实例。
        """
        result: dict = {"method": self.method, "framework": self.framework}
        if self.method == FinetuneMethod.LORA:
            result["lora"] = self.lora or LoRAConfig()
        elif self.method == FinetuneMethod.QLORA:
            result["qlora"] = self.qlora or QLoRAConfig()
        elif self.method == FinetuneMethod.FULL:
            result["full"] = self.full or FullFinetuneConfig()
        if self.framework == FinetuneFramework.DEEPSPEED:
            result["deepspeed"] = self.deepspeed or DeepSpeedConfig()
        result["hyperparams"] = self.hyperparams
        return result