#!/usr/bin/env bash
# 钉版外链可达性预检（台账 #112）
#
# 为什么需要它（2026-11-03 实测事故）：Apache 快源目录只保留「当前发布线」，
# 我们钉的 spark 4.2.0 与回退版 3.5.5 被挤出后，tuna/aliyun/downloads 三条全 404，
# 只剩 archive.apache.org 有（530MB）。构建于是合法但极慢，被 scripts/build-retry.sh 的
# 600 秒挂起预算当挂起杀掉 ⇒ `Docker Build Verify` 两次 attempt 各烧 ~44 分钟后红，
# 面板上长得像「编译失败」。这条预检把同一个事实压到几十秒内说清楚。
#
# ── 2026-11-15 复核（台账 #112 第 ⑥ 项待裁加固，本轮落地） ──────────────
# ① **探测方法从 HEAD 改为限量 Range GET**。原实现探的是 HEAD（`curl -sI`），
#    HEAD 只取响应头、**不取 body**，因此探不出「源应答了但一个字节都不给」的
#    body stall。PR #795 的 CI（run 43176621456 / job 131042291495）实测形态正是这个：
#    预检 step 判 success（08:20:01Z→08:20:11Z），构建期的 GET 仍挂起 600s
#    ⇒ 「预检绿 ≠ 构建绿」。现在探的就是构建期真正取数的那条路径（Range GET），
#    并记录实收字节数；实测 2026-11-15 本机 8 个真实坐标全部回 HTTP 206 + 精确
#    262144 字节（0.18–1.99s），事故版本 4.2.0/3.5.5 在 tuna/aliyun 回 404、
#    在 archive 回 206 ⇒ 新探测法同时覆盖「被挤出」与「body stall」两种形态。
# ② **INCONCLUSIVE 收紧为大件可判红的 DEGRADED 档**。大件（Spark tarball）
#    全部源都拿不到字节 ⇒ 构建期必然挂到 job 超时 ⇒ 早失败省掉一次 60 分钟的误诊；
#    小件（Maven JAR）构建期 curl 的 --max-time 只有 60~120s，挂了不烧预算 ⇒ 仍不判红。
# ③ **清单与 Dockerfile 的源清单双源一致（fail-closed）**：本脚本的清单与
#    `platform/batch-pipeline/docker/spark-cluster/Dockerfile` 的锚点逐项重建比对，
#    任一侧改了而另一侧没改 ⇒ 判红；Dockerfile 读不到 / 锚点提取不到 ⇒ 也判红
#    （绝不静默放行）。
# ④ 顺带修掉一个实测到的清单缺陷：Maven 三条条目的第 3 源是**占位 URL**
#    （`https://archive.apache.org/dist/` 实测回 HTTP 200 + 36323 字节的目录页，
#    根本不是那个 JAR），且前两源顺序与 Dockerfile 相反（Dockerfile 先 aliyun 后
#    repo1）。两处都改成与 Dockerfile 的真实取数源、真实顺序一致。
#
# 判据（四态，不许把「没结论」当成通过或失败）：
#   OK            源交出了字节（2xx/206 且实收字节 > 0）
#   FAIL          快源确定性 404 ⇒ 该发布线已被快源挤出（抬版本 + 更新 sha 清单）
#   DEGRADED      全部源都拿不到字节（连不上 / 应答了但零字节）⇒ 传输面已坏
#   INCONCLUSIVE  状态不足（如 404 与 5xx 混合）⇒ 只告警，不判红也不判绿
#
# 用法：
#   bash scripts/check-pinned-downloads.sh             # 真检（限量 Range GET）
#   bash scripts/check-pinned-downloads.sh --self-test  # 离线自测（喂状态码表，不碰网）
set -uo pipefail

SELF_TEST=0
[ "${1:-}" = "--self-test" ] && SELF_TEST=1

# ── 探测参数 ──────────────────────────────────────────────────────────
PROBE_TIMEOUT="${PINNED_DOWNLOAD_PROBE_TIMEOUT:-20}"   # 单源探测墙钟上限（秒）
RANGE_BYTES="${PINNED_DOWNLOAD_RANGE_BYTES:-262144}"   # 每次取多少字节（256 KiB）
MAX_BYTES="${PINNED_DOWNLOAD_MAX_BYTES:-4194304}"      # 单次最多允许落盘多少字节（4 MiB）
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKERFILE_PATH="${PINNED_DOWNLOAD_DOCKERFILE:-${REPO_ROOT}/platform/batch-pipeline/docker/spark-cluster/Dockerfile}"

# ── 条目表 ────────────────────────────────────────────────────────────
# 格式：名称|类别|模式|快源数|源1|源2|源3
#   类别 large＝大件（Spark tarball，构建期 GET 整包，挂了会吃满预算）
#         small＝小件（Maven JAR，构建期 curl --max-time 60~120s，挂了不烧预算）
#   模式 squeeze＝快源被挤出即 FAIL（发布线会滚动，快源没有就是没有）；
#         plain ＝任一源有即 OK，全 404 才 FAIL（Maven 制品不按发布线滚动）
#   快源数＝前 N 个源是快源，其余是兜底源
#   源顺序＝Dockerfile 里的实际尝试顺序（一致性自检会逐项核对，顺序不一致也判红）
PINNED_ITEMS='spark-bin-hadoop3(primary)|large|squeeze|2|https://mirrors.tuna.tsinghua.edu.cn/apache/spark/spark-4.2.1/spark-4.2.1-bin-hadoop3.tgz|https://mirrors.aliyun.com/apache/spark/spark-4.2.1/spark-4.2.1-bin-hadoop3.tgz|https://archive.apache.org/dist/spark/spark-4.2.1/spark-4.2.1-bin-hadoop3.tgz
spark-bin-hadoop3(fallback)|large|squeeze|2|https://mirrors.tuna.tsinghua.edu.cn/apache/spark/spark-3.5.9/spark-3.5.9-bin-hadoop3.tgz|https://mirrors.aliyun.com/apache/spark/spark-3.5.9/spark-3.5.9-bin-hadoop3.tgz|https://archive.apache.org/dist/spark/spark-3.5.9/spark-3.5.9-bin-hadoop3.tgz
hadoop-aws|small|plain|1|https://maven.aliyun.com/repository/central/org/apache/hadoop/hadoop-aws/3.5.0/hadoop-aws-3.5.0.jar|https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-aws/3.5.0/hadoop-aws-3.5.0.jar|
aws-sdk-v2-bundle|small|plain|1|https://maven.aliyun.com/repository/central/software/amazon/awssdk/bundle/2.35.4/bundle-2.35.4.jar|https://repo1.maven.org/maven2/software/amazon/awssdk/bundle/2.35.4/bundle-2.35.4.jar|
analyticsaccelerator-s3|small|plain|1|https://maven.aliyun.com/repository/central/software/amazon/s3/analyticsaccelerator/analyticsaccelerator-s3/1.3.1/analyticsaccelerator-s3-1.3.1.jar|https://repo1.maven.org/maven2/software/amazon/s3/analyticsaccelerator/analyticsaccelerator-s3/1.3.1/analyticsaccelerator-s3-1.3.1.jar|'

# Maven 条目在 Dockerfile 里的制品路径模板（与 Dockerfile 的 URL 模板逐段对应）。
# 覆盖的漂移类型：版本 ARG / 源根 / 源顺序 / 快源数 / 条目数 / 制品版本。
# 边界（显式声明，不假装覆盖）：本检查**不覆盖** Dockerfile 内部制品路径模板本身被改写的情形
# —— 即路径模板变了、版本 ARG 没变时，重建出来的坐标会与清单不一致而被抓到；但若有人
# 同时改了路径模板**和**清单条目表（本表在脚本里），两边仍然一致 ⇒ 本检查不会报警。
# 该情形只能靠人工同步 + CHECKSUMS.md 的 sha512/sha1 复核兜住。
MAVEN_PATHS='hadoop-aws|/org/apache/hadoop/hadoop-aws/${HADOOP_AWS_VERSION}/hadoop-aws-${HADOOP_AWS_VERSION}.jar|HADOOP_AWS_VERSION
aws-sdk-v2-bundle|/software/amazon/awssdk/bundle/${AWS_SDK_V2_BUNDLE_VERSION}/bundle-${AWS_SDK_V2_BUNDLE_VERSION}.jar|AWS_SDK_V2_BUNDLE_VERSION
analyticsaccelerator-s3|/software/amazon/s3/analyticsaccelerator/analyticsaccelerator-s3/${ANALYTICS_ACCELERATOR_VERSION}/analyticsaccelerator-s3-${ANALYTICS_ACCELERATOR_VERSION}.jar|ANALYTICS_ACCELERATOR_VERSION'

# ── 判据核心 ──────────────────────────────────────────────────────────
# 单源状态：GOOD(2xx/206 且实收字节 > 0) / EMPTY(2xx/206 但零字节) / GONE(404) /
#           BROKEN(其它确定性状态码) / UNREACHABLE(000 或空＝连不上)
# 入参：模式 快源数 "<code> <bytes>" ...
classify() {
  local mode="$1" nfast="$2"; shift 2
  local -a states=("$@")
  local idx=0 st code size total=0 answered=0 gone=0
  local fast_good=0 fast_answered=0 fast_all_gone=1 any_good=0 any_gone=0 any_broken=0

  for st in "${states[@]}"; do
    total=$((total + 1))
    idx=$((idx + 1))
    code="${st%% *}"; size="${st##* }"
    [ -n "${code:-}" ] || code="000"
    [ -n "${size:-}" ] || size="0"
    [ "$code" = "000" ] && continue
    answered=$((answered + 1))
    case "$code" in
      200|206)
        if [ "$size" -gt 0 ]; then
          any_good=1
          [ "$idx" -le "$nfast" ] && fast_good=1
        fi
        ;;
      404) any_gone=1; gone=$((gone + 1)) ;;
      *)   any_broken=1 ;;
    esac
    if [ "$idx" -le "$nfast" ]; then
      fast_answered=1
      [ "$code" = "404" ] || fast_all_gone=0
    fi
  done

  if [ "$mode" = "plain" ]; then
    # Maven 制品不按发布线滚动：任一源有字节即 OK，全部源确定性 404 才 FAIL
    [ "$any_good" = "1" ] && { echo "OK"; return; }
    if [ "$answered" -eq "$total" ] && [ "$gone" -eq "$total" ]; then echo "FAIL"; return; fi
  else
    # Spark 发布线会滚动：快源交出字节即 OK；快源确定性 404 即 FAIL（兜底源再慢也不算数）
    [ "$fast_good" = "1" ] && { echo "OK"; return; }
    if [ "$fast_answered" = "1" ] && [ "$fast_all_gone" = "1" ]; then echo "FAIL"; return; fi
  fi
  # 没有任何源交出字节、也没有任何源给出确定性错误 ⇒ 传输面已坏（连不上 / body stall）
  if [ "$any_good" = "0" ] && [ "$any_gone" = "0" ] && [ "$any_broken" = "0" ]; then
    echo "DEGRADED"; return
  fi
  echo "INCONCLUSIVE"
}

# ── 判据到「是否阻断」的映射 ─────────────────────────────────────────
policy_verdict() {
  local cls="$1" verdict="$2"
  case "$verdict" in
    FAIL) echo "RED" ;;
    DEGRADED) [ "$cls" = "large" ] && echo "RED" || echo "PASS" ;;
    *) echo "PASS" ;;
  esac
}

# ── 探测：限量 Range GET（与构建期的 GET 同源，且真的取字节）──────────
probe() {
  local url="$1" code size tmp out
  if [ -n "${PINNED_DOWNLOAD_PROBE_CMD:-}" ]; then
    # 自测注入点：外部命令负责回 "<http_code> <实收字节>"（空串＝连不上）
    out="$("$PINNED_DOWNLOAD_PROBE_CMD" "$url" 2>/dev/null || true)"
    code="${out%% *}"; size="${out##* }"
    [ -n "${code:-}" ] || code="000"
    [ -n "${size:-}" ] || size="0"
    printf '%s %s' "$code" "$size"
    return
  fi
  tmp="$(mktemp)"
  # -r 发 Range 头（源回 206 + 精确字节数）；--max-filesize 兜住「源忽略 Range 直接回整包」
  # 的大文件；--max-time 兜住 body stall（此时 http_code 仍是 200、实收字节 0 ⇒ EMPTY）。
  # 注意 Range 的结束偏移是**闭区间**：要取 N 字节必须写 0-(N-1)。写成 0-N 会多取 1 字节
  # （2026-11-15 实测：-r 0-262143 ⇒ 262144 字节；-r 0-262144 ⇒ 262145 字节）。
  code="$(curl -sS -r "0-$((RANGE_BYTES - 1))" --max-filesize "${MAX_BYTES}" --max-time "${PROBE_TIMEOUT}" \
      -o "$tmp" -w '%{http_code}' "$url" 2>/dev/null || true)"
  size="$(wc -c < "$tmp" | tr -d '[:space:]')"
  rm -f "$tmp"
  [ -n "${code:-}" ] || code="000"
  printf '%s %s' "$code" "${size:-0}"
}

# ── 清单 ↔ Dockerfile 一致性自检（fail-closed）─────────────────────────
# 从 Dockerfile 取锚点并**重建**期望坐标，与条目表逐项比对。
# 任一锚点取不到 / Dockerfile 读不到 / 坐标对不上 ⇒ 判红（绝不静默放行）。
df_value() {  # $1=文件 $2=锚点正则；回 "=" 之后的值（剥外层引号），取不到回空串
  local file="$1" line v
  line="$(grep -m1 -E "$2" "$file" 2>/dev/null || true)"
  [ -n "$line" ] || return 1
  v="${line#*=}"
  v="${v%\"}"; v="${v#\"}"
  printf '%s' "$v"
}

item_field() {  # $1=条目名 $2=字段序号（1=类别 2=模式 3=快源数 4=源1 5=源2 6=源3）
  local want="$1" which="$2" name cls mode nfast u1 u2 u3
  while IFS='|' read -r name cls mode nfast u1 u2 u3; do
    [ "$name" = "$want" ] || continue
    case "$which" in
      1) printf '%s' "$cls" ;;
      2) printf '%s' "$mode" ;;
      3) printf '%s' "$nfast" ;;
      4) printf '%s' "$u1" ;;
      5) printf '%s' "$u2" ;;
      6) printf '%s' "$u3" ;;
    esac
    return 0
  done <<< "$PINNED_ITEMS"
  return 1
}

check_consistency() {
  local df="${1:-$DOCKERFILE_PATH}"
  local errs=0
  if [ ! -f "$df" ]; then
    echo "  ✗ 读不到 Dockerfile：$df"
    errs=$((errs + 1))
    return 1
  fi
  local v_primary v_fallback v_mirrors v_fastn v_maven_fast v_maven_last
  v_primary="$(df_value "$df" '^ARG SPARK_VERSION=' || true)"
  v_fallback="$(df_value "$df" '^ENV SPARK_VERSION_FALLBACK=' || true)"
  v_mirrors="$(df_value "$df" '^ARG SPARK_MIRRORS=' || true)"
  v_fastn="$(df_value "$df" '^ARG SPARK_FAST_MIRRORS=' || true)"
  v_maven_fast="$(df_value "$df" '^ENV MAVEN_MIRROR=' || true)"
  v_maven_last="$(df_value "$df" '^ENV MAVEN_BASE=' || true)"

  local anchor
  for anchor in v_primary v_fallback v_mirrors v_fastn v_maven_fast v_maven_last; do
    if [ -z "${!anchor}" ]; then
      echo "  ✗ 取不到锚点：$anchor（Dockerfile 结构变了 ⇒ 本检必须同步更新，否则 fail-closed）"
      errs=$((errs + 1))
    fi
  done
  [ "$errs" -eq 0 ] || return 1

  # ── Spark 两条条目：按 Dockerfile 的版本 ARG 与镜像根重建坐标 ──
  # ARG SPARK_MIRRORS 是**逗号分隔**的源根列表（逗号在 URL 里不可能出现 ⇒ 无歧义；
  # Dockerfile 的 ARG 默认值是字面量，带空格有被解析器截断的风险，加引号又会带进引号）。
  local -a roots=()
  local root ver item expected got
  IFS=',' read -r -a roots <<< "$v_mirrors"
  if [ "${#roots[@]}" -ne 3 ]; then
    echo "  ✗ ARG SPARK_MIRRORS 解析出 ${#roots[@]} 个源根（应为 3）：$v_mirrors"
    errs=$((errs + 1))
  fi
  # 快源/兜底源的分界也是契约：Dockerfile 用 ARG SPARK_FAST_MIRRORS 声明前 N 个是快源，
  # 清单的「快源数」必须与它相等（否则「快源被挤出即 FAIL」的判据会错位）。
  case "$v_fastn" in
    ''|*[!0-9]*)
      echo "  ✗ ARG SPARK_FAST_MIRRORS 不是正整数：'$v_fastn'"
      errs=$((errs + 1))
      ;;
    *)
      if [ "$v_fastn" -ge "${#roots[@]}" ] && [ "${#roots[@]}" -gt 0 ]; then
        echo "  ✗ ARG SPARK_FAST_MIRRORS=$v_fastn 不小于源根总数 ${#roots[@]}（必须留兜底源）"
        errs=$((errs + 1))
      fi
      ;;
  esac
  for item in "spark-bin-hadoop3(primary)" "spark-bin-hadoop3(fallback)"; do
    if [ "$item" = "spark-bin-hadoop3(primary)" ]; then ver="$v_primary"; else ver="$v_fallback"; fi
    local i=0
    for root in "${roots[@]}"; do
      i=$((i + 1))
      expected="${root}/spark-${ver}/spark-${ver}-bin-hadoop3.tgz"
      got="$(item_field "$item" "$((i + 3))" || true)"
      if [ "$got" != "$expected" ]; then
        echo "  ✗ 清单漂移：${item} 的源${i} 期望 '$expected'，实为 '$got'"
        errs=$((errs + 1))
      fi
    done
    got="$(item_field "$item" 3 || true)"
    if [ "$got" != "$v_fastn" ]; then
      echo "  ✗ 清单漂移：${item} 的快源数期望 '$v_fastn'（Dockerfile 的 SPARK_FAST_MIRRORS），实为 '$got'"
      errs=$((errs + 1))
    fi
  done

  # ── Maven 三条条目：按 Dockerfile 的源根与版本 ARG 重建坐标 ──
  local mname mpath mver mv
  while IFS='|' read -r mname mpath mver; do
    [ -n "${mname:-}" ] || continue
    mv="$(df_value "$df" "^ARG ${mver}=" || true)"
    if [ -z "$mv" ]; then
      echo "  ✗ 取不到锚点：ARG ${mver}"
      errs=$((errs + 1))
      continue
    fi
    local mpath_exp="${mpath//\$\{$mver\}/$mv}"
    expected="${v_maven_fast}${mpath_exp}"
    got="$(item_field "$mname" 4 || true)"
    if [ "$got" != "$expected" ]; then
      echo "  ✗ 清单漂移：${mname} 的快源期望 '$expected'，实为 '$got'"
      errs=$((errs + 1))
    fi
    expected="${v_maven_last}${mpath_exp}"
    got="$(item_field "$mname" 5 || true)"
    if [ "$got" != "$expected" ]; then
      echo "  ✗ 清单漂移：${mname} 的兜底源期望 '$expected'，实为 '$got'"
      errs=$((errs + 1))
    fi
    got="$(item_field "$mname" 6 || true)"
    if [ -n "$got" ]; then
      echo "  ✗ ${mname} 不该有第 3 个源（占位 URL 已废止，实为 '$got'）"
      errs=$((errs + 1))
    fi
    # Maven 的快源分界由 Dockerfile 的源顺序决定：先试 MAVEN_MIRROR（快），再试 MAVEN_BASE
    got="$(item_field "$mname" 3 || true)"
    if [ "$got" != "1" ]; then
      echo "  ✗ 清单漂移：${mname} 的快源数期望 '1'（Dockerfile 先试 MAVEN_MIRROR），实为 '$got'"
      errs=$((errs + 1))
    fi
  done <<< "$MAVEN_PATHS"

  # 任一不一致 ⇒ 判红（绝不静默放行）。错误条数只用于报告，不当退出码。
  [ "$errs" -eq 0 ] && return 0
  return 1
}

# ── 真检主流程 ────────────────────────────────────────────────────────
run_check() {
  local df="${1:-$DOCKERFILE_PATH}"
  local ok=0 fails=0 degraded=0 incon=0 red=0
  local name cls mode nfast u1 u2 u3 s1 s2 s3 verdict policy

  echo "== 钉版外链可达性预检（台账 #112）=="
  echo "-- 步骤 0：预检清单 × Dockerfile 源清单 一致性自检（fail-closed）--"
  if ! check_consistency "$df"; then
    echo "::error::预检清单与 Dockerfile 的源清单已经漂移，本检不出结论，直接判红"
    return 1
  fi
  echo "  ✓ 一致性自检通过（源根 / 版本 ARG / 源顺序 / 快源数 / 条目数 / 制品版本 逐项相符）"

  echo "-- 步骤 1：限量 Range GET 逐源探测（每次 ${RANGE_BYTES} 字节，单源墙钟上限 ${PROBE_TIMEOUT}s）--"
  while IFS='|' read -r name cls mode nfast u1 u2 u3; do
    [ -n "${name:-}" ] || continue
    s1="$(probe "$u1")"
    s2="$(probe "$u2")"
    s3=""
    [ -n "${u3:-}" ] && s3="$(probe "$u3")"
    if [ -n "$s3" ]; then
      verdict="$(classify "$mode" "$nfast" "$s1" "$s2" "$s3")"
    else
      verdict="$(classify "$mode" "$nfast" "$s1" "$s2")"
    fi
    policy="$(policy_verdict "$cls" "$verdict")"
    case "$verdict" in
      OK)
        ok=$((ok + 1))
        printf '  ✓ %-32s 交出了字节 (%s | %s | %s)\n' "$name" "$s1" "$s2" "${s3:-—}"
        ;;
      FAIL)
        fails=$((fails + 1)); red=1
        printf '::error::钉住的发布线已被快源挤出：%s（快源 %s / %s，兜底 %s）\n' "$name" "$s1" "$s2" "${s3:-—}"
        echo "  ⇒ 处置：抬到快源在架的版本，并同步 Dockerfile 的 ARG 与 CHECKSUMS.md 的 sha512/sha1 清单；"
        echo "  ⇒ 不要靠重跑解决：快源全 404 时构建会退到 archive 大文件，被下载总预算杀掉（台账 #112）。"
        ;;
      DEGRADED)
        degraded=$((degraded + 1))
        if [ "$policy" = "RED" ]; then
          red=1
          printf '::error::全部源都拿不到字节（连不上，或应答了但零字节）：%s（%s | %s | %s）\n' "$name" "$s1" "$s2" "${s3:-—}"
          echo "  ⇒ 处置：这是传输面坏了，不是构建内容坏了；构建期只会挂到 job 超时，本检据此早失败。"
        else
          printf '  ⚠ %-32s 全部源都拿不到字节（小件，构建期超时上限短，不判红）(%s | %s)\n' "$name" "$s1" "$s2"
        fi
        ;;
      *)
        incon=$((incon + 1))
        printf '  ⚠ %-32s 状态不足，不下结论 (%s | %s | %s)\n' "$name" "$s1" "$s2" "${s3:-—}"
        ;;
    esac
  done <<< "$PINNED_ITEMS"

  echo "汇总：可达 $ok / 被挤出 $fails / 取不到字节 $degraded / 状态不足 $incon"
  if [ "$red" = "1" ]; then
    echo "::error::钉住的下载源不可用，阻断 CI（早失败，省掉一次 60 分钟的误诊）"
    return 1
  fi
  echo "::notice::本检不阻断（未发现确定性不可用的坐标）"
  return 0
}

# ── 离线自测（不碰网）────────────────────────────────────────────────
run_self_test() {
  local pass=0 fail=0
  check() {
    local desc="$1" expect="$2" got="$3"
    if [ "$got" = "$expect" ]; then
      pass=$((pass + 1)); printf '  ✓ %s ⇒ %s\n' "$desc" "$got"
    else
      fail=$((fail + 1)); printf '  ✗ %s ⇒ %s（期望 %s）\n' "$desc" "$got" "$expect"
    fi
  }
  check_rc() {
    local desc="$1" expect="$2" got="$3"
    if [ "$got" = "$expect" ]; then
      pass=$((pass + 1)); printf '  ✓ %s ⇒ rc=%s\n' "$desc" "$got"
    else
      fail=$((fail + 1)); printf '  ✗ %s ⇒ rc=%s（期望 %s）\n' "$desc" "$got" "$expect"
    fi
  }

  echo "-- 判据（红侧夹具）--"
  check "快源全 404 + 兜底源有字节（2026-11-03 事故形态）⇒ FAIL" "FAIL" \
    "$(classify squeeze 2 '404 153' '404 2318' '206 262144')"
  check "快源 206 + 有字节 ⇒ OK" "OK" \
    "$(classify squeeze 2 '206 262144' '404 153' '206 262144')"
  check "快源 200 但零字节（body stall）⇒ 不是 OK" "DEGRADED" \
    "$(classify squeeze 2 '200 0' '000 0' '000 0')"
  check "快源零字节 + 兜底源有字节 ⇒ INCONCLUSIVE" "INCONCLUSIVE" \
    "$(classify squeeze 2 '200 0' '404 153' '206 262144')"
  check "快源全连不上 ⇒ DEGRADED" "DEGRADED" \
    "$(classify squeeze 2 '000 0' '000 0' '000 0')"
  check "快源连不上但兜底源有字节 ⇒ INCONCLUSIVE" "INCONCLUSIVE" \
    "$(classify squeeze 2 '000 0' '000 0' '206 262144')"
  check "快源一条超时一条 500 ⇒ INCONCLUSIVE" "INCONCLUSIVE" \
    "$(classify squeeze 2 '000 0' '500 0' '000 0')"
  check "一条快源 404、另一条 500 ⇒ INCONCLUSIVE" "INCONCLUSIVE" \
    "$(classify squeeze 2 '404 153' '500 0' '206 262144')"
  check "三条都 404 ⇒ FAIL" "FAIL" \
    "$(classify squeeze 2 '404 153' '404 153' '404 153')"
  check "plain（Apache 制品）：快源 404 + repo1 有字节 ⇒ OK" "OK" \
    "$(classify plain 1 '404 153' '206 262144')"
  check "plain：两条全 404 ⇒ FAIL" "FAIL" \
    "$(classify plain 1 '404 153' '404 153')"
  check "plain：两条全连不上 ⇒ DEGRADED" "DEGRADED" \
    "$(classify plain 1 '000 0' '000 0')"
  check "plain：快源零字节 + repo1 有字节 ⇒ OK" "OK" \
    "$(classify plain 1 '200 0' '206 262144')"

  echo "-- 判据到阻断的映射（大件才判红）--"
  check "大件 DEGRADED ⇒ RED" "RED" "$(policy_verdict large DEGRADED)"
  check "小件 DEGRADED ⇒ PASS" "PASS" "$(policy_verdict small DEGRADED)"
  check "大件 FAIL ⇒ RED" "RED" "$(policy_verdict large FAIL)"
  check "小件 FAIL ⇒ RED" "RED" "$(policy_verdict small FAIL)"
  check "INCONCLUSIVE ⇒ PASS" "PASS" "$(policy_verdict large INCONCLUSIVE)"

  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/pinned-downloads-selftest.XXXXXX")"
  trap 'rm -rf "$tmp"' RETURN

  cat >"$tmp/good.Dockerfile" <<'FIXTURE'
ARG SPARK_VERSION=4.2.1
ENV SPARK_VERSION_FALLBACK=3.5.9
ARG SPARK_MIRRORS=https://mirrors.tuna.tsinghua.edu.cn/apache/spark,https://mirrors.aliyun.com/apache/spark,https://archive.apache.org/dist/spark
ARG SPARK_FAST_MIRRORS=2
ENV MAVEN_MIRROR=https://maven.aliyun.com/repository/central
ENV MAVEN_BASE=https://repo1.maven.org/maven2
ARG HADOOP_AWS_VERSION=3.5.0
ARG AWS_SDK_V2_BUNDLE_VERSION=2.35.4
ARG ANALYTICS_ACCELERATOR_VERSION=1.3.1
FIXTURE

  echo "-- 清单 ↔ Dockerfile 一致性自检（漂移必须被抓到）--"
  local rc=0
  check_consistency "$tmp/good.Dockerfile" >/dev/null 2>&1; rc=$?
  check_rc "一致的 Dockerfile ⇒ 通过" 0 "$rc"
  check_consistency "$tmp/does-not-exist.Dockerfile" >/dev/null 2>&1; rc=$?
  check_rc "Dockerfile 读不到 ⇒ fail-closed 判红" 1 "$rc"

  sed 's/^ARG SPARK_VERSION=4.2.1$/ARG SPARK_VERSION=4.2.0/' "$tmp/good.Dockerfile" >"$tmp/drift-version.Dockerfile"
  check_consistency "$tmp/drift-version.Dockerfile" >/dev/null 2>&1; rc=$?
  check_rc "Spark 版本漂移 ⇒ 判红" 1 "$rc"

  sed 's#^ARG SPARK_MIRRORS=.*#ARG SPARK_MIRRORS=https://mirrors.tuna.tsinghua.edu.cn/apache/spark,https://mirror.example/apache/spark,https://archive.apache.org/dist/spark#' \
    "$tmp/good.Dockerfile" >"$tmp/drift-root.Dockerfile"
  check_consistency "$tmp/drift-root.Dockerfile" >/dev/null 2>&1; rc=$?
  check_rc "Spark 源根漂移 ⇒ 判红" 1 "$rc"

  sed 's#^ARG SPARK_MIRRORS=.*#ARG SPARK_MIRRORS=https://mirrors.aliyun.com/apache/spark,https://mirrors.tuna.tsinghua.edu.cn/apache/spark,https://archive.apache.org/dist/spark#' \
    "$tmp/good.Dockerfile" >"$tmp/drift-order.Dockerfile"
  check_consistency "$tmp/drift-order.Dockerfile" >/dev/null 2>&1; rc=$?
  check_rc "Spark 源顺序漂移 ⇒ 判红" 1 "$rc"

  sed 's/^ARG SPARK_FAST_MIRRORS=2$/ARG SPARK_FAST_MIRRORS=3/' "$tmp/good.Dockerfile" >"$tmp/drift-fastn.Dockerfile"
  check_consistency "$tmp/drift-fastn.Dockerfile" >/dev/null 2>&1; rc=$?
  check_rc "Spark 快源分界漂移 ⇒ 判红" 1 "$rc"

  sed 's#^ENV MAVEN_BASE=.*#ENV MAVEN_BASE=https://repo.example/maven2#' "$tmp/good.Dockerfile" >"$tmp/drift-maven.Dockerfile"
  check_consistency "$tmp/drift-maven.Dockerfile" >/dev/null 2>&1; rc=$?
  check_rc "Maven 兜底源漂移 ⇒ 判红" 1 "$rc"

  sed 's/^ARG HADOOP_AWS_VERSION=3.5.0$/ARG HADOOP_AWS_VERSION=3.4.0/' "$tmp/good.Dockerfile" >"$tmp/drift-jarver.Dockerfile"
  check_consistency "$tmp/drift-jarver.Dockerfile" >/dev/null 2>&1; rc=$?
  check_rc "Maven 制品版本漂移 ⇒ 判红" 1 "$rc"

  sed '/^ENV SPARK_VERSION_FALLBACK=/d' "$tmp/good.Dockerfile" >"$tmp/drift-missing.Dockerfile"
  check_consistency "$tmp/drift-missing.Dockerfile" >/dev/null 2>&1; rc=$?
  check_rc "锚点缺失 ⇒ fail-closed 判红" 1 "$rc"

  echo "-- 端到端（桩探测，不碰网）--"
  printf '#!/usr/bin/env bash\necho "206 262144"\n' >"$tmp/stub_ok.sh"
  printf '#!/usr/bin/env bash\necho "404 153"\n' >"$tmp/stub_404.sh"
  printf '#!/usr/bin/env bash\necho "200 0"\n' >"$tmp/stub_zero.sh"
  chmod +x "$tmp"/stub_*.sh

  ( export PINNED_DOWNLOAD_PROBE_CMD="$tmp/stub_ok.sh"; run_check "$tmp/good.Dockerfile" ) >/dev/null 2>&1; rc=$?
  check_rc "全部源交出字节 ⇒ 不阻断" 0 "$rc"
  ( export PINNED_DOWNLOAD_PROBE_CMD="$tmp/stub_404.sh"; run_check "$tmp/good.Dockerfile" ) >/dev/null 2>&1; rc=$?
  check_rc "快源全 404 ⇒ 判红" 1 "$rc"
  ( export PINNED_DOWNLOAD_PROBE_CMD="$tmp/stub_zero.sh"; run_check "$tmp/good.Dockerfile" ) >/dev/null 2>&1; rc=$?
  check_rc "大件全部源零字节 ⇒ 判红" 1 "$rc"

  if [ -f "$DOCKERFILE_PATH" ]; then
    check_consistency "$DOCKERFILE_PATH" >/dev/null 2>&1; rc=$?
    check_rc "真实 Dockerfile 与清单一致" 0 "$rc"
  else
    printf '  – 真实 Dockerfile 不在本次运行环境里，跳过该项（真检时 fail-closed）\n'
  fi

  echo "自测结果：通过 $pass 项 / 失败 $fail 项"
  [ "$fail" -eq 0 ] || return 1
  return 0
}

if [ "$SELF_TEST" = "1" ]; then
  echo "== check-pinned-downloads 自测（离线，不碰网）=="
  run_self_test
  exit $?
fi

run_check
exit $?
