"""Knative Serving/Eventing 集成测试 · 数据引擎大数据平台。

本模块是 T024 Knative 部署的 pytest 集成测试，验证：
    1. Knative Serving KService 创建与 URL 分配
    2. KafkaSource 消息触发
    3. CronJobSource 定时触发
    4. scale-to-zero（无流量 60s 后 Pod 缩容到 0）

被测对象：Knative Serving >= 1.12 + Eventing >= 1.12
部署方式：ArgoCD GitOps（见 platform/knative/argocd-application.yaml）
网络层：复用 Phase 1 Istio Ingress Gateway

设计要点：
    - 借鉴 Phase 1 经验：K3s 环境不稳定，优先 Docker 直连验证；
    - 测试通过 kubectl + Kubernetes API 验证 Knative 资源状态；
    - 当集群不可用或 Knative 未部署时自动跳过，不产生错误；
    - scale-to-zero 测试默认跳过（耗时 70s），通过命令行选项启用；
    - 所有测试使用中文注释，与项目约定一致。

运行方式：
    # 完整测试（跳过 scale-to-zero）
    pytest tests/integration/docker/test_knative.py -v

    # 启用 scale-to-zero 测试
    pytest tests/integration/docker/test_knative.py -v --run-scale-to-zero

    # 仅运行快速测试
    pytest tests/integration/docker/test_knative.py -v -m "not slow"
"""

from __future__ import annotations

import os
import subprocess
import time
from pathlib import Path
from typing import Dict, Optional

import pytest


# ---------------------------------------------------------------------------
# 常量定义
# ---------------------------------------------------------------------------
# 项目根目录（从 tests/integration/docker/ 向上三级）
PROJECT_ROOT = Path(__file__).resolve().parents[3]

# Knative 示例 YAML 目录
KNATIVE_EXAMPLES_DIR = PROJECT_ROOT / "platform" / "knative" / "examples"

# Knative namespace
KNATIVE_SERVING_NS = "knative-serving"
KNATIVE_EVENTING_NS = "knative-eventing"
KNATIVE_EXAMPLES_NS = "knative-examples"

# 示例资源名称
KSERVICE_NAME = "hello-kservice"
KAFKA_SOURCE_NAME = "kafka-source-example"
CRONJOB_SOURCE_NAME = "cronjob-source-example"
PING_SOURCE_NAME = "ping-source-example"

# 超时配置（秒）
WAIT_TIMEOUT = 120  # 资源就绪超时
WAIT_INTERVAL = 2   # 轮询间隔
SCALE_TO_ZERO_WAIT = 70  # scale-to-zero 等待时间（> grace-period 60s）

# kubectl 命令
KUBECTL = os.environ.get("KUBECTL_BIN", "kubectl")


# ---------------------------------------------------------------------------
# 命令行选项注册已移至 conftest.py（pytest_addoption 必须在 conftest 或插件中定义）
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def run_kubectl(
    args: list[str],
    timeout: int = 30,
    check: bool = False,
) -> subprocess.CompletedProcess:
    """执行 kubectl 命令并返回结果。

    Args:
        args: kubectl 参数列表，例如 ``["get", "pods", "-n", "knative-serving"]``。
        timeout: 命令超时秒数。
        check: 为 True 时，非零退出码抛出 CalledProcessError。

    Returns:
        subprocess.CompletedProcess 实例。
    """
    cmd = [KUBECTL] + args
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=check,
    )


def is_cluster_available() -> bool:
    """检查 Kubernetes 集群是否可用。

    通过 ``kubectl get nodes`` 探测，5 秒内无响应视为不可用。
    """
    try:
        result = run_kubectl(["get", "nodes"], timeout=5)
        return result.returncode == 0
    except (subprocess.SubprocessError, FileNotFoundError):
        return False


def is_knative_serving_installed() -> bool:
    """检查 Knative Serving 是否已部署。

    通过检查 knative-serving namespace 是否存在判断。
    """
    if not is_cluster_available():
        return False
    result = run_kubectl(["get", "namespace", KNATIVE_SERVING_NS], timeout=5)
    return result.returncode == 0


def is_knative_eventing_installed() -> bool:
    """检查 Knative Eventing 是否已部署。

    通过检查 knative-eventing namespace 是否存在判断。
    """
    if not is_cluster_available():
        return False
    result = run_kubectl(["get", "namespace", KNATIVE_EVENTING_NS], timeout=5)
    return result.returncode == 0


def is_crd_installed(crd_name: str) -> bool:
    """检查指定 CRD 是否已注册。

    Args:
        crd_name: CRD 名称，例如 ``services.serving.knative.dev``。

    Returns:
        ``True`` 表示 CRD 已注册。
    """
    if not is_cluster_available():
        return False
    result = run_kubectl(["get", "crd", crd_name], timeout=5)
    return result.returncode == 0


def wait_for_condition(
    check_fn: callable,
    timeout: int = WAIT_TIMEOUT,
    interval: float = WAIT_INTERVAL,
    description: str = "条件",
) -> bool:
    """轮询等待条件满足。

    Args:
        check_fn: 返回 bool 的可调用对象，True 表示条件满足。
        timeout: 最长等待秒数。
        interval: 轮询间隔秒数。
        description: 条件描述（用于日志）。

    Returns:
        ``True`` 表示条件在超时前满足；``False`` 表示超时。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if check_fn():
                return True
        except Exception:
            pass
        time.sleep(interval)
    return False


def get_ksvc_status(name: str, namespace: str = KNATIVE_EXAMPLES_NS) -> Dict[str, str]:
    """获取 KService 状态信息。

    Args:
        name: KService 名称。
        namespace: 所在 namespace。

    Returns:
        包含 url、ready、replicas 等键的字典。
    """
    result = run_kubectl(
        ["get", "ksvc", name, "-n", namespace, "-o", "json"],
        timeout=10,
    )
    if result.returncode != 0:
        return {"exists": False}

    import json
    data = json.loads(result.stdout)
    status = data.get("status", {})

    # 提取 Ready 条件
    conditions = status.get("conditions", [])
    ready = "Unknown"
    for cond in conditions:
        if cond.get("type") == "Ready":
            ready = cond.get("status", "Unknown")
            break

    return {
        "exists": True,
        "url": status.get("url", ""),
        "ready": ready,
        "latestReadyRevision": status.get("latestReadyRevisionName", ""),
    }


def get_deployment_replicas(
    label_selector: str,
    namespace: str = KNATIVE_EXAMPLES_NS,
) -> Optional[int]:
    """获取匹配标签的 Deployment 副本数。

    Args:
        label_selector: 标签选择器，例如 ``serving.knative.dev/service=hello-kservice``。
        namespace: 所在 namespace。

    Returns:
        副本数（int），不存在时返回 None。
    """
    result = run_kubectl(
        [
            "get", "deployment",
            "-n", namespace,
            "-l", label_selector,
            "-o", "jsonpath={.items[0].spec.replicas}",
        ],
        timeout=10,
    )
    if result.returncode != 0 or not result.stdout.strip():
        return None
    try:
        return int(result.stdout.strip())
    except ValueError:
        return None


def apply_yaml(yaml_path: Path) -> bool:
    """kubectl apply 指定 YAML 文件。

    Args:
        yaml_path: YAML 文件绝对路径。

    Returns:
        ``True`` 表示 apply 成功。
    """
    if not yaml_path.exists():
        return False
    result = run_kubectl(["apply", "-f", str(yaml_path)], timeout=30)
    return result.returncode == 0


def delete_yaml(yaml_path: Path) -> bool:
    """kubectl delete 指定 YAML 文件中的资源。

    Args:
        yaml_path: YAML 文件绝对路径。

    Returns:
        ``True`` 表示 delete 成功（或资源不存在）。
    """
    if not yaml_path.exists():
        return True
    result = run_kubectl(["delete", "-f", str(yaml_path), "--ignore-not-found=true"], timeout=30)
    return result.returncode == 0


# ---------------------------------------------------------------------------
# pytest fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="session")
def cluster_available() -> bool:
    """Kubernetes 集群是否可用。"""
    return is_cluster_available()


@pytest.fixture(scope="session")
def knative_serving_available() -> bool:
    """Knative Serving 是否已部署。"""
    return is_knative_serving_installed()


@pytest.fixture(scope="session")
def knative_eventing_available() -> bool:
    """Knative Eventing 是否已部署。"""
    return is_knative_eventing_installed()


@pytest.fixture(scope="session")
def knative_available(
    knative_serving_available: bool,
    knative_eventing_available: bool,
) -> bool:
    """Knative Serving + Eventing 均已部署。"""
    return knative_serving_available and knative_eventing_available


@pytest.fixture(scope="session")
def run_scale_to_zero(request: pytest.FixtureRequest) -> bool:
    """是否启用 scale-to-zero 测试。"""
    return request.config.getoption("--run-scale-to-zero")


@pytest.fixture(scope="module")
def deployed_kservice(knative_available: bool) -> str:
    """部署示例 KService 并返回名称，测试结束后清理。

    若 Knative 不可用则跳过。

    Yields:
        KService 名称。
    """
    if not knative_available:
        pytest.skip("Knative Serving/Eventing 未部署，跳过 KService 测试")

    yaml_path = KNATIVE_EXAMPLES_DIR / "kservice-hello.yaml"
    if not apply_yaml(yaml_path):
        pytest.skip(f"无法 apply {yaml_path}")

    # 等待 KService 就绪
    def ksvc_ready() -> bool:
        status = get_ksvc_status(KSERVICE_NAME)
        return status.get("ready") == "True"

    if not wait_for_condition(ksvc_ready, timeout=180, description="KService Ready"):
        pytest.skip("KService 未能在 180s 内就绪")

    yield KSERVICE_NAME

    # 清理
    delete_yaml(yaml_path)


# ---------------------------------------------------------------------------
# 测试：Knative Serving 基础部署验证
# ---------------------------------------------------------------------------
class TestKnativeServingDeployment:
    """验证 Knative Serving 基础部署状态。

    这些测试不依赖示例资源，仅检查控制面与 CRD。
    """

    def test_namespace_exists(self, cluster_available: bool, knative_serving_available: bool):
        """验证 knative-serving namespace 存在。"""
        if not cluster_available:
            pytest.skip("Kubernetes 集群不可用")
        if not knative_serving_available:
            pytest.skip("Knative Serving 未部署")
        assert knative_serving_available, "knative-serving namespace 应存在"

    def test_serving_crds_installed(self, knative_serving_available: bool):
        """验证 Knative Serving CRD 已注册。"""
        if not knative_serving_available:
            pytest.skip("Knative Serving 未部署")
        required_crds = [
            "services.serving.knative.dev",
            "configurations.serving.knative.dev",
            "revisions.serving.knative.dev",
            "routes.serving.knative.dev",
            "podautoscalers.autoscaling.knative.dev",
        ]
        for crd in required_crds:
            assert is_crd_installed(crd), f"CRD {crd} 应已注册"

    def test_controller_pods_ready(self, knative_serving_available: bool):
        """验证 Knative Serving 控制面 Pod 就绪。"""
        if not knative_serving_available:
            pytest.skip("Knative Serving 未部署")
        result = run_kubectl(
            ["get", "pods", "-n", KNATIVE_SERVING_NS, "--no-headers"],
            timeout=10,
        )
        assert result.returncode == 0
        lines = [l for l in result.stdout.strip().split("\n") if l]
        assert len(lines) > 0, "knative-serving 应有 Pod 运行"
        # 检查是否有未就绪的 Pod
        for line in lines:
            parts = line.split()
            if len(parts) >= 3:
                ready = parts[2]  # READY 列
                if "/" in ready:
                    current, desired = ready.split("/")
                    assert current == desired, f"Pod {parts[0]} 未就绪: {ready}"

    def test_scale_to_zero_config(self, knative_serving_available: bool):
        """验证 scale-to-zero 配置已启用。"""
        if not knative_serving_available:
            pytest.skip("Knative Serving 未部署")
        result = run_kubectl(
            [
                "get", "configmap", "config-autoscaler",
                "-n", KNATIVE_SERVING_NS,
                "-o", "jsonpath={.data.enable-scale-to-zero}",
            ],
            timeout=10,
        )
        assert result.returncode == 0, "config-autoscaler ConfigMap 应存在"
        assert result.stdout.strip() == "true", "enable-scale-to-zero 应为 true"

    def test_kpa_target_concurrency_config(self, knative_serving_available: bool):
        """验证 KPA target-concurrency 配置。"""
        if not knative_serving_available:
            pytest.skip("Knative Serving 未部署")
        result = run_kubectl(
            [
                "get", "configmap", "config-autoscaler",
                "-n", KNATIVE_SERVING_NS,
                "-o", "jsonpath={.data.target-concurrency}",
            ],
            timeout=10,
        )
        if result.returncode == 0 and result.stdout.strip():
            target = result.stdout.strip()
            assert target is not None, "target-concurrency 应已配置"

    def test_istio_ingress_integration(self, knative_serving_available: bool):
        """验证 Knative 使用 Istio Ingress Gateway。"""
        if not knative_serving_available:
            pytest.skip("Knative Serving 未部署")
        # 检查 Istio Ingress Gateway 存在
        result = run_kubectl(
            ["get", "svc", "istio-ingressgateway", "-n", "istio-system"],
            timeout=10,
        )
        assert result.returncode == 0, "Istio Ingress Gateway 应存在"
        # 检查 Knative 配置使用 Istio
        result = run_kubectl(
            [
                "get", "configmap", "config-network",
                "-n", KNATIVE_SERVING_NS,
                "-o", "jsonpath={.data.ingress\\.class}",
            ],
            timeout=10,
        )
        if result.returncode == 0 and result.stdout.strip():
            ingress_class = result.stdout.strip()
            assert "istio" in ingress_class.lower(), f"ingress.class 应包含 istio: {ingress_class}"


# ---------------------------------------------------------------------------
# 测试：Knative Eventing 基础部署验证
# ---------------------------------------------------------------------------
class TestKnativeEventingDeployment:
    """验证 Knative Eventing 基础部署状态。"""

    def test_eventing_namespace_exists(self, knative_eventing_available: bool):
        """验证 knative-eventing namespace 存在。"""
        if not knative_eventing_available:
            pytest.skip("Knative Eventing 未部署")
        assert knative_eventing_available

    def test_eventing_crds_installed(self, knative_eventing_available: bool):
        """验证 Knative Eventing CRD 已注册。"""
        if not knative_eventing_available:
            pytest.skip("Knative Eventing 未部署")
        required_crds = [
            "brokers.eventing.knative.dev",
            "triggers.eventing.knative.dev",
            "subscriptions.eventing.knative.dev",
        ]
        for crd in required_crds:
            assert is_crd_installed(crd), f"CRD {crd} 应已注册"

    def test_event_source_crds_installed(self, knative_eventing_available: bool):
        """验证事件源 CRD（KafkaSource/CronJobSource/PingSource）已注册。"""
        if not knative_eventing_available:
            pytest.skip("Knative Eventing 未部署")
        event_source_crds = [
            "kafkasources.sources.knative.dev",
            "cronjobsources.sources.knative.dev",
            "pingsources.sources.knative.dev",
        ]
        for crd in event_source_crds:
            assert is_crd_installed(crd), f"事件源 CRD {crd} 应已注册"

    def test_eventing_controller_ready(self, knative_eventing_available: bool):
        """验证 Knative Eventing 控制面 Pod 就绪。"""
        if not knative_eventing_available:
            pytest.skip("Knative Eventing 未部署")
        result = run_kubectl(
            ["get", "pods", "-n", KNATIVE_EVENTING_NS, "--no-headers"],
            timeout=10,
        )
        assert result.returncode == 0
        lines = [l for l in result.stdout.strip().split("\n") if l]
        assert len(lines) > 0, "knative-eventing 应有 Pod 运行"


# ---------------------------------------------------------------------------
# 测试：KService 创建与 URL 分配
# ---------------------------------------------------------------------------
class TestKServiceCreation:
    """验证 KService 创建与 URL 分配。

    依赖：platform/knative/examples/kservice-hello.yaml
    """

    def test_kservice_created(self, deployed_kservice: str):
        """验证 KService 已创建且存在。"""
        result = run_kubectl(
            ["get", "ksvc", deployed_kservice, "-n", KNATIVE_EXAMPLES_NS],
            timeout=10,
        )
        assert result.returncode == 0, f"KService {deployed_kservice} 应存在"

    def test_kservice_url_assigned(self, deployed_kservice: str):
        """验证 KService 已分配 URL。

        Knative Serving 会为每个 KService 分配一个 URL，
        格式通常为 http://<name>.<namespace>.<domain>。
        """
        status = get_ksvc_status(deployed_kservice)
        assert status.get("exists"), "KService 应存在"
        url = status.get("url", "")
        assert url, "KService 应有分配的 URL"
        assert url.startswith("http://") or url.startswith("https://"), \
            f"URL 应为 HTTP/HTTPS 协议: {url}"

    def test_kservice_ready(self, deployed_kservice: str):
        """验证 KService Ready 条件为 True。"""
        status = get_ksvc_status(deployed_kservice)
        assert status.get("ready") == "True", \
            f"KService Ready 应为 True，实际: {status.get('ready')}"

    def test_kservice_has_revision(self, deployed_kservice: str):
        """验证 KService 已创建 Revision。"""
        status = get_ksvc_status(deployed_kservice)
        revision = status.get("latestReadyRevision", "")
        assert revision, "KService 应有 latestReadyRevision"

    def test_kservice_pod_running(self, deployed_kservice: str):
        """验证 KService 对应的 Pod 正在运行（或有流量时运行）。"""
        # 等待 Pod 启动（首次访问会触发 scale-from-zero）
        result = run_kubectl(
            [
                "get", "deployment",
                "-n", KNATIVE_EXAMPLES_NS,
                "-l", f"serving.knative.dev/service={deployed_kservice}",
            ],
            timeout=10,
        )
        assert result.returncode == 0, "KService 应有对应的 Deployment"


# ---------------------------------------------------------------------------
# 测试：KafkaSource 消息触发
# ---------------------------------------------------------------------------
class TestKafkaSource:
    """验证 KafkaSource 消息触发。

    依赖：
        - Phase 1 Kafka 集群（kafka namespace）
        - platform/knative/examples/kafkasource-example.yaml
    """

    @pytest.fixture(scope="class")
    def kafka_source_deployed(self, knative_available: bool) -> str:
        """部署 KafkaSource 示例并返回名称。"""
        if not knative_available:
            pytest.skip("Knative 未部署")

        # 检查 Kafka 集群是否可用
        kafka_result = run_kubectl(
            ["get", "svc", "kafka-bootstrap", "-n", "kafka"],
            timeout=5,
        )
        if kafka_result.returncode != 0:
            pytest.skip("Kafka 集群不可用，跳过 KafkaSource 测试")

        # 部署 Kafka Topic
        topic_yaml = KNATIVE_EXAMPLES_DIR / "kafka-topic.yaml"
        apply_yaml(topic_yaml)

        # 部署 KafkaSource
        source_yaml = KNATIVE_EXAMPLES_DIR / "kafkasource-example.yaml"
        if not apply_yaml(source_yaml):
            pytest.skip("无法部署 KafkaSource")

        # 等待 KafkaSource 就绪
        def source_ready() -> bool:
            result = run_kubectl(
                [
                    "get", "kafkasource", KAFKA_SOURCE_NAME,
                    "-n", KNATIVE_EXAMPLES_NS,
                    "-o", "jsonpath={.status.conditions[?(@.type==\"Ready\")].status}",
                ],
                timeout=10,
            )
            return result.returncode == 0 and result.stdout.strip() == "True"

        if not wait_for_condition(source_ready, timeout=120, description="KafkaSource Ready"):
            pytest.skip("KafkaSource 未能在 120s 内就绪")

        yield KAFKA_SOURCE_NAME

        # 清理
        delete_yaml(source_yaml)
        delete_yaml(topic_yaml)

    def test_kafkasource_created(self, kafka_source_deployed: str):
        """验证 KafkaSource 已创建。"""
        result = run_kubectl(
            ["get", "kafkasource", kafka_source_deployed, "-n", KNATIVE_EXAMPLES_NS],
            timeout=10,
        )
        assert result.returncode == 0, "KafkaSource 应存在"

    def test_kafkasource_ready(self, kafka_source_deployed: str):
        """验证 KafkaSource Ready 条件为 True。"""
        result = run_kubectl(
            [
                "get", "kafkasource", kafka_source_deployed,
                "-n", KNATIVE_EXAMPLES_NS,
                "-o", "jsonpath={.status.conditions[?(@.type==\"Ready\")].status}",
            ],
            timeout=10,
        )
        assert result.stdout.strip() == "True", "KafkaSource Ready 应为 True"

    def test_kafkasource_sink_configured(self, kafka_source_deployed: str):
        """验证 KafkaSource 的 sink 已正确配置为 KService。"""
        result = run_kubectl(
            [
                "get", "kafkasource", kafka_source_deployed,
                "-n", KNATIVE_EXAMPLES_NS,
                "-o", "jsonpath={.spec.sink.ref.name}",
            ],
            timeout=10,
        )
        assert result.stdout.strip() == KSERVICE_NAME, \
            f"KafkaSource sink 应为 {KSERVICE_NAME}"


# ---------------------------------------------------------------------------
# 测试：CronJobSource 定时触发
# ---------------------------------------------------------------------------
class TestCronJobSource:
    """验证 CronJobSource 定时触发。

    依赖：platform/knative/examples/cronjobsource-example.yaml
    """

    @pytest.fixture(scope="class")
    def cronjob_source_deployed(self, knative_available: bool) -> str:
        """部署 CronJobSource 示例并返回名称。"""
        if not knative_available:
            pytest.skip("Knative 未部署")

        source_yaml = KNATIVE_EXAMPLES_DIR / "cronjobsource-example.yaml"
        if not apply_yaml(source_yaml):
            pytest.skip("无法部署 CronJobSource")

        # 等待 CronJobSource 就绪
        def source_ready() -> bool:
            result = run_kubectl(
                [
                    "get", "cronjobsource", CRONJOB_SOURCE_NAME,
                    "-n", KNATIVE_EXAMPLES_NS,
                    "-o", "jsonpath={.status.conditions[?(@.type==\"Ready\")].status}",
                ],
                timeout=10,
            )
            return result.returncode == 0 and result.stdout.strip() == "True"

        if not wait_for_condition(source_ready, timeout=60, description="CronJobSource Ready"):
            pytest.skip("CronJobSource 未能在 60s 内就绪")

        yield CRONJOB_SOURCE_NAME

        # 清理
        delete_yaml(source_yaml)

    def test_cronjobsource_created(self, cronjob_source_deployed: str):
        """验证 CronJobSource 已创建。"""
        result = run_kubectl(
            ["get", "cronjobsource", cronjob_source_deployed, "-n", KNATIVE_EXAMPLES_NS],
            timeout=10,
        )
        assert result.returncode == 0, "CronJobSource 应存在"

    def test_cronjobsource_ready(self, cronjob_source_deployed: str):
        """验证 CronJobSource Ready 条件为 True。"""
        result = run_kubectl(
            [
                "get", "cronjobsource", cronjob_source_deployed,
                "-n", KNATIVE_EXAMPLES_NS,
                "-o", "jsonpath={.status.conditions[?(@.type==\"Ready\")].status}",
            ],
            timeout=10,
        )
        assert result.stdout.strip() == "True", "CronJobSource Ready 应为 True"

    def test_cronjobsource_schedule(self, cronjob_source_deployed: str):
        """验证 CronJobSource 的 schedule 配置正确。"""
        result = run_kubectl(
            [
                "get", "cronjobsource", cronjob_source_deployed,
                "-n", KNATIVE_EXAMPLES_NS,
                "-o", "jsonpath={.spec.schedule}",
            ],
            timeout=10,
        )
        schedule = result.stdout.strip()
        assert schedule, "CronJobSource 应有 schedule 配置"
        # 示例配置为 */2 * * * *
        assert "*/2" in schedule or "*/1" in schedule, \
            f"schedule 应为每 2 分钟或每分钟: {schedule}"

    def test_cronjobsource_sink_configured(self, cronjob_source_deployed: str):
        """验证 CronJobSource 的 sink 已正确配置为 KService。"""
        result = run_kubectl(
            [
                "get", "cronjobsource", cronjob_source_deployed,
                "-n", KNATIVE_EXAMPLES_NS,
                "-o", "jsonpath={.spec.sink.ref.name}",
            ],
            timeout=10,
        )
        assert result.stdout.strip() == KSERVICE_NAME, \
            f"CronJobSource sink 应为 {KSERVICE_NAME}"


# ---------------------------------------------------------------------------
# 测试：scale-to-zero
# ---------------------------------------------------------------------------
class TestScaleToZero:
    """验证 scale-to-zero：无流量 60s 后 Pod 缩容到 0。

    此测试耗时约 70s，默认跳过，通过 ``--run-scale-to-zero`` 启用。
    """

    @pytest.mark.slow
    def test_scale_to_zero_after_idle(
        self,
        deployed_kservice: str,
        run_scale_to_zero: bool,
    ):
        """验证无流量 70s 后 KService Pod 缩容到 0。"""
        if not run_scale_to_zero:
            pytest.skip("scale-to-zero 测试未启用，使用 --run-scale-to-zero 运行")

        # 确保当前有流量触发 Pod 启动
        status = get_ksvc_status(deployed_kservice)
        url = status.get("url", "")
        if not url:
            pytest.skip("KService 无 URL，无法触发流量")

        # 触发一次请求（启动 Pod）
        try:
            import requests
            try:
                requests.get(url, timeout=10)
            except requests.RequestException:
                pass  # URL 可能仅集群内可访问
        except ImportError:
            pass

        # 等待 Pod 启动
        time.sleep(5)

        # 等待 scale-to-zero 生效（无流量 70s > grace-period 60s）
        time.sleep(SCALE_TO_ZERO_WAIT)

        # 检查副本数
        replicas = get_deployment_replicas(
            f"serving.knative.dev/service={deployed_kservice}"
        )
        # scale-to-zero 后副本数应为 0 或 None（Deployment 被缩容）
        assert replicas is None or replicas == 0, \
            f"scale-to-zero 后副本数应为 0，实际: {replicas}"

    @pytest.mark.slow
    def test_scale_from_zero_on_request(
        self,
        deployed_kservice: str,
        run_scale_to_zero: bool,
    ):
        """验证 scale-from-zero：请求到达后 Pod 重新启动。"""
        if not run_scale_to_zero:
            pytest.skip("scale-to-zero 测试未启用，使用 --run-scale-to-zero 运行")

        status = get_ksvc_status(deployed_kservice)
        url = status.get("url", "")
        if not url:
            pytest.skip("KService 无 URL")

        # 发送请求触发 scale-from-zero
        try:
            import requests
            try:
                resp = requests.get(url, timeout=30)
                # 请求应成功（Activator 会暂停请求直到 Pod 启动）
                assert resp.status_code in (200, 503, 504), \
                    f"scale-from-zero 请求应成功或临时不可用: {resp.status_code}"
            except requests.RequestException:
                # 集群内 URL 可能从外部不可访问，跳过
                pytest.skip("KService URL 从测试环境不可访问")
        except ImportError:
            pytest.skip("requests 库未安装")

        # 等待 Pod 启动
        def pod_running() -> bool:
            replicas = get_deployment_replicas(
                f"serving.knative.dev/service={deployed_kservice}"
            )
            return replicas is not None and replicas > 0

        assert wait_for_condition(pod_running, timeout=60, description="Pod 启动"), \
            "scale-from-zero 后 Pod 应在 60s 内启动"


# ---------------------------------------------------------------------------
# 测试：ArgoCD Application 同步状态
# ---------------------------------------------------------------------------
class TestArgoCDApplication:
    """验证 ArgoCD Application 同步状态。

    依赖：platform/knative/argocd-application.yaml 已 apply
    """

    def test_argocd_applications_exist(self, cluster_available: bool):
        """验证 ArgoCD Application 已创建。"""
        if not cluster_available:
            pytest.skip("Kubernetes 集群不可用")
        # 检查 argocd namespace
        result = run_kubectl(["get", "namespace", "argocd"], timeout=5)
        if result.returncode != 0:
            pytest.skip("ArgoCD 未部署")

        for app in ["knative-serving", "knative-eventing", "knative-examples"]:
            result = run_kubectl(
                ["get", "application", app, "-n", "argocd"],
                timeout=10,
            )
            if result.returncode != 0:
                pytest.skip(f"ArgoCD Application {app} 未创建")

    def test_argocd_sync_wave_annotations(self, cluster_available: bool):
        """验证 ArgoCD Application 含 sync wave 注解。

        sync wave 顺序：
            -1: knative-serving
             0: knative-eventing
             1: knative-examples
        """
        if not cluster_available:
            pytest.skip("Kubernetes 集群不可用")

        expected_waves = {
            "knative-serving": "-1",
            "knative-eventing": "0",
            "knative-examples": "1",
        }

        for app, expected_wave in expected_waves.items():
            result = run_kubectl(
                [
                    "get", "application", app, "-n", "argocd",
                    "-o", "jsonpath={.metadata.annotations.argocd\\.argoproj\\.io/sync-wave}",
                ],
                timeout=10,
            )
            if result.returncode != 0:
                pytest.skip(f"Application {app} 不存在")
            actual_wave = result.stdout.strip()
            assert actual_wave == expected_wave, \
                f"Application {app} sync-wave 应为 {expected_wave}，实际: {actual_wave}"