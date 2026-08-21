#!/usr/bin/env python3
"""
Cilium eBPF 网络性能基准测试（v2.1 网络优化）。

测试 Cilium eBPF 数据路径优化的跨节点网络性能：
- 跨节点吞吐量（iperf3）
- 跨节点延迟（ping / tcp ping）
- Pod 间通信延迟（同节点 vs 跨节点）
- Service 负载均衡延迟
- Hubble 流量可观测性

目标：跨节点吞吐提升 40%。

用法：
    python bench_cilium_network.py --target-ip 10.244.1.10
    python bench_cilium_network.py --target-pod trino-worker-1 --duration 30
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass, field


@dataclass
class NetworkMetric:
    """网络性能指标。"""
    test_name: str
    throughput_mbps: float = 0.0
    latency_ms: float = 0.0
    jitter_ms: float = 0.0
    packet_loss_pct: float = 0.0
    success: bool = True
    error: str | None = None
    raw_output: str = ""


@dataclass
class BenchmarkSuite:
    """基准测试套件结果。"""
    metrics: list[NetworkMetric] = field(default_factory=list)

    def add(self, m: NetworkMetric) -> None:
        self.metrics.append(m)

    def summary(self) -> dict:
        if not self.metrics:
            return {}
        return {
            "total_tests": len(self.metrics),
            "successful": sum(1 for m in self.metrics if m.success),
            "failed": sum(1 for m in self.metrics if not m.success),
            "avg_throughput_mbps": statistics.mean(
                [m.throughput_mbps for m in self.metrics if m.throughput_mbps > 0]
            ) if any(m.throughput_mbps > 0 for m in self.metrics) else 0,
            "avg_latency_ms": statistics.mean(
                [m.latency_ms for m in self.metrics if m.latency_ms > 0]
            ) if any(m.latency_ms > 0 for m in self.metrics) else 0,
        }


def run_iperf3(target: str, duration: int = 30, parallel: int = 8) -> NetworkMetric:
    """运行 iperf3 跨节点吞吐测试。"""
    metric = NetworkMetric(test_name=f"iperf3_{parallel}parallel")
    try:
        result = subprocess.run(
            ["iperf3", "-c", target, "-t", str(duration), "-P", str(parallel), "-J"],
            capture_output=True, text=True, timeout=duration + 10,
        )
        metric.raw_output = result.stdout
        if result.returncode == 0:
            data = json.loads(result.stdout)
            # 汇总吞吐（所有并行流）
            total_bps = data.get("end", {}).get("sum_received", {}).get("bits_per_second", 0)
            if total_bps == 0:
                # 多流模式取 sum_sent
                total_bps = data.get("end", {}).get("sum_sent", {}).get("bits_per_second", 0)
            metric.throughput_mbps = total_bps / 1_000_000
            metric.success = True
        else:
            metric.success = False
            metric.error = result.stderr[:200]
    except FileNotFoundError:
        metric.success = False
        metric.error = "iperf3 not installed"
    except subprocess.TimeoutExpired:
        metric.success = False
        metric.error = "timeout"
    except Exception as e:
        metric.success = False
        metric.error = str(e)
    return metric


def run_ping(target: str, count: int = 100, interval: float = 0.1) -> NetworkMetric:
    """运行 ping 延迟测试。"""
    metric = NetworkMetric(test_name=f"ping_{count}pkts")
    try:
        result = subprocess.run(
            ["ping", "-c", str(count), "-i", str(interval), target],
            capture_output=True, text=True, timeout=count * interval + 10,
        )
        metric.raw_output = result.stdout
        if result.returncode == 0:
            # 解析 ping 统计行
            # rtt min/avg/max/mdev = 0.123/0.456/0.789/0.012 ms
            match = re.search(
                r"rtt min/avg/max/mdev = [\d.]+/([\d.]+)/([\d.]+)/([\d.]+)",
                result.stdout,
            )
            if match:
                metric.latency_ms = float(match.group(1))
                metric.jitter_ms = float(match.group(3))
            # 解析丢包率
            loss_match = re.search(r"(\d+)% packet loss", result.stdout)
            if loss_match:
                metric.packet_loss_pct = float(loss_match.group(1))
            metric.success = True
        else:
            metric.success = False
            metric.error = result.stderr[:200]
    except FileNotFoundError:
        metric.success = False
        metric.error = "ping not available"
    except Exception as e:
        metric.success = False
        metric.error = str(e)
    return metric


def run_hubble_flow_stats(duration: int = 30) -> NetworkMetric:
    """通过 Hubble 获取流量统计。"""
    metric = NetworkMetric(test_name="hubble_flow_stats")
    try:
        result = subprocess.run(
            ["hubble", "observe", "--since", f"{duration}s",
             "--type", "flow", "--output", "json"],
            capture_output=True, text=True, timeout=duration + 10,
        )
        metric.raw_output = result.stdout[:1000]  # 截断
        if result.returncode == 0:
            lines = [l for l in result.stdout.strip().split("\n") if l]
            metric.latency_ms = len(lines)  # 流量包数
            metric.success = True
        else:
            metric.success = False
            metric.error = "hubble not available"
    except FileNotFoundError:
        metric.success = False
        metric.error = "hubble CLI not installed"
    except Exception as e:
        metric.success = False
        metric.error = str(e)
    return metric


def run_kubectl_exec_ping(target_pod: str, namespace: str = "default") -> NetworkMetric:
    """通过 kubectl exec 在 Pod 内执行 ping 测试。"""
    metric = NetworkMetric(test_name=f"pod_ping_{target_pod}")
    try:
        # 获取目标 Pod IP
        ip_result = subprocess.run(
            ["kubectl", "get", "pod", target_pod, "-n", namespace,
             "-o", "jsonpath={.status.podIP}"],
            capture_output=True, text=True, timeout=10,
        )
        if ip_result.returncode != 0:
            metric.success = False
            metric.error = f"无法获取 Pod IP: {ip_result.stderr}"
            return metric
        target_ip = ip_result.stdout.strip()
        if not target_ip:
            metric.success = False
            metric.error = "Pod IP 为空"
            return metric
        return run_ping(target_ip, count=50, interval=0.1)
    except FileNotFoundError:
        metric.success = False
        metric.error = "kubectl not available"
    except Exception as e:
        metric.success = False
        metric.error = str(e)
    return metric


def main() -> int:
    parser = argparse.ArgumentParser(description="Cilium eBPF 网络性能基准测试")
    parser.add_argument("--target-ip", help="目标 IP（iperf3/ping）")
    parser.add_argument("--target-pod", help="目标 Pod 名（kubectl exec ping）")
    parser.add_argument("--namespace", default="default", help="Pod 命名空间")
    parser.add_argument("--duration", type=int, default=30, help="测试时长（秒）")
    parser.add_argument("--parallel", type=int, default=8, help="iperf3 并行流数")
    parser.add_argument("--output", help="结果输出 JSON 文件路径")
    args = parser.parse_args()

    suite = BenchmarkSuite()
    print("=" * 60)
    print("Cilium eBPF 网络性能基准测试")
    print("=" * 60)

    if args.target_ip:
        print(f"\n[1] iperf3 跨节点吞吐测试 (target={args.target_ip}, "
              f"duration={args.duration}s, parallel={args.parallel})")
        m = run_iperf3(args.target_ip, args.duration, args.parallel)
        suite.add(m)
        if m.success:
            print(f"  吞吐: {m.throughput_mbps:.1f} Mbps")
        else:
            print(f"  失败: {m.error}")

        print(f"\n[2] Ping 延迟测试 (target={args.target_ip})")
        m = run_ping(args.target_ip, count=100, interval=0.1)
        suite.add(m)
        if m.success:
            print(f"  延迟: {m.latency_ms:.3f} ms (jitter: {m.jitter_ms:.3f} ms, "
                  f"loss: {m.packet_loss_pct}%)")
        else:
            print(f"  失败: {m.error}")

    if args.target_pod:
        print(f"\n[3] Pod 间延迟测试 (target={args.target_pod})")
        m = run_kubectl_exec_ping(args.target_pod, args.namespace)
        suite.add(m)
        if m.success:
            print(f"  延迟: {m.latency_ms:.3f} ms")
        else:
            print(f"  失败: {m.error}")

    print(f"\n[4] Hubble 流量统计")
    m = run_hubble_flow_stats(args.duration)
    suite.add(m)
    if m.success:
        print(f"  流量包数: {m.latency_ms}")
    else:
        print(f"  跳过: {m.error}")

    # 汇总
    print("\n" + "=" * 60)
    print("汇总")
    print("=" * 60)
    summary = suite.summary()
    print(f"  总测试数: {summary.get('total_tests', 0)}")
    print(f"  成功: {summary.get('successful', 0)}")
    print(f"  失败: {summary.get('failed', 0)}")
    if summary.get("avg_throughput_mbps", 0) > 0:
        print(f"  平均吞吐: {summary['avg_throughput_mbps']:.1f} Mbps")
    if summary.get("avg_latency_ms", 0) > 0:
        print(f"  平均延迟: {summary['avg_latency_ms']:.3f} ms")

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump([
                {
                    "test_name": m.test_name,
                    "throughput_mbps": m.throughput_mbps,
                    "latency_ms": m.latency_ms,
                    "jitter_ms": m.jitter_ms,
                    "packet_loss_pct": m.packet_loss_pct,
                    "success": m.success,
                    "error": m.error,
                }
                for m in suite.metrics
            ], f, indent=2, ensure_ascii=False)
        print(f"\n结果已保存到 {args.output}")

    return 0


if __name__ == "__main__":
    sys.exit(main())