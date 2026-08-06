"""Helm CLI 执行封装.

通过 subprocess 调用真实 helm CLI 完成 install / upgrade / uninstall / status / list。
仅在生产/集成环境（deployMode=helm）使用；开发与测试默认走 mock。

设计要点：
- 不依赖任何第三方 Python 库（YAML 序列化使用标准库的 json 兼容子集，
  或在缺 PyYAML 时回退为 JSON）
- 渲染后的 values 通过 --values - 从 stdin 传入，避免临时文件
- 命令失败抛 HelmCommandError，含 stdout/stderr/returncode 便于排查
- 提供 dry_run 选项用于在无集群时验证渲染
"""
from __future__ import annotations

import json
import os
import subprocess
from dataclasses import dataclass
from typing import Any, Optional

from industry_templates.services.exceptions import TemplateError


class HelmCommandError(TemplateError):
    """Helm 命令执行失败."""

    def __init__(
        self,
        message: str,
        cmd: list[str],
        returncode: int,
        stdout: str,
        stderr: str,
    ) -> None:
        super().__init__(message, code="HELM_COMMAND_ERROR")
        self.cmd = cmd
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


@dataclass
class HelmCommandResult:
    """Helm 命令执行结果."""

    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0


def _dump_values_yaml(values: dict[str, Any]) -> str:
    """把 values 字典序列化为 YAML.

    优先使用 PyYAML；若未安装则回退为 JSON（helm 同时支持 --values 的 YAML/JSON）。
    """
    try:
        import yaml  # type: ignore[import-not-found]

        return yaml.safe_dump(values, allow_unicode=True)
    except ImportError:
        return json.dumps(values, ensure_ascii=False, indent=2)


class HelmExecutor:
    """Helm CLI 执行封装.

    Attributes:
        helmBin:    helm 二进制路径（默认 helm，依赖 PATH）
        kubeconfig: 可选 KUBECONFIG 路径
        timeout:    单条命令超时秒数
    """

    def __init__(
        self,
        helmBin: str = "helm",
        kubeconfig: Optional[str] = None,
        timeout: int = 600,
    ) -> None:
        self.helmBin = helmBin
        self.kubeconfig = kubeconfig
        self.timeout = timeout

    # ---------- 底层执行 ----------

    def _env(self) -> dict[str, str]:
        env = dict(os.environ)
        if self.kubeconfig:
            env["KUBECONFIG"] = self.kubeconfig
        return env

    def _run(
        self,
        args: list[str],
        stdin: Optional[str] = None,
    ) -> HelmCommandResult:
        cmd = [self.helmBin, *args]
        try:
            proc = subprocess.run(
                cmd,
                input=stdin,
                capture_output=True,
                text=True,
                timeout=self.timeout,
                env=self._env(),
                check=False,
            )
        except FileNotFoundError as e:
            raise HelmCommandError(
                f"helm 二进制未找到: {self.helmBin}（请安装 Helm CLI 或调整 helmBin 配置）",
                cmd=cmd,
                returncode=-1,
                stdout="",
                stderr=str(e),
            ) from e
        except subprocess.TimeoutExpired as e:
            raise HelmCommandError(
                f"helm 命令超时（{self.timeout}s）: {' '.join(cmd)}",
                cmd=cmd,
                returncode=-1,
                stdout=e.stdout.decode() if e.stdout else "",
                stderr=e.stderr.decode() if e.stderr else "",
            ) from e
        return HelmCommandResult(
            returncode=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
        )

    @staticmethod
    def _check(result: HelmCommandResult, cmd: list[str]) -> None:
        if not result.ok:
            raise HelmCommandError(
                f"helm 命令失败（rc={result.returncode}）: {' '.join(cmd)}",
                cmd=cmd,
                returncode=result.returncode,
                stdout=result.stdout,
                stderr=result.stderr,
            )

    # ---------- install / upgrade ----------

    def install_or_upgrade(
        self,
        releaseName: str,
        chart: str,
        namespace: str,
        values: dict[str, Any],
        createNamespace: bool = True,
        dryRun: bool = False,
        wait: bool = True,
    ) -> HelmCommandResult:
        """安装或升级 Helm release.

        使用 `helm upgrade --install` 语义：不存在则安装，存在则升级。

        Args:
            releaseName:     Helm release 名称
            chart:           Chart 路径或引用（如 ./mychart 或 oci://.../mychart）
            namespace:       K8s namespace
            values:          渲染后的 values 字典（以 YAML/JSON 经 stdin 传入）
            createNamespace: namespace 不存在时自动创建
            dryRun:          仅渲染不真正安装（用于校验）
            wait:            等待资源就绪

        Returns:
            HelmCommandResult
        """
        args = [
            "upgrade",
            "--install",
            releaseName,
            chart,
            "--namespace",
            namespace,
            "--values",
            "-",
        ]
        if createNamespace:
            args.append("--create-namespace")
        if dryRun:
            args.append("--dry-run")
        if wait and not dryRun:
            args.append("--wait")
        values_str = _dump_values_yaml(values)
        cmd = [self.helmBin, *args]
        result = self._run(args, stdin=values_str)
        self._check(result, cmd)
        return result

    # ---------- uninstall ----------

    def uninstall(
        self,
        releaseName: str,
        namespace: str,
    ) -> HelmCommandResult:
        """卸载 Helm release.

        release 不存在时 helm uninstall 返回非 0，调用方需容忍。
        """
        args = ["uninstall", releaseName, "--namespace", namespace]
        return self._run(args)

    # ---------- status ----------

    def status(
        self,
        releaseName: str,
        namespace: str,
        outputJson: bool = True,
    ) -> dict[str, Any]:
        """查询 release 状态.

        Returns:
            helm status --output json 的解析结果；失败返回空 dict。
        """
        args = ["status", releaseName, "--namespace", namespace]
        if outputJson:
            args.extend(["--output", "json"])
        result = self._run(args)
        if not result.ok:
            return {}
        if outputJson:
            try:
                return json.loads(result.stdout)
            except json.JSONDecodeError:
                return {}
        return {"raw": result.stdout}

    # ---------- list ----------

    def list_releases(self, namespace: Optional[str] = None) -> list[dict[str, Any]]:
        """列出 release."""
        args = ["list", "--output", "json"]
        if namespace:
            args.extend(["--namespace", namespace])
        else:
            args.append("--all-namespaces")
        result = self._run(args)
        if not result.ok:
            return []
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError:
            return []


__all__ = [
    "HelmExecutor",
    "HelmCommandResult",
    "HelmCommandError",
]