#!/usr/bin/env python3
"""探测「无 Endpoints 的 Service」在当前 k3s 版本下的 ClusterIP 访问行为.

用途（台账 #35 续八遗留盲区）：
    台账写的是「v1.36.5+k3s1 下无端点 Service 走 kube-proxy REJECT 快速失败，
    v1.37.2+k3s1 下同现象变 10s 读超时挂起」——两者都是**推断**，从未在真集群
    同一时刻、同一 Service 上对照观测过。本脚本提供最小可复现的观测点。

行为约定：
    · 连接被拒（kube-proxy 装 REJECT 规则）→ outcome=refused，耗时通常 < 1s
    · SYN 被丢弃（无端点/黑洞）            → outcome=timeout，耗时 ≈ timeout 参数
    · 正常连上                              → outcome=connected

只用标准库（socket），不依赖 requests/urllib3 ⇒ 可以在任意容器内直接执行，
宿主机与 Pod 视角可用同一份脚本（Pod 视角经 kubectl exec -i 把脚本喂给 stdin）。
"""

import socket
import sys
import time


def probe(host: str, port: int, timeout: float) -> str:
    """对 host:port 做一次 TCP 连接探测，返回机器可读的结果行."""
    start = time.time()
    try:
        conn = socket.create_connection((host, port), timeout=timeout)
    except socket.timeout:
        # socket.timeout 是 TimeoutError 的别名（OSError 子类）⇒ 必须排在 OSError 之前
        elapsed = (time.time() - start) * 1000.0
        return (
            "PROBE_RESULT host=%s port=%d outcome=timeout elapsed_ms=%.1f timeout_s=%.1f"
            % (host, port, elapsed, timeout)
        )
    except ConnectionRefusedError:
        elapsed = (time.time() - start) * 1000.0
        return (
            "PROBE_RESULT host=%s port=%d outcome=refused elapsed_ms=%.1f timeout_s=%.1f"
            % (host, port, elapsed, timeout)
        )
    except OSError as exc:
        elapsed = (time.time() - start) * 1000.0
        return (
            "PROBE_RESULT host=%s port=%d outcome=oserror:%s elapsed_ms=%.1f timeout_s=%.1f"
            % (host, port, exc, elapsed, timeout)
        )
    elapsed = (time.time() - start) * 1000.0
    conn.close()
    return (
        "PROBE_RESULT host=%s port=%d outcome=connected elapsed_ms=%.1f timeout_s=%.1f"
        % (host, port, elapsed, timeout)
    )


def main() -> int:
    if len(sys.argv) < 3:
        print("用法: probe_endpointless_service.py <host> <port> [timeout_s]", file=sys.stderr)
        return 2
    host = sys.argv[1]
    try:
        port = int(sys.argv[2])
    except ValueError:
        print("端口必须是整数: %s" % sys.argv[2], file=sys.stderr)
        return 2
    timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 10.0
    print(probe(host, port, timeout))
    return 0


if __name__ == "__main__":
    sys.exit(main())
