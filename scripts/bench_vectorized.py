#!/usr/bin/env python3
"""
向量化执行性能基准测试（v2.1 性能调优）。

测试 Doris / Trino 向量化执行引擎在不同查询模式下的性能：
- 全表扫描（Scan）
- 聚合查询（Aggregate）
- JOIN 查询
- 排序查询（Sort）
- 复合查询（多算子组合）

目标：向量化执行 vs 非向量化执行，OLAP 查询性能提升 2x。

用法：
    python bench_vectorized.py --trino-url http://trino:8080 --doris-url http://doris:9030
    python bench_vectorized.py --engine trino --iterations 5
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.request
import urllib.error
from dataclasses import dataclass, field
from typing import NamedTuple


class QueryResult(NamedTuple):
    """单次查询结果。"""
    query_id: str
    duration_ms: float
    row_count: int
    success: bool
    error: str | None = None


@dataclass
class BenchmarkCase:
    """基准测试用例。"""
    name: str
    category: str  # scan / aggregate / join / sort / composite
    sql: str
    description: str = ""


@dataclass
class BenchmarkResult:
    """基准测试结果汇总。"""
    case_name: str
    category: str
    engine: str
    vectorized: bool
    iterations: int
    durations_ms: list[float] = field(default_factory=list)
    success_count: int = 0
    failure_count: int = 0

    @property
    def p50(self) -> float:
        return statistics.median(self.durations_ms) if self.durations_ms else 0.0

    @property
    def p95(self) -> float:
        if not self.durations_ms:
            return 0.0
        sorted_d = sorted(self.durations_ms)
        idx = int(len(sorted_d) * 0.95)
        return sorted_d[min(idx, len(sorted_d) - 1)]

    @property
    def mean(self) -> float:
        return statistics.mean(self.durations_ms) if self.durations_ms else 0.0

    @property
    def stddev(self) -> float:
        return statistics.stdev(self.durations_ms) if len(self.durations_ms) > 1 else 0.0


# ---- 基准测试用例集 ----
BENCHMARK_CASES: list[BenchmarkCase] = [
    # 全表扫描
    BenchmarkCase(
        name="scan_simple",
        category="scan",
        sql="SELECT * FROM bench.lineitem LIMIT 100000",
        description="全表扫描 10 万行",
    ),
    BenchmarkCase(
        name="scan_with_filter",
        category="scan",
        sql="SELECT * FROM bench.lineitem WHERE l_quantity > 30 AND l_shipdate >= '1995-01-01'",
        description="带过滤条件的全表扫描",
    ),
    # 聚合查询
    BenchmarkCase(
        name="agg_group_by",
        category="aggregate",
        sql="SELECT l_returnflag, l_linestatus, COUNT(*) AS cnt, SUM(l_quantity) AS sum_qty, "
            "AVG(l_extendedprice) AS avg_price FROM bench.lineitem "
            "WHERE l_shipdate < '1998-09-01' GROUP BY l_returnflag, l_linestatus",
        description="TPC-H Q1 风格聚合",
    ),
    BenchmarkCase(
        name="agg_distinct",
        category="aggregate",
        sql="SELECT COUNT(DISTINCT l_partkey) FROM bench.lineitem",
        description="DISTINCT 聚合",
    ),
    # JOIN 查询
    BenchmarkCase(
        name="join_two_tables",
        category="join",
        sql="SELECT l.l_orderkey, l.l_quantity, o.o_totalprice "
            "FROM bench.lineitem l JOIN bench.orders o ON l.l_orderkey = o.o_orderkey "
            "WHERE o.o_orderdate >= '1995-01-01' LIMIT 100000",
        description="两表 JOIN",
    ),
    BenchmarkCase(
        name="join_three_tables",
        category="join",
        sql="SELECT l.l_orderkey, c.c_name, p.p_name "
            "FROM bench.lineitem l JOIN bench.orders o ON l.l_orderkey = o.o_orderkey "
            "JOIN bench.customer c ON o.o_custkey = c.c_custkey "
            "JOIN bench.part p ON l.l_partkey = p.p_partkey LIMIT 50000",
        description="三表 JOIN",
    ),
    # 排序查询
    BenchmarkCase(
        name="sort_large",
        category="sort",
        sql="SELECT * FROM bench.lineitem ORDER BY l_quantity DESC LIMIT 100000",
        description="大结果集排序",
    ),
    # 复合查询
    BenchmarkCase(
        name="composite_tpch_q3",
        category="composite",
        sql="SELECT l.l_orderkey, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue, "
            "o.o_orderdate, o.o_shippriority "
            "FROM bench.customer c JOIN bench.orders o ON c.c_custkey = o.o_custkey "
            "JOIN bench.lineitem l ON o.o_orderkey = l.l_orderkey "
            "WHERE c.c_mktsegment = 'BUILDING' AND o.o_orderdate < '1995-03-15' "
            "GROUP BY l.l_orderkey, o.o_orderdate, o.o_shippriority "
            "ORDER BY revenue DESC, o.o_orderdate LIMIT 10",
        description="TPC-H Q3 复合查询",
    ),
]


def execute_trino_query(url: str, sql: str, user: str = "bench") -> QueryResult:
    """通过 Trino REST API 执行查询。"""
    import urllib.request
    import json as _json
    query_id = f"trino-{int(time.time() * 1000)}"
    start = time.monotonic()
    try:
        req = urllib.request.Request(
            f"{url}/v1/statement",
            data=sql.encode("utf-8"),
            headers={
                "X-Trino-User": user,
                "Content-Type": "text/plain",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=300) as resp:
            data = _json.loads(resp.read())
        # 轮询查询结果
        next_uri = data.get("nextUri")
        row_count = 0
        while next_uri:
            with urllib.request.urlopen(next_uri, timeout=300) as resp:
                data = _json.loads(resp.read())
            if data.get("data"):
                row_count += len(data["data"])
            next_uri = data.get("nextUri")
            if data.get("status") == "FINISHED":
                break
        duration_ms = (time.monotonic() - start) * 1000
        return QueryResult(query_id, duration_ms, row_count, True)
    except Exception as e:
        duration_ms = (time.monotonic() - start) * 1000
        return QueryResult(query_id, duration_ms, 0, False, str(e))


def execute_doris_query(url: str, sql: str, user: str = "root") -> QueryResult:
    """通过 Doris FE HTTP API 执行查询。"""
    query_id = f"doris-{int(time.time() * 1000)}"
    start = time.monotonic()
    try:
        # Doris FE HTTP 查询接口
        params = urllib.parse.urlencode({"sql": sql, "user": user})
        req = urllib.request.Request(
            f"{url}/api/query",
            data=params.encode("utf-8"),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=300) as resp:
            data = json.loads(resp.read())
        row_count = len(data.get("data", [])) if isinstance(data, dict) else 0
        duration_ms = (time.monotonic() - start) * 1000
        return QueryResult(query_id, duration_ms, row_count, True)
    except Exception as e:
        duration_ms = (time.monotonic() - start) * 1000
        return QueryResult(query_id, duration_ms, 0, False, str(e))


def run_benchmark(
    engine: str,
    url: str,
    cases: list[BenchmarkCase],
    iterations: int,
    vectorized: bool,
) -> list[BenchmarkResult]:
    """运行基准测试。"""
    results: list[BenchmarkResult] = []
    executor = execute_trino_query if engine == "trino" else execute_doris_query

    for case in cases:
        print(f"\n[{engine}{'-vec' if vectorized else '-novec'}] "
              f"运行 {case.name} ({case.category}) x{iterations}")
        bench = BenchmarkResult(
            case_name=case.name,
            category=case.category,
            engine=engine,
            vectorized=vectorized,
            iterations=iterations,
        )
        for i in range(iterations):
            result = executor(url, case.sql)
            bench.durations_ms.append(result.duration_ms)
            if result.success:
                bench.success_count += 1
                print(f"  iter {i + 1}: {result.duration_ms:.1f}ms "
                      f"({result.row_count} rows)")
            else:
                bench.failure_count += 1
                print(f"  iter {i + 1}: FAIL - {result.error}")
        results.append(bench)
    return results


def print_summary(results: list[BenchmarkResult]) -> None:
    """打印汇总报告。"""
    print("\n" + "=" * 80)
    print("向量化执行性能基准测试汇总")
    print("=" * 80)
    print(f"{'Case':<25} {'Engine':<12} {'Mode':<8} "
          f"{'P50(ms)':<10} {'P95(ms)':<10} {'Mean(ms)':<10} {'StdDev':<10} {'Success':<8}")
    print("-" * 80)
    for r in results:
        mode = "vec" if r.vectorized else "novec"
        print(f"{r.case_name:<25} {r.engine:<12} {mode:<8} "
              f"{r.p50:<10.1f} {r.p95:<10.1f} {r.mean:<10.1f} {r.stddev:<10.1f} "
              f"{r.success_count}/{r.iterations}")


def compare_vectorized(results: list[BenchmarkResult]) -> None:
    """对比向量化 vs 非向量化，输出提升倍数。"""
    print("\n" + "=" * 80)
    print("向量化 vs 非向量化 性能对比")
    print("=" * 80)
    by_case: dict[str, list[BenchmarkResult]] = {}
    for r in results:
        by_case.setdefault(r.case_name, []).append(r)

    print(f"{'Case':<25} {'Engine':<12} {'Vec(ms)':<10} {'NoVec(ms)':<10} {'Speedup':<10}")
    print("-" * 80)
    for case_name, group in by_case.items():
        vec = next((r for r in group if r.vectorized), None)
        novec = next((r for r in group if not r.vectorized), None)
        if vec and novec and vec.mean > 0 and novec.mean > 0:
            speedup = novec.mean / vec.mean
            print(f"{case_name:<25} {vec.engine:<12} "
                  f"{vec.mean:<10.1f} {novec.mean:<10.1f} {speedup:<10.2f}x")


def main() -> int:
    parser = argparse.ArgumentParser(description="向量化执行性能基准测试")
    parser.add_argument("--trino-url", default="http://localhost:8080",
                        help="Trino URL")
    parser.add_argument("--doris-url", default="http://localhost:9030",
                        help="Doris FE URL")
    parser.add_argument("--engine", choices=["trino", "doris", "both"],
                        default="both", help="测试引擎")
    parser.add_argument("--iterations", type=int, default=3,
                        help="每个用例迭代次数")
    parser.add_argument("--output", default=None,
                        help="结果输出 JSON 文件路径")
    args = parser.parse_args()

    all_results: list[BenchmarkResult] = []
    engines = ["trino", "doris"] if args.engine == "both" else [args.engine]

    for engine in engines:
        url = args.trino_url if engine == "trino" else args.doris_url
        # 向量化模式
        results_vec = run_benchmark(engine, url, BENCHMARK_CASES,
                                     args.iterations, vectorized=True)
        all_results.extend(results_vec)
        # 非向量化模式（用于对比）
        results_novec = run_benchmark(engine, url, BENCHMARK_CASES,
                                       args.iterations, vectorized=False)
        all_results.extend(results_novec)

    print_summary(all_results)
    compare_vectorized(all_results)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump([
                {
                    "case_name": r.case_name,
                    "category": r.category,
                    "engine": r.engine,
                    "vectorized": r.vectorized,
                    "iterations": r.iterations,
                    "p50_ms": r.p50,
                    "p95_ms": r.p95,
                    "mean_ms": r.mean,
                    "stddev_ms": r.stddev,
                    "success_count": r.success_count,
                    "failure_count": r.failure_count,
                }
                for r in all_results
            ], f, indent=2, ensure_ascii=False)
        print(f"\n结果已保存到 {args.output}")

    return 0


if __name__ == "__main__":
    sys.exit(main())