#!/usr/bin/env bash
# 钉版外链可达性预检（台账 #112）
#
# 为什么需要它（2026-11-03 实测事故）：Apache 快源目录只保留「当前发布线」，
# 我们钉的 spark 4.2.0 与回退版 3.5.5 被挤出后，tuna/aliyun/downloads 三条全 404，
# 只剩 archive.apache.org 有（530MB）。构建于是合法但极慢，被 scripts/build-retry.sh:43
# 的 600 秒挂起预算当挂起杀掉 ⇒ `Docker Build Verify` 两次 attempt 各烧 ~44 分钟后红，
# 面板上长得像"编译失败"。这条预检把同一个事实压到几十秒内说清楚。
#
# 判据（三态，不许把"没结论"当成通过或失败）：
#   OK            快源（tuna / aliyun）至少一条返回 HTTP 200
#   FAIL          快源全部返回**确定性 404**（ ⇒ 该发布线已被快源挤出，抬版本 + 更新 sha 清单）
#   INCONCLUSIVE  连不上（DNS/TLS/超时），拿不到 HTTP 状态 ⇒ 只告警，不判红也不判绿
# 慢源 archive.apache.org 只作降级记录：快源全 404 而 archive 200 时，FAIL 的文案会点名它。
#
# 用法：
#   bash scripts/check-pinned-downloads.sh            # 真检（发 HEAD 请求）
#   bash scripts/check-pinned-downloads.sh --self-test # 离线自测（喂状态码表，不碰网）
set -uo pipefail

SELF_TEST=0
[ "${1:-}" = "--self-test" ] && SELF_TEST=1

# 条目：名称|主源1|主源2|降级源
# 版本改动时必须同步 platform/batch-pipeline/docker/spark-cluster/Dockerfile 的 ARG 与
# CHECKSUMS.md 的 sha512/sha1 清单，二者不一致正是本检要拦的事。
PINNED_ITEMS='spark-bin-hadoop3(primary)|https://mirrors.tuna.tsinghua.edu.cn/apache/spark/spark-4.2.1/spark-4.2.1-bin-hadoop3.tgz|https://mirrors.aliyun.com/apache/spark/spark-4.2.1/spark-4.2.1-bin-hadoop3.tgz|https://archive.apache.org/dist/spark/spark-4.2.1/spark-4.2.1-bin-hadoop3.tgz
spark-bin-hadoop3(fallback)|https://mirrors.tuna.tsinghua.edu.cn/apache/spark/spark-3.5.9/spark-3.5.9-bin-hadoop3.tgz|https://mirrors.aliyun.com/apache/spark/spark-3.5.9/spark-3.5.9-bin-hadoop3.tgz|https://archive.apache.org/dist/spark/spark-3.5.9/spark-3.5.9-bin-hadoop3.tgz
hadoop-aws|https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-aws/3.5.0/hadoop-aws-3.5.0.jar|https://maven.aliyun.com/repository/central/org/apache/hadoop/hadoop-aws/3.5.0/hadoop-aws-3.5.0.jar|https://archive.apache.org/dist/
aws-sdk-v2-bundle|https://repo1.maven.org/maven2/software/amazon/awssdk/bundle/2.35.4/bundle-2.35.4.jar|https://maven.aliyun.com/repository/central/software/amazon/awssdk/bundle/2.35.4/bundle-2.35.4.jar|https://archive.apache.org/dist/
analyticsaccelerator-s3|https://repo1.maven.org/maven2/software/amazon/s3/analyticsaccelerator/analyticsaccelerator-s3/1.3.1/analyticsaccelerator-s3-1.3.1.jar|https://maven.aliyun.com/repository/central/software/amazon/s3/analyticsaccelerator/analyticsaccelerator-s3/1.3.1/analyticsaccelerator-s3-1.3.1.jar|https://archive.apache.org/dist/'

# 判据核心：给定各源的 HTTP 状态（空串＝连不上），返回 OK / FAIL / INCONCLUSIVE
classify() {
  # 三态判据：任一快源 200 ⇒ OK；可达快源全部 404 ⇒ FAIL（发布线被挤出）；
  # 其余（无状态、或 404 与 5xx 混合）⇒ INCONCLUSIVE——一次镜像站抖动不该红全仓。
  local -a codes=("$@")
  local fast_seen=0 fast_200=0 fast_non404=0 idx=0 c
  for c in "${codes[@]}"; do
    idx=$((idx+1))
    [ "$idx" -gt 2 ] && break
    [ -z "$c" ] && continue
    fast_seen=$((fast_seen+1))
    if [ "$c" = "200" ]; then fast_200=1
    elif [ "$c" != "404" ]; then fast_non404=1
    fi
  done
  if [ "$fast_200" = "1" ]; then echo "OK"; return; fi
  if [ "$fast_seen" -gt 0 ] && [ "$fast_non404" = "0" ]; then echo "FAIL"; return; fi
  echo "INCONCLUSIVE"
}

probe() {
  # 只取 HTTP 状态；连不上返回空串
  local url="$1" code
  code=$(curl -sI --max-time 25 --retry 1 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null) || code=""
  printf '%s' "$code"
}

run_self_test() {
  local pass=0 fail=0
  check_case() {
    local desc="$1" expect="$2"; shift 2
    local got; got=$(classify "$@")
    if [ "$got" = "$expect" ]; then pass=$((pass+1)); printf '  ✓ %s ⇒ %s\n' "$desc" "$got"
    else fail=$((fail+1)); printf '  ✗ %s ⇒ %s（期望 %s）\n' "$desc" "$got" "$expect"; fi
  }
  # 事故原样：快源两条 404、archive 200 ⇒ 必须 FAIL（而不是"还能下"就放过）
  check_case "快源全 404 + archive 200（2026-11-03 事故形态）" "FAIL" 404 404 200
  # 抬版后：tuna 200 ⇒ OK
  check_case "快源首条 200（抬到 4.2.1 之后）" "OK" 200 404 200
  check_case "快源第二条 200（只 aliyun 有）" "OK" 404 200 200
  # 完全消失 ⇒ FAIL（没有降级源可救）
  check_case "三条都 404" "FAIL" 404 404 404
  # 网络不通 ⇒ INCONCLUSIVE（不判红也不判绿）
  check_case "快源连不上、archive 也连不上" "INCONCLUSIVE" "" "" ""
  check_case "快源连不上但 archive 给了 200" "INCONCLUSIVE" "" "" 200
  # 快源连不上、其中一条给了 500（非 404 的确定性错误）⇒ 结论不足，按 INCONCLUSIVE
  check_case "快源一条超时、一条 500" "INCONCLUSIVE" "" 500 ""
  # 混合状态＝镜像站抖动，不算"发布线被挤出"
  check_case "一条快源 404、另一条 500" "INCONCLUSIVE" 404 500 ""
  # 变异反证：把"404 视为可用"写错时会立刻红在这里
  check_case "只有降级源 200 不算通过" "FAIL" 404 404 200
  echo "自测结果：通过 $pass 项 / 失败 $fail 项"
  [ "$fail" -eq 0 ] || return 1
  return 0
}

if [ "$SELF_TEST" = "1" ]; then
  echo "== check-pinned-downloads 自测（离线，不碰网）=="
  run_self_test
  exit $?
fi

echo "== 钉版外链可达性预检（台账 #112）=="
fails=0; incon=0; ok=0
while IFS='|' read -r name u1 u2 u3; do
  [ -z "${name:-}" ] && continue
  c1=$(probe "$u1"); c2=$(probe "$u2"); c3=$(probe "$u3")
  verdict=$(classify "$c1" "$c2" "$c3")
  case "$verdict" in
    OK)           ok=$((ok+1));            printf '  ✓ %-32s 快源可达 (%s/%s)\n' "$name" "${c1:--}" "${c2:--}" ;;
    INCONCLUSIVE) incon=$((incon+1));      printf '  ⚠ %-32s 连不上，拿不到 HTTP 状态（不判红也不判绿）\n' "$name" ;;
    *)            fails=$((fails+1));
                  printf '::error::钉住的发布线已被快源挤出：%s（快源状态 %s/%s，降级源 %s）\n' "$name" "${c1:--}" "${c2:--}" "${c3:--}"
                  echo "  ⇒ 处置：抬到快源在架的版本，并同步 Dockerfile 的 ARG 与 CHECKSUMS.md 的 sha512/sha1 清单；"
                  echo "  ⇒ 不要靠重跑解决：快源全 404 时构建会退到 archive 大文件，被 build-retry 的 600 秒挂起预算杀掉（台账 #112 的 2026-11-03 事故就是这么烧掉两次 ~44 分钟的）。" ;;
  esac
done <<< "$PINNED_ITEMS"

echo "汇总：可达 $ok / 不可达 $fails / 无结论 $incon"
if [ "$fails" -gt 0 ]; then
  echo "::error::有 $fails 个钉住的下载源已被快源挤出，阻断 CI（早失败，省掉一次 45 分钟的误诊）"
  exit 1
fi
if [ "$incon" -gt 0 ] && [ "$ok" -eq 0 ]; then
  echo "::warning::全部 $incon 条都拿不到 HTTP 状态（网络面），本检不下结论，不阻断"
  exit 0
fi
exit 0
