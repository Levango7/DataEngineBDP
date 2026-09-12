"""微调框架适配器层.

统一封装三种微调框架：
- LLaMA-Factory：通过 subprocess 调用 CLI
- HuggingFace PEFT：直接 Python 集成
- DeepSpeed：多卡并行训练
"""
