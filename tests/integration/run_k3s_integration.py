"""K3s 端到端集成测试运行脚本.

功能：
    1. 检测运行环境（WSL/Windows），确保可访问 K3s 服务；
    2. 运行 4 条链路的 pytest 集成测试；
    3. 收集测试结果，生成 Markdown 报告 integration_test_report.md。

用法：
    # 在 WSL 内运行（推荐，可直接访问 ClusterIP）
    python run_k3s_integration.py

    # 在 Windows 运行（需通过 kubectl port-forward 或 K3S_SVC_* 环境变量）
    python run_k3s_integration.py

    # 指定只运行某条链路
    python run_k3s_integration.py --chain 1
    python run_k3s_integration.py --chain 1,3

输出：
    tests/integration/integration_test_report.md
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional


# 项目根目录
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]

# K3s 测试目录（子目录，避免与原有 conftest.py 冲突）
K3S_TEST_DIR = SCRIPT_DIR / "k3s"

# 4 条链路的测试文件
CHAIN_FILES = {
    1: "test_chain1_nl2sql_to_query.py",
    2: "test_chain2_orchestrator_agent.py",
    3: "test_chain3_query_rewrite_mv.py",
    4: "test_chain4_security_crypto.py",
}

CHAIN_NAMES = {
    1: "链路1: NL2SQL→SQL网关→查询",
    2: "链路2: 编排引擎→Agent→工具调用",
    3: "链路3: 查询改写→物化视图路由",
    4: "链路4: SecurityFacade→加解密",
}


def check_kubectl() -> bool:
    """检查 kubectl 是否可用."""
    try:
        result = subprocess.run(
            ["kubectl", "version", "--client"],
            capture_output=True, text=True, timeout=10,
        )
        return result.returncode == 0
    except (subprocess.SubprocessError, FileNotFoundError):
        return False


def get_k3s_pod_status() -> dict:
    """获取 K3s Pod 状态（用于报告环境信息）."""
    pods = {}
    try:
        result = subprocess.run(
            ["kubectl", "get", "pods", "-n", "shuqing", "-o", "json"],
            capture_output=True, text=True, timeout=15,
        )
        if result.returncode == 0:
            data = json.loads(result.stdout)
            for item in data.get("items", []):
                name = item["metadata"]["name"]
                phase = item["status"]["phase"]
                containers = item["status"].get("containerStatuses", [])
                ready = all(c.get("ready", False) for c in containers) if containers else False
                restart_count = sum(c.get("restartCount", 0) for c in containers)
                pods[name] = {
                    "phase": phase,
                    "ready": ready,
                    "restarts": restart_count,
                }
    except (subprocess.SubprocessError, json.JSONDecodeError, FileNotFoundError):
        pass
    return pods


def get_k3s_service_ips() -> dict:
    """获取 K3s Service ClusterIP 列表."""
    services = {}
    try:
        result = subprocess.run(
            ["kubectl", "get", "svc", "-n", "shuqing", "-o", "json"],
            capture_output=True, text=True, timeout=15,
        )
        if result.returncode == 0:
            data = json.loads(result.stdout)
            for item in data.get("items", []):
                name = item["metadata"]["name"]
                cluster_ip = item["spec"].get("clusterIP", "")
                ports = item["spec"].get("ports", [])
                port = ports[0]["port"] if ports else 0
                services[name] = {"clusterIP": cluster_ip, "port": port}
    except (subprocess.SubprocessError, json.JSONDecodeError, FileNotFoundError):
        pass
    return services


def run_pytest(chain_ids: list[int]) -> dict:
    """运行指定链路的 pytest 测试.

    Args:
        chain_ids: 链路 ID 列表.

    Returns:
        pytest 执行结果（returncode, stdout, stderr, duration）.
    """
    test_files = [CHAIN_FILES[i] for i in chain_ids if i in CHAIN_FILES]
    test_paths = [str(K3S_TEST_DIR / f) for f in test_files]

    cmd = [
        sys.executable, "-m", "pytest",
        *test_paths,
        "-v",
        "--tb=short",
        f"--rootdir={K3S_TEST_DIR}",
        f"-c={K3S_TEST_DIR / 'pytest.ini'}",
        "--no-header",
    ]

    print(f"\n{'='*60}")
    print(f"运行 K3s 集成测试: {', '.join(test_files)}")
    print(f"命令: {' '.join(cmd)}")
    print(f"{'='*60}\n")

    start = time.time()
    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=300,
        cwd=str(K3S_TEST_DIR),
    )
    duration = time.time() - start

    return {
        "returncode": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "duration": duration,
    }


def parse_pytest_output(stdout: str) -> list[dict]:
    """解析 pytest 输出，提取每个测试的通过/失败状态.

    Returns:
        测试结果列表: [{name, passed, skipped, detail}]
    """
    results = []
    for line in stdout.splitlines():
        line = line.strip()
        # pytest -v 输出格式: "test_file.py::TestClass::test_method PASSED"
        if "::" in line:
            parts = line.split()
            if len(parts) >= 2:
                test_name = parts[0]
                status = parts[1]
                results.append({
                    "name": test_name,
                    "passed": status == "PASSED",
                    "skipped": status == "SKIPPED",
                    "failed": status == "FAILED",
                    "status": status,
                })
    return results


def generate_report(
    chain_results: dict[int, dict],
    pod_status: dict,
    service_ips: dict,
    output_path: Path,
) -> None:
    """生成 Markdown 集成测试报告.

    Args:
        chain_results: 链路 ID → pytest 执行结果.
        pod_status: K3s Pod 状态.
        service_ips: K3s Service IP 列表.
        output_path: 报告输出路径.
    """
    lines = []
    lines.append("# K3s 端到端集成联调测试报告")
    lines.append("")
    lines.append(f"> 生成时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"> 测试环境: K3s (namespace=shuqing)")
    lines.append("")

    # 摘要
    lines.append("## 测试摘要")
    lines.append("")
    total_chains = len(chain_results)
    passed_chains = sum(
        1 for r in chain_results.values() if r["returncode"] == 0
    )
    lines.append(f"| 指标 | 值 |")
    lines.append(f"|------|-----|")
    lines.append(f"| 链路总数 | {total_chains} |")
    lines.append(f"| 通过链路 | {passed_chains} |")
    lines.append(f"| 失败链路 | {total_chains - passed_chains} |")
    lines.append(f"| 通过率 | {passed_chains}/{total_chains} ({passed_chains*100//total_chains if total_chains else 0}%) |")
    lines.append("")

    # K3s 环境信息
    lines.append("## K3s 集群环境")
    lines.append("")
    if pod_status:
        lines.append("### Pod 状态")
        lines.append("")
        lines.append("| Pod | Phase | Ready | Restarts |")
        lines.append("|-----|-------|-------|----------|")
        for name, info in sorted(pod_status.items()):
            ready_mark = "✅" if info["ready"] else "❌"
            lines.append(
                f"| {name} | {info['phase']} | {ready_mark} | {info['restarts']} |"
            )
        lines.append("")
    else:
        lines.append("> ⚠️ 无法获取 Pod 状态（kubectl 不可用或集群不可达）")
        lines.append("")

    if service_ips:
        lines.append("### Service ClusterIP")
        lines.append("")
        lines.append("| Service | ClusterIP | Port |")
        lines.append("|---------|-----------|------|")
        for name, info in sorted(service_ips.items()):
            lines.append(
                f"| {name} | {info['clusterIP']} | {info['port']} |"
            )
        lines.append("")

    # 各链路详细结果
    lines.append("## 链路测试详情")
    lines.append("")
    for chain_id, result in sorted(chain_results.items()):
        chain_name = CHAIN_NAMES[chain_id]
        passed = result["returncode"] == 0
        status_mark = "✅ 通过" if passed else "❌ 失败"
        duration = result["duration"]

        lines.append(f"### {chain_name} — {status_mark}")
        lines.append("")
        lines.append(f"- **耗时**: {duration:.2f}s")
        lines.append(f"- **退出码**: {result['returncode']}")
        lines.append("")

        # 解析测试结果
        test_results = parse_pytest_output(result["stdout"])
        if test_results:
            lines.append("| 测试 | 状态 |")
            lines.append("|------|------|")
            for t in test_results:
                # 只显示测试方法名
                short_name = t["name"].split("::")[-1] if "::" in t["name"] else t["name"]
                if t["passed"]:
                    mark = "✅ PASSED"
                elif t["skipped"]:
                    mark = "⏭️ SKIPPED"
                elif t["failed"]:
                    mark = "❌ FAILED"
                else:
                    mark = f"⚠️ {t['status']}"
                lines.append(f"| {short_name} | {mark} |")
            lines.append("")

        # 显示 stdout 末尾的摘要和错误信息
        stdout_lines = result["stdout"].strip().splitlines()
        # 找到 FAILED 行和摘要行
        relevant = [
            l for l in stdout_lines
            if "PASSED" in l or "FAILED" in l or "SKIPPED" in l
            or "error" in l.lower() or "passed" in l.lower() or "failed" in l.lower()
        ]
        if relevant:
            lines.append("<details>")
            lines.append(f"<summary>pytest 输出摘要</summary>")
            lines.append("")
            lines.append("```")
            for l in relevant[-30:]:
                lines.append(l)
            lines.append("```")
            lines.append("")
            lines.append("</details>")
            lines.append("")

        # 显示错误详情
        if not passed and result["stderr"]:
            lines.append("<details>")
            lines.append(f"<summary>stderr</summary>")
            lines.append("")
            lines.append("```")
            lines.append(result["stderr"][-2000:])
            lines.append("```")
            lines.append("")
            lines.append("</details>")
            lines.append("")

        lines.append("---")
        lines.append("")

    # 结论
    lines.append("## 结论")
    lines.append("")
    if passed_chains == total_chains:
        lines.append("✅ **所有链路测试通过**，K3s 集群端到端集成联调成功。")
    elif passed_chains > 0:
        lines.append(
            f"⚠️ **部分链路测试通过**（{passed_chains}/{total_chains}），"
            f"请检查失败链路的详细日志。"
        )
    else:
        lines.append(
            "❌ **所有链路测试失败**，请检查 K3s 集群状态和服务可用性。"
        )
    lines.append("")
    lines.append("### 失败原因排查建议")
    lines.append("")
    lines.append("1. **Pod 未就绪**: 检查 `kubectl get pods -n shuqing`，确认 READY=1/1")
    lines.append("2. **服务不可达**: 检查 Service ClusterIP 是否可访问（`curl http://<IP>:<port>/health`）")
    lines.append("3. **JWT 认证失败**: 确认 JWT_SECRET 与各组件配置一致")
    lines.append("4. **Pod 不断重启**: 检查 `kubectl logs <pod> -n shuqing` 查看启动错误")
    lines.append("5. **环境变量冲突**: 检查 K8s Service 环境变量是否与应用配置冲突")
    lines.append("")

    output_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"\n报告已生成: {output_path}")


def main() -> int:
    """主入口: 解析参数 → 运行测试 → 生成报告."""
    parser = argparse.ArgumentParser(
        description="K3s 端到端集成测试运行脚本"
    )
    parser.add_argument(
        "--chain", type=str, default="1,2,3,4",
        help="运行的链路 ID，逗号分隔（默认 1,2,3,4）",
    )
    parser.add_argument(
        "--output", type=str, default="integration_test_report.md",
        help="报告输出文件名（默认 integration_test_report.md）",
    )
    args = parser.parse_args()

    # 解析链路 ID
    try:
        chain_ids = [int(x.strip()) for x in args.chain.split(",")]
    except ValueError:
        print(f"错误: 无效的链路 ID: {args.chain}")
        return 1

    # 检查 kubectl
    if not check_kubectl():
        print("⚠️ kubectl 不可用，将尝试通过环境变量 K3S_SVC_* 访问服务")

    # 获取 K3s 环境信息
    print("正在收集 K3s 集群环境信息...")
    pod_status = get_k3s_pod_status()
    service_ips = get_k3s_service_ips()

    if pod_status:
        ready_count = sum(1 for p in pod_status.values() if p["ready"])
        print(f"  Pod 总数: {len(pod_status)}, 就绪: {ready_count}")
    if service_ips:
        print(f"  Service 数: {len(service_ips)}")

    # 运行各链路测试
    chain_results = {}
    for chain_id in chain_ids:
        if chain_id not in CHAIN_FILES:
            print(f"⚠️ 跳过未知链路 ID: {chain_id}")
            continue
        result = run_pytest([chain_id])
        chain_results[chain_id] = result
        status = "✅ 通过" if result["returncode"] == 0 else "❌ 失败"
        print(f"\n{CHAIN_NAMES[chain_id]}: {status} ({result['duration']:.2f}s)")

    # 生成报告
    output_path = SCRIPT_DIR / args.output
    generate_report(chain_results, pod_status, service_ips, output_path)

    # 返回退出码（所有链路通过返回 0）
    return 0 if all(r["returncode"] == 0 for r in chain_results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())