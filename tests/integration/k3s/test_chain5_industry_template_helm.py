"""链路5: 行业模板 helm 真部署 端到端集成测试.

测试行业模板 Helm Chart 在真实 K3s 集群上的端到端生命周期：
    helm upgrade --install -> ConfigMap 资产打包 -> 导入 Job -> helm list/status -> uninstall 卸载闭环

背景（P2 里程碑）：
- Sprint 4.2 修复了行业 chart 的 chart 映射（chartRef 三级回退）与 configmap-assets.tpl 的
  .Files 作用域 bug（range 内 .Files.Get -> $.Files.Get），9 个行业 chart 补齐了静态资产
  （ddl/dag/dashboards/iotdb/rbac 五类），现可在真实 K3s 上 helm install。

被测对象：
- platform/industry-templates/charts/*-template （9 个行业 chart）
- 真实 helm CLI（需安装；无 helm 时整链路 SKIPPED）

测试步骤：
1. 检查 helm CLI 可用性（无则跳过）
2. 对 energy-template 执行 helm upgrade --install 到 namespace=energy
3. 断言：release 存在（helm list）、ConfigMap {prefix}-assets 非空、导入 Job 创建
4. helm status 验证 release 状态
5. helm uninstall 卸载闭环：release 消失、ConfigMap 级联删除
6. 其余 8 个行业 chart 各做一次 helm template 渲染校验（ConfigMap 含非空资产、无模板错误），
   避免在每个 namespace 都创建真实资源，控制 CI 负载。真实 install 覆盖用 energy。
"""
from __future__ import annotations

import json
import shutil
import subprocess
import time
from pathlib import Path

import pytest

CHAIN_NAME = "链路5: 行业模板 helm 真部署"
CHART_BASE = "platform/industry-templates/charts"
ALL_INDUSTRIES = [
    "agriculture", "education", "energy", "finance", "government",
    "manufacturing", "medical", "retail", "transportation",
]


def _run(cmd, cwd=None, timeout=120):
    """运行子进程命令，返回 (returncode, stdout, stderr)."""
    r = subprocess.run(
        cmd, capture_output=True, text=True, timeout=timeout, cwd=cwd,
    )
    return r.returncode, r.stdout, r.stderr


def _helm_available() -> bool:
    """检查 helm CLI 是否可用."""
    return shutil.which("helm") is not None


def _kubectl_available() -> bool:
    """检查 kubectl CLI 是否可用."""
    return shutil.which("kubectl") is not None


def _chart_dir(industry: str) -> str:
    """行业模板 chart 目录（相对仓库根）."""
    return f"./{CHART_BASE}/{industry}-template"


def _chart_name(industry: str) -> str:
    """helm release/chart 名 = {industry}-template."""
    return f"{industry}-template"


def _helm_install(industry: str, namespace: str, repo_root: str) -> tuple:
    """执行 helm upgrade --install 并等待导入 Job 完成."""
    chart = _chart_dir(industry)
    release = _chart_name(industry)
    code, out, err = _run(
        ["helm", "upgrade", "--install", release, chart, "-n", namespace, "--wait"],
        cwd=repo_root, timeout=180,
    )
    return code, out, err


def _helm_list(remote: str, namespace: str) -> list:
    """列出命名空间下的 helm release."""
    code, out, err = _run(["helm", "list", "-n", namespace, "--output", "json"])
    if code != 0:
        return []
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return []


def _helm_status(release: str, namespace: str) -> str:
    """获取 release 的 status."""
    code, out, err = _run(["helm", "status", release, "-n", namespace])
    return out


def _helm_uninstall(release: str, namespace: str) -> tuple:
    """卸载 release（级联删除其创建的资源）. 返回 (code, out, err)."""
    return _run(["helm", "uninstall", release, "-n", namespace], timeout=120)


def _get_configmap_data_keys(name: str, namespace: str) -> list:
    """读取 ConfigMap 的 data 键列表（空或未找到返回空列表）."""
    code, out, err = _run(
        ["kubectl", "get", "cm", name, "-n", namespace, "-o", "json"]
    )
    if code != 0:
        return []
    try:
        cm = json.loads(out)
        return list((cm.get("data") or {}).keys())
    except json.JSONDecodeError:
        return []


def _configmap_name(release: str) -> str:
    """ConfigMap 命名：values.yaml 的 configMap.namePrefix（默认 release 名） + -assets."""
    return f"{release}-assets"


def _job_name(release: str) -> str:
    """导入 Job 命名：{namePrefix}-import."""
    return f"{release}-import"


def _kubectl_resource_exists(kind: str, name: str, namespace: str) -> bool:
    """检查 K8s 资源是否存在."""
    code, _, _ = _run(["kubectl", "get", kind, name, "-n", namespace])
    return code == 0


class TestChain5HelmCli:
    """链路5 环境检查：helm 与 kubectl 可用性."""

    def test_helm_cli_available(self):
        """helm CLI 可用（无 helm 则跳过整链路）."""
        if not _helm_available():
            pytest.skip("helm CLI 不可用，跳过链路5")
        assert _helm_available()

    def test_kubectl_available(self):
        """kubectl CLI 可用（无则跳过整链路）."""
        if not _kubectl_available():
            pytest.skip("kubectl CLI 不可用，跳过链路5")
        assert _kubectl_available()


class TestChain5HelmInstall:
    """链路5 核心：对 energy-template 真实 helm install + 验证."""

    REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent

    @pytest.fixture(scope="class")
    def deployed_ns(self):
        """前置：helm install energy-template -> 返回命名空间."""
        if not _helm_available():
            pytest.skip("helm CLI 不可用，跳过链路5")
        ns = "energy"
        code, out, err = _helm_install("energy", ns, self.REPO_ROOT)
        assert code == 0, f"helm install energy-template 失败:\n{out}\n{err}"
        return ns

    def test_release_listed(self, deployed_ns):
        """helm list 可见 energy-template release."""
        releases = _helm_list("", deployed_ns)
        assert any(r.get("name") == "energy-template" for r in releases), (
            f"energy-template 不在 helm list 中: {releases}"
        )

    def test_release_status_deployed(self, deployed_ns):
        """helm status 显示 deployed 状态."""
        status = _helm_status("energy-template", deployed_ns)
        assert "STATUS: deployed" in status, f"release 状态非 deployed:\n{status}"

    def test_configmap_assets_non_empty(self, deployed_ns):
        """ConfigMap 打包了非空模板资产（ddl/dag/dashboards 等 key 存在）."""
        cm_name = _configmap_name("energy-template")
        keys = _get_configmap_data_keys(cm_name, deployed_ns)
        assert keys, f"ConfigMap {cm_name} 的 data 为空或不存在"
        joined = "/".join(keys)
        assert any(k.startswith("ddl/") for k in keys), f"ConfigMap 缺少 ddl 资产: {joined}"

    def test_import_job_created(self, deployed_ns):
        """导入 Job 被创建（post-install hook）. Job 名 {namePrefix}-import."""
        job = _job_name("energy-template")
        assert _kubectl_resource_exists("job", job, deployed_ns), (
            f"导入 Job {job} 未创建（helm post-install hook 未执行）"
        )


class TestChain5HelmUninstall:
    """链路5 卸载闭环：uninstall 后 release 与级联资源消失."""

    REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent

    def test_helm_uninstall_removes_release(self):
        """helm uninstall energy-template -> release 消失."""
        if not _helm_available():
            pytest.skip("helm CLI 不可用，跳过链路5")
        ns = "energy"
        code, out, err = _helm_install("energy", ns, self.REPO_ROOT)
        assert code == 0, f"重新 install 失败:\n{err}"
        ucode, uout, uerr = _helm_uninstall("energy-template", ns)
        assert ucode == 0, f"uninstall 失败:\n{uout}\n{uerr}"
        releases = _helm_list("", ns)
        assert not any(r.get("name") == "energy-template" for r in releases), (
            f"uninstall 后 release 仍存在: {releases}"
        )

    def test_uninstall_cascades_resources(self):
        """卸载后 ConfigMap / Job 被级联删除."""
        if not _helm_available():
            pytest.skip("helm CLI 不可用，跳过链路5")
        ns = "energy"
        cm_name = _configmap_name("energy-template")
        job = _job_name("energy-template")
        assert not _kubectl_resource_exists("configmap", cm_name, ns), (
            f"卸载后 ConfigMap {cm_name} 仍存在"
        )
        assert not _kubectl_resource_exists("job", job, ns), (
            f"卸载后 Job {job} 仍存在"
        )


class TestChain5OtherChartsRender:
    """链路5 扩展：其余 8 个行业 chart 各做一次 helm template 渲染校验.

    P2 验收要求"9 个行业模板至少各 1 次 helm 调用成功"：energy 走真实 install，
    其余 8 个走 helm template 渲染（校验 ConfigMap 含非空资产、无模板错误），
    避免在每个 namespace 都创建真实资源，控制 CI 负载。真实 install 覆盖用 energy。
    """

    REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
    OTHER = [i for i in ALL_INDUSTRIES if i != "energy"]

    def test_other_charts_render_with_assets(self):
        """8 个非 energy 行业 chart 均能渲染出带非空资产的 ConfigMap."""
        if not _helm_available():
            pytest.skip("helm CLI 不可用，跳过链路5")
        for industry in self.OTHER:
            chart = _chart_dir(industry)
            code, out, err = _run(
                ["helm", "template", _chart_name(industry), chart, "-n", industry],
                cwd=self.REPO_ROOT, timeout=60,
            )
            assert code == 0, f"{industry}-template helm template 失败:\n{err}"
            assert "kind: ConfigMap" in out, f"{industry}-template 无 ConfigMap"
            assert any(k in out for k in ["ddl/", "dag/", "dashboards/", "rbac/"]), (
                f"{industry}-template ConfigMap 资产为空"
            )
            assert "kind: Job" in out, f"{industry}-template 无导入 Job"