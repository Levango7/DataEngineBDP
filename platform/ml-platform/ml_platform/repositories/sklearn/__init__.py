"""Scikit-learn 仓储实现.

通过配置开关 ML_BACKEND_TYPE=sklearn 启用。
sklearn 未安装时，导入此模块不报错，实例化时才抛 BackendUnavailableError。
"""

from ml_platform.repositories.sklearn.backend import SklearnMLBackend

__all__ = ["SklearnMLBackend"]
