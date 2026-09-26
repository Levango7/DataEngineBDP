"""统计非测试源码行数（README 数字订正用的可复现口径）。

用法：python scripts/count-source-lines.py
口径：按行计数（含空行与注释），排除测试目录、构建产物与第三方目录。
"""

from __future__ import annotations

import glob
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

EXCLUDE_JAVA = ("/src/test/", "/target/")
EXCLUDE_GO = ("_test.go", "/vendor/")
EXCLUDE_PY = ("/tests/", "/test/", "/target/", "/node_modules/")


def count_lines(patterns: list[str], exclude: tuple[str, ...]) -> int:
    total = 0
    for pattern in patterns:
        for path in glob.glob(pattern, recursive=True):
            norm = path.replace("\\", "/")
            if any(x in norm for x in exclude):
                continue
            try:
                with open(path, encoding="utf-8", errors="ignore") as fh:
                    total += sum(1 for _ in fh)
            except OSError:
                pass
    return total


def main() -> int:
    java = count_lines(["platform/**/*.java"], EXCLUDE_JAVA)
    go = count_lines(["platform/**/*.go"], EXCLUDE_GO)
    py = count_lines(["platform/**/*.py"], EXCLUDE_PY)
    ts = count_lines(["frontend/src/**/*.ts", "frontend/src/**/*.vue"], ("/node_modules/",))
    java_all = count_lines(["platform/**/*.java"], ("/target/",))
    py_all = count_lines(["platform/**/*.py"], ("/target/", "/node_modules/"))

    print(f"Java 非测试: {java:,}")
    print(f"Go   非测试: {go:,}")
    print(f"Py   非测试: {py:,}")
    print(f"后端非测试合计: {java + go + py:,}")
    print(f"前端 src: {ts:,}")
    print(f"全部（含测试）: Java {java_all:,} / Python {py_all:,}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
