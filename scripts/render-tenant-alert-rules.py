#!/usr/bin/env python3
"""渲染租户告警规则模板 → Prometheus 可直接加载的规则文件。

为什么需要：platform/observability/rules/*.yaml 是"模板"，内含 {{tenant_id}}、
{{fail_threshold}} 等契约变量；而 rules 文件是经 ConfigMap 原样挂给 Prometheus 的，
中间从未有任何替换步骤。expr 里的 "{{tenant_id}}" 是合法的 PromQL 字符串字面量，
promtool 与 Prometheus 都不会报错，但它匹配不到任何序列 —— 规则永久静默。
本脚本把这层从未落地的"租户自定义阈值"契约补成真正可执行的东西。

用法：
  # 平台侧：一份规则覆盖全部租户（按 tenant_id 分组逐租户触发）
  python3 scripts/render-tenant-alert-rules.py --out-dir <dir>

  # 单租户定制
  python3 scripts/render-tenant-alert-rules.py --tenant-id acme-corp \\
      --fail-threshold 0.05 --out-dir <dir>
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RULES_DIR = REPO_ROOT / "platform" / "observability" / "rules"

# 契约变量 → 默认值。默认值即"平台侧推荐值"，写在这里是唯一事实来源。
DEFAULTS: dict[str, str] = {
    "tenant_id": ".+",  # =~ 插值；".+" 表示所有租户
    "threshold": "0",  # P0：不可达 Pod 数 > 0 即告警
    "latency_p95": "5",  # P1：SQL 网关 P95 延迟（秒）
    "disk_threshold": "0.8",  # P1：租户存储水位（比率）
    "fail_threshold": "0.1",  # P1：租户请求失败率（比率）
    "mem_threshold": "0.9",  # P2：容器内存 / limit（比率）
    "slow_query_threshold": "2",  # P2：SQL 网关 P99 延迟（秒）
}

# 契约变量：{{name}}（小写字母/下划线）。Prometheus 自身的模板写法是
# {{ $labels.x }} —— 以 $ 开头且含空格，因此不会被本正则误伤。
VAR_RE = re.compile(r"\{\{([a-z_][a-z0-9_]*)\}\}")

# 租户 ID 会被插进 PromQL 的正则字面量，必须限制字符集，
# 否则一个带 "." 或 "*" 的 ID 会静默改变匹配范围（甚至跨租户误告警）。
TENANT_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]*$")


def strip_comments(text: str) -> str:
    """去掉整行注释，只留数据字段——占位符残留判定只关心这些。"""
    return "\n".join(ln for ln in text.splitlines() if not ln.lstrip().startswith("#"))


def render_text(text: str, values: dict[str, str], src: Path) -> str:
    """替换全部契约变量；遇到未知变量立即报错，不产出半成品。

    注释行（首个非空白字符为 #）保持原样：模板说明里要写明"{{tenant_id}}"这个变量名
    本身，替换后注释反而读不通。Prometheus 只解析数据字段，注释保留占位符无副作用。
    """
    lines = []
    for line in text.splitlines(keepends=True):
        if line.lstrip().startswith("#"):
            lines.append(line)
        else:
            lines.append(VAR_RE.sub(lambda m: values[m.group(1)], line))
    rendered = "".join(lines)

    body = strip_comments(rendered)
    unknown = sorted({m.group(1) for m in VAR_RE.finditer(body)} - set(values))
    if unknown:
        print(
            f"ERROR: {src.name} 使用了未登记的模板变量: {', '.join(unknown)}\n"
            f"       请在 {__file__} 的 DEFAULTS 中补充默认值，或在规则里改用已支持的字面量。",
            file=sys.stderr,
        )
        raise SystemExit(1)
    return rendered


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rules-dir", type=Path, default=DEFAULT_RULES_DIR)
    ap.add_argument("--out-dir", type=Path, required=True)
    ap.add_argument("--tenant-id", help="单个租户 ID（如 acme-corp）；与 --tenant-regex 互斥")
    ap.add_argument("--tenant-regex", help="自定义租户正则（默认 .+ = 覆盖全部租户）")
    for name in DEFAULTS:
        if name == "tenant_id":
            continue
        ap.add_argument(
            f"--{name.replace('_', '-')}", default=None, help=f"覆盖 {{{{{name}}}}} 默认值 {DEFAULTS[name]}"
        )
    args = ap.parse_args()

    if args.tenant_id and args.tenant_regex:
        print("ERROR: --tenant-id 与 --tenant-regex 互斥", file=sys.stderr)
        return 2
    values = dict(DEFAULTS)
    if args.tenant_id:
        if not TENANT_ID_RE.match(args.tenant_id):
            print(
                f"ERROR: 非法租户 ID {args.tenant_id!r}（只允许 ^[a-z0-9][a-z0-9-]*$）。\n"
                f"       该值会插入 PromQL 正则字面量，放宽字符集等于改变匹配范围。",
                file=sys.stderr,
            )
            return 2
        values["tenant_id"] = args.tenant_id
    elif args.tenant_regex:
        values["tenant_id"] = args.tenant_regex

    for name in DEFAULTS:
        if name == "tenant_id":
            continue
        got = getattr(args, name)
        if got is not None:
            values[name] = str(got)

    if not args.rules_dir.is_dir():
        print(f"ERROR: 规则目录不存在: {args.rules_dir}", file=sys.stderr)
        return 2
    srcs = sorted(p for p in args.rules_dir.glob("*.yaml") if p.is_file())
    if not srcs:
        print(f"ERROR: {args.rules_dir} 下没有规则文件", file=sys.stderr)
        return 2

    args.out_dir.mkdir(parents=True, exist_ok=True)
    for src in srcs:
        rendered = render_text(src.read_text(encoding="utf-8"), values, src)
        leftover = sorted({m.group(1) for m in VAR_RE.finditer(strip_comments(rendered))})
        if leftover:  # render_text 已拦未知变量，此处是兜底不变量
            print(f"ERROR: {src.name} 渲染后数据字段仍残留变量 {leftover}", file=sys.stderr)
            return 1
        out = args.out_dir / src.name
        # newline="\n" 是必须的：Windows 上默认文本模式会把 \n 写成 \r\n，
        # 而 chart 副本要进 Linux 容器与 yamllint（new-lines 规则）—— CRLF 会让两者都报错。
        with open(out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(rendered)
        print(f"rendered {src.name}")

    scope = args.tenant_id or values["tenant_id"]
    print(f'完成：{len(srcs)} 个文件已渲染到 {args.out_dir}（tenant_id=~"{scope}"）')
    return 0


if __name__ == "__main__":
    sys.exit(main())
