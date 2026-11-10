#!/usr/bin/env python3
"""巡检项探测（D15 运维年费 SOP §二 的可执行形态）。

背景：D15 SOP 的巡检项全部取自 platform/observability/metric-contract.yaml 的
available 集（pending 集结构性不查：缺采集器/缺发射方，查了恒为空或报错，
属"已知静默零值"面）。本脚本对 available 集逐条向 Prometheus 发即时查询，
把"指标在不在"从肉眼读 targets 变成可重复执行的探测。

用法：
    python scripts/patrol-readings.py --prom-url http://localhost:9090
    python scripts/patrol-readings.py --prom-url http://localhost:9090 --timeout 10
    python scripts/patrol-readings.py --self-test

退出码：0=available 集全部有数据；1=存在无数据/查询失败项；
      2=用法或契约文件错误；3=全部查询均连接失败（疑似离线/URL 错）。

凭据：需要鉴权时经环境变量 PROM_TOKEN 注入（Bearer），不落仓库、不打印。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = REPO_ROOT / "platform" / "observability" / "metric-contract.yaml"

STATUS_LABEL = {"present": "有数据", "absent": "无数据", "error": "查询失败", "connfail": "连接失败"}


def load_contract(path: str):
    try:
        import yaml
    except ImportError:  # pragma: no cover - 环境缺 PyYAML 时给出明确提示
        print("ERROR: 需要 PyYAML（pip install pyyaml）", file=sys.stderr)
        raise SystemExit(2)
    p = Path(path)
    if not p.is_file():
        print(f"ERROR: 契约文件不存在：{p}", file=sys.stderr)
        raise SystemExit(2)
    data = yaml.safe_load(p.read_text(encoding="utf-8"))
    available = data.get("available") or []
    pending = data.get("pending") or []
    for group, label in ((available, "available"), (pending, "pending")):
        if not isinstance(group, list):
            print(f"ERROR: 契约 {label} 段不是列表：{p}", file=sys.stderr)
            raise SystemExit(2)
    return available, pending


def probe(base_url: str, metric: str, timeout: float, token: str | None):
    """对单指标发即时查询，返回 (状态, 细节)。"""
    url = f"{base_url.rstrip('/')}/api/v1/query?query={quote(metric)}"
    req = Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urlopen(req, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except HTTPError as e:
        return "error", f"HTTP {e.code}"
    except URLError as e:
        return "connfail", str(e.reason)
    except TimeoutError:
        return "connfail", "超时"
    except ValueError as e:  # 含 json.JSONDecodeError
        return "error", f"响应解析失败：{e}"
    if payload.get("status") != "success":
        return "error", f"Prometheus 返回 status={payload.get('status')}"
    results = payload.get("data", {}).get("result") or []
    return ("present" if results else "absent"), ""


def overall_exit(rows: list[tuple[str, str]]) -> int:
    if rows and all(st == "connfail" for st, _ in rows):
        return 3
    return 0 if all(st == "present" for st, _ in rows) else 1


def self_test() -> int:
    assert probe.__doc__  # 文档在
    assert STATUS_LABEL.keys() == {"present", "absent", "error", "connfail"}
    # classify 等价面（probe 的返回值语义）
    assert overall_exit([("present", ""), ("present", "")]) == 0
    assert overall_exit([("present", ""), ("absent", "")]) == 1
    assert overall_exit([("present", ""), ("error", "HTTP 500")]) == 1
    assert overall_exit([("connfail", "连接失败：x")]) == 3
    assert overall_exit([("connfail", ""), ("absent", "")]) == 1  # 部分连不上按有结果处理
    # 契约实载（本地文件，离线安全）
    available, pending = load_contract(str(DEFAULT_CONTRACT))
    names = [m["name"] for m in available]
    assert names and len(names) == len(set(names)), "available 重名"
    assert all(m.get("source") for m in available), "available 缺 source"
    assert all(p.get("requires") for p in pending), "pending 缺 requires"
    print(f"self-test PASS（契约 available={len(available)} pending={len(pending)}）")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="巡检项探测（metric-contract available 集）")
    parser.add_argument("--prom-url", help="Prometheus 根 URL，如 http://localhost:9090（网络探测需显式提供）")
    parser.add_argument("--contract", default=str(DEFAULT_CONTRACT), help="指标契约路径（默认仓内 metric-contract.yaml）")
    parser.add_argument("--timeout", type=float, default=5.0, help="单次查询超时秒数（默认 5）")
    parser.add_argument("--self-test", action="store_true", help="离线自检（不触网）")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.prom_url:
        parser.error("--prom-url 必填（网络探测为 opt-in；离线自检用 --self-test）")

    token = os.environ.get("PROM_TOKEN")  # 凭据只走 env，不落仓库、不打印
    available, pending = load_contract(args.contract)
    rows = []
    for m in available:
        st, detail = probe(args.prom_url, m["name"], args.timeout, token)
        rows.append((st, detail, m))

    print(f"{'指标':<45} {'来源':<22} 状态")
    print("-" * 78)
    for st, detail, m in rows:
        line = f"{m['name']:<45} {(m.get('source') or '-'):<22} {STATUS_LABEL[st]}"
        if detail:
            line += f"（{detail}）"
        print(line)
    if pending:
        print(f"\npending 集（{len(pending)} 条，结构性不查）：")
        for p in pending:
            print(f"  {p['name']:<45} requires: {p.get('requires', '-')}")

    present_n = sum(1 for st, _, _ in rows if st == "present")
    print(f"\n汇总：available {present_n}/{len(rows)} 有数据；pending {len(pending)} 条按契约不查。")
    print("提示：无数据≠未发射（瞬时零值/标签过滤同果），先按 D15 SOP §三第 4 步排伪装再定性。")
    return overall_exit([(st, d) for st, d, _ in rows])


if __name__ == "__main__":
    raise SystemExit(main())
