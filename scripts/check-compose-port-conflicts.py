#!/usr/bin/env python3
"""compose 宿主端口 vs Windows Hyper-V 保留段 静态比对（台账 #116 的 P3 收尾）。

背景：tests/integration/docker-compose.yml 的 Jaeger OTLP 宿主端口 14318 落在
Windows Hyper-V 动态端口保留段（netsh 实测 14219–14318）内 ⇒ Windows 本机
`docker compose up` 直接 bind 失败，而 CI Linux 无该保留段、从来不暴露
（#116）。本脚本把「撞了才知道」变成「提 PR 前就知道」。

用法：
    python scripts/check-compose-port-conflicts.py [compose 文件...]
    （不带参数默认 tests/integration/docker-compose.yml）

退出码：0=无冲突；1=发现冲突；2=用法/解析错误。
非 Windows 平台：打印提示并按 0 退出（保留段是 Windows 侧特性，
        CI  Linux 无此问题——这正是当年 #116 能潜伏的原因）。
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    print("ERROR: 需要 PyYAML", file=sys.stderr)
    sys.exit(2)


def windows_excluded_ranges() -> list[tuple[int, int]]:
    """netsh int ipv4 show excludedportrange protocol=tcp 解析保留段。"""
    out = subprocess.run(
        ["netsh", "int", "ipv4", "show", "excludedportrange", "protocol=tcp"],
        capture_output=True, text=True,
    )
    ranges: list[tuple[int, int]] = []
    for line in out.stdout.splitlines():
        m = re.match(r"^\s*(\d+)\s+(\d+)\s*$", line)
        if m:
            ranges.append((int(m.group(1)), int(m.group(2))))
    return ranges


def host_ports(compose: Path) -> list[tuple[str, int | None]]:
    doc = yaml.safe_load(compose.read_text(encoding="utf-8")) or {}
    found: list[tuple[str, int | None]] = []
    for name, svc in (doc.get("services") or {}).items():
        for p in svc.get("ports") or []:
            s = str(p)
            # 形态："H:C" / "H:C/proto" / "ip:H:C" / "C"（短语法=随机宿主端口，无冲突面）
            parts = s.split(":")
            host = parts[-2] if len(parts) >= 2 else None
            if host is None or not host.isdigit():
                found.append((name, None))
                continue
            found.append((name, int(host)))
    return found


def main() -> int:
    files = [Path(a) for a in sys.argv[1:]] or [Path("tests/integration/docker-compose.yml")]
    if sys.platform not in ("win32", "msys", "cygwin"):
        print("非 Windows 平台：Hyper-V 保留段是 Windows 侧特性，本机检查跳过（CI Linux 无此问题）")
        return 0

    ranges = windows_excluded_ranges()
    if not ranges:
        print("ERROR: netsh 未返回任何保留段（权限或平台异常）", file=sys.stderr)
        return 2
    print(f"Windows 保留段（netsh 实测）：{len(ranges)} 段")

    conflicts: list[tuple[str, int, int, int]] = []
    total = 0
    for f in files:
        if not f.is_file():
            print(f"ERROR: 文件不存在 {f}", file=sys.stderr)
            return 2
        for svc, port in host_ports(f):
            if port is None:
                continue
            total += 1
            for lo, hi in ranges:
                if lo <= port <= hi:
                    conflicts.append((svc, port, lo, hi))
                    break

    for svc, port, lo, hi in conflicts:
        print(
            f"CONFLICT: 服务 {svc} 的宿主端口 {port} 落在保留段 {lo}-{hi} 内 ⇒ "
            f"Windows 本机 docker compose up 会 bind 失败（CI 不暴露）"
        )
    print(f"固定宿主端口 {total} 个，冲突 {len(conflicts)} 个（台账 #116）")
    return 1 if conflicts else 0


if __name__ == "__main__":
    sys.exit(main())
