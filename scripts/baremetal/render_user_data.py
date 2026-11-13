#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""渲染 cloud-init user-data 模板 —— 台账 #121 路径 ③/④ 的投放前置。

为什么需要它
------------
`scripts/baremetal/cloud-init/user-data.yaml` 是**模板**，里面有 `__K3S_TOKEN__` 这类占位符。
直接把模板投给装机机会得到一台「拿着字面量 token 去连集群」的机器 —— 这类事故很难在事后看出来。
本脚本把渲染做成**可校验的一步**：

- 渲染后若仍有未解析的 `__XXX__` 占位符 ⇒ **判红退出**，不产出文件；
  占位符形态限定为**大写 + 数字 + 下划线**（与 `serve-pxe.sh --check-only` 的 grep 口径一致），
  小写形态按普通文本处理，不算占位符；
- 输出路径若落在 **git 工作树内** ⇒ 默认拒绝（避免把含 token 的渲染结果提交进版本库），
  确需落仓内时用 `--force`；
- 键值来源：`--vars KEY=VALUE` 文件与 `--set KEY=VALUE` 命令行，命令行优先。

用法
----
    python3 scripts/baremetal/render_user_data.py \
        --template scripts/baremetal/cloud-init/user-data.yaml \
        --vars /etc/bdp-node/node.env \
        --out /srv/pxe/user-data.yaml

退出码：0 = 渲染完成且无残留占位符；1 = 有未解析占位符 / 写入被拒 / 参数错。
"""

from __future__ import annotations

import argparse
import os
import re
import sys

PLACEHOLDER_RE = re.compile(r"__[A-Z0-9_]+__")


def parse_kv_file(path):
    out = {}
    with open(path, encoding="utf-8") as f:
        for lineno, raw in enumerate(f, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                raise ValueError("%s 第 %d 行不是 KEY=VALUE 形态: %r" % (path, lineno, raw.rstrip("\n")))
            key, value = line.split("=", 1)
            key = key.strip()
            if not key:
                raise ValueError("%s 第 %d 行键名为空" % (path, lineno))
            out[key] = value.strip()
    return out


def in_git_worktree(path):
    """向上找 .git（目录或文件），判断目标路径是否落在 git 工作树内。"""
    cur = os.path.abspath(path)
    while True:
        parent = os.path.dirname(cur)
        if parent == cur:
            return False
        if os.path.exists(os.path.join(cur, ".git")):
            return True
        cur = parent


def render(template_text, values):
    missing = []

    def repl(match):
        key = match.group(0).strip("_")
        if key in values:
            return values[key]
        missing.append(key)
        return match.group(0)

    rendered = PLACEHOLDER_RE.sub(repl, template_text)
    return rendered, sorted(set(missing))


def main(argv=None):
    ap = argparse.ArgumentParser(description="渲染 cloud-init user-data 模板")
    ap.add_argument("--template", default="", help="模板文件")
    ap.add_argument("--vars", default="", help="键值文件（KEY=VALUE 每行一条）")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE",
                    help="命令行键值，可重复；优先级高于 --vars")
    ap.add_argument("--out", default="", help="输出文件")
    ap.add_argument("--force", action="store_true", help="允许输出到 git 工作树内（默认拒绝）")
    ap.add_argument("--self-test", action="store_true", help="跑离线自测（不读写文件）")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    if not args.template or not args.out:
        print("FAIL 需要 --template 与 --out（--self-test 除外）", file=sys.stderr)
        return 1

    values = {}
    if args.vars:
        try:
            values.update(parse_kv_file(args.vars))
        except (OSError, ValueError) as exc:
            print("FAIL 读取 --vars 失败: %s" % exc, file=sys.stderr)
            return 1
    for item in args.set:
        if "=" not in item:
            print("FAIL --set 需要 KEY=VALUE 形态: %r" % item, file=sys.stderr)
            return 1
        key, value = item.split("=", 1)
        values[key.strip()] = value

    try:
        with open(args.template, encoding="utf-8") as f:
            template_text = f.read()
    except OSError as exc:
        print("FAIL 读取模板失败: %s" % exc, file=sys.stderr)
        return 1

    rendered, missing = render(template_text, values)
    if missing:
        print("FAIL 未解析占位符 %d 个: %s" % (len(missing), ", ".join(missing)), file=sys.stderr)
        print("     （模板 %s）" % args.template, file=sys.stderr)
        return 1

    out_abs = os.path.abspath(args.out)
    if in_git_worktree(out_abs) and not args.force:
        print("FAIL 输出路径落在 git 工作树内: %s" % out_abs, file=sys.stderr)
        print("     渲染结果可能含 token 等敏感值，不应进版本库；确需写这里请加 --force", file=sys.stderr)
        return 1

    out_dir = os.path.dirname(out_abs)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(out_abs, "w", encoding="utf-8", newline="\n") as f:
        f.write(rendered)
    try:
        os.chmod(out_abs, 0o600)
    except OSError:
        pass  # Windows 上没有 POSIX 权限位，忽略
    print("OK 已渲染 %s（%d 字节，权限 0600）" % (out_abs, len(rendered.encode("utf-8"))))
    return 0


def self_test():
    """四组断言：全渲染 / 缺键判红 / 占位符正则只认大写下划线 / git 工作树判定。"""
    failures = []
    tpl = "hostname: __NODE_HOSTNAME__\ntoken: __K3S_TOKEN__\n"

    rendered, missing = render(tpl, {"NODE_HOSTNAME": "n1", "K3S_TOKEN": "t1"})
    if missing:
        failures.append("全渲染：不应有缺失键，实为 %s" % missing)
    if "hostname: n1" not in rendered or "token: t1" not in rendered:
        failures.append("全渲染：占位符未被替换")

    rendered, missing = render(tpl, {"NODE_HOSTNAME": "n1"})
    if "K3S_TOKEN" not in missing:
        failures.append("缺键：K3S_TOKEN 应被报为缺失，实为 %s" % missing)
    if "__K3S_TOKEN__" not in rendered:
        failures.append("缺键：未解析占位符应原样保留")

    rendered, missing = render("a: __lower_case__ b: __X1__", {})
    if missing != ["X1"]:
        failures.append("占位符识别：只认大写形态（与 serve-pxe.sh 的 grep 口径一致），实为 %s" % missing)

    if not in_git_worktree(os.path.dirname(os.path.abspath(__file__))):
        failures.append("git 判定：仓内路径应判为工作树内")
    if in_git_worktree(os.path.abspath(os.sep)):
        failures.append("git 判定：根目录不应判为工作树内")

    for f in failures:
        print("SELF-TEST FAIL: %s" % f)
    print("self-test: %s（4 项断言）" % ("OK" if not failures else "FAIL"))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
