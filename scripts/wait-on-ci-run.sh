#!/usr/bin/env bash
# 等待 ci.yml（或指定 workflow）在某个 commit 上的 run 出结论。
#
# 为什么有这个脚本：这段逻辑原先在 build.yml 与 release.yml 里各抄一份，
# 两份只差一个词（"Build 中止" / "Release 中止"）。2026-10-17 main 的 run
# `39048221968` 里，它把"runner 排队 6 小时没轮到"判成了 CI 红（60 分钟写死），
# 台账 #75。收进来一处实现，是为了让三条分支能被本机自测跑，而不是只能等 CI。
#
# 用法：
#   scripts/wait-on-ci-run.sh [--sha SHA] [--repo owner/repo] [--workflow ci.yml]
#                             [--subject Build|Release] [--timeout-minutes N]
#                             [--poll-interval S] [--self-test]
# 退出码：0 CI 已过；1 CI 判失败（有 run 且全部结束但无一 success）；
#         2 **等待超时**（含"根本没看到 run"）⇒ infra/排队问题，不是代码红。
#         3 **判据无法建立**（缺 jq、响应不可解析、runs 取满一页说明集合不完整）
#           ⇒ 立刻红，不允许"读不出数"被读成"只是排队"或直接空等到超时。
#         64 未知命令行参数。
#         非零都不放行：拿不到 CI 结论时构建/发布一律中止，只是文案要说清拦下的原因。
# 自测注入点：WAIT_ON_CI_STUB_CMD —— 由它代替 `gh api` 打印 workflow-runs 的 JSON，
#         这样每条分支能在本机真跑（含"取满一页"这种要造 100 条才触发的分支）。
set -uo pipefail

SHA="${GITHUB_SHA:-}"
REPO="${REPO:-${GITHUB_REPOSITORY:-}}"
WORKFLOW="ci.yml"
SUBJECT="CI"
TIMEOUT_MIN="${CI_GATE_WAIT_MINUTES:-360}"   # 默认 6 小时：本轮实测排队就在这个量级
POLL=30
PAGE_SIZE=100
SELF_TEST=0
STUB="${WAIT_ON_CI_STUB_CMD:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    --workflow) WORKFLOW="$2"; shift 2 ;;
    --subject) SUBJECT="$2"; shift 2 ;;
    --timeout-minutes) TIMEOUT_MIN="$2"; shift 2 ;;
    --poll-interval) POLL="$2"; shift 2 ;;
    --self-test) SELF_TEST=1; shift ;;
    *) echo "未知参数：$1" >&2; exit 64 ;;
  esac
done

log() { echo "[wait-on-ci-run] $*" >&2; }

# 取该 workflow 在本 sha 上的 runs（stdout = JSON）
fetch_runs() {
  if [ -n "$STUB" ]; then
    $STUB "$WORKFLOW"
  else
    gh api "repos/$REPO/actions/workflows/$WORKFLOW/runs?head_sha=$SHA&per_page=$PAGE_SIZE" 2>/dev/null || echo '{}'
  fi
}

counts() {   # 打印 "total success pending page"（page = 过滤 head_sha 之前的返回条数）
  echo "$1" | jq -r --arg sha "$SHA" '
    (.workflow_runs // []) as $raw
    | ($raw | map(select(.head_sha == $sha))) as $r
    | [ ($r | length),
        ($r | map(select(.conclusion == "success")) | length),
        ($r | map(select(.status != "completed")) | length),
        ($raw | length) ] | @tsv'
}

wait_ci() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "::error::运行环境缺 jq ⇒ 无法解析 workflow-runs 响应；不能因为读不出数就空等到超时" >&2
    return 3
  fi
  if [ -z "$SHA" ] || [ -z "$REPO" ]; then
    echo "::error::缺 SHA/REPO（SHA='$SHA' REPO='$REPO'）⇒ 判据无法建立，按未通过处理" >&2
    return 1
  fi
  local deadline now runs t s p n snapshot
  deadline=$(( $(date +%s) + TIMEOUT_MIN * 60 ))
  log "等待 $WORKFLOW 在 commit $SHA 上出结论（上限 $TIMEOUT_MIN 分钟，轮询 ${POLL}s，主体=$SUBJECT）"
  while :; do
    runs="$(fetch_runs)"
    read -r t s p n <<<"$(counts "$runs")"
    if ! printf '%s %s %s %s' "$t" "$s" "$p" "$n" | grep -qE '^[0-9]+ [0-9]+ [0-9]+ [0-9]+$'; then
      echo "::error::解析 $WORKFLOW 的 runs 响应失败（得到 total='$t' success='$s' pending='$p' page='$n'）⇒ 判失败而不是继续等" >&2
      return 3
    fi
    # 单页取满 ⇒ 后面可能还有 run 没读到。此时任何"结论"都建立在残缺集合上，
    # 尤其不能报"全部结束但无一 success"（那会把分页问题说成代码红）。
    if [ "$n" -ge "$PAGE_SIZE" ]; then
      echo "::error::$WORKFLOW 的 runs 响应取满一页（page=$n，per_page=$PAGE_SIZE）⇒ 判据集合可能不完整，按解析失败处理而不是下结论" >&2
      return 3
    fi
    echo "$WORKFLOW runs on ${SHA:0:12}: total=$t success=$s pending=$p page=$n"
    if [ "${s:-0}" -gt 0 ]; then
      echo "::notice::$WORKFLOW 已通过（success=$s），允许 $SUBJECT 继续"
      return 0
    fi
    if [ "${t:-0}" -gt 0 ] && [ "${p:-0}" -eq 0 ]; then
      snapshot="$(echo "$runs" | jq -r --arg sha "$SHA" \
        '[.workflow_runs[]? | select(.head_sha == $sha) | .status + "/" + (.conclusion // "-")] | join(", ")')"
      echo "::error::$WORKFLOW 在 $SHA 上的 run 已全部结束但无一 success ⇒ 判 CI 失败，$SUBJECT 中止（runs: $snapshot）"
      return 1
    fi
    now="$(date +%s)"
    if [ "$now" -ge "$deadline" ]; then
      # 关键区分：这一支不是"CI 说不过"，是"没拿到 CI 的结论"。
      echo "::warning::等待 $WORKFLOW 超时（$TIMEOUT_MIN 分钟，total=$t pending=$p）——这是 runner 排队/infra，不是代码失败"
      echo "::error::等待 $WORKFLOW 结论超时（$TIMEOUT_MIN 分钟，commit $SHA，total=$t pending=$p）⇒ $SUBJECT 不放行"
      return 2
    fi
    sleep "$POLL"
  done
}

self_test() {
  # 用一次性夹具目录 + `cat` 当 stub（不用带引号的内联命令：shell 展开后不会再解析引号）
  local ok=1 tmp rc out
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/wait-ci-selftest.XXXXXX")"
  SHA='deadbeefdeadbeefdeadbeefdeadbeefdeadbeef'
  REPO='stub/repo'
  WORKFLOW='ci.yml'
  POLL=0
  TIMEOUT_MIN=1
  STUB="cat $tmp/fixture.json"

  check() {   # $1=标签 $2=期望 rc $3=期望文案片段
    out="$(WAIT_ON_CI_STUB_CMD="$STUB" bash "$0" --sha "$SHA" --repo "$REPO"              --workflow ci.yml --subject Build --timeout-minutes "$TIMEOUT_MIN" --poll-interval 0 2>&1)"
    rc=$?
    if [ "$rc" = "$2" ] && printf '%s' "$out" | grep -q "$3"; then
      echo "  [PASS] $1（rc=$rc）"
    else
      echo "  [FAIL] $1 期望 rc=$2 且文案含「$3」，实得 rc=$rc"
      printf '%s
' "$out" | sed 's/^/        /' | tail -4
      ok=0
    fi
  }

  # ① CI 已过 ⇒ 0
  printf '%s' "{\"workflow_runs\":[{\"head_sha\":\"$SHA\",\"status\":\"completed\",\"conclusion\":\"success\"}]}" > "$tmp/fixture.json"
  check 'CI success ⇒ 放行并退 0' 0 '已通过'
  # ② 全部结束但无一 success ⇒ 1，且文案是"判 CI 失败"
  printf '%s' "{\"workflow_runs\":[{\"head_sha\":\"$SHA\",\"status\":\"completed\",\"conclusion\":\"failure\"}]}" > "$tmp/fixture.json"
  check 'CI 判失败 ⇒ 退 1 且点名无一 success' 1 '无一 success'
  # ③ 还在排队 ⇒ 到点走超时分支，退 2 且明说是 infra，不说"CI 失败"
  printf '%s' "{\"workflow_runs\":[{\"head_sha\":\"$SHA\",\"status\":\"queued\",\"conclusion\":null}]}" > "$tmp/fixture.json"
  TIMEOUT_MIN=0
  check '排队拿不到结论 ⇒ 退 2 并标 infra（不是代码红）' 2 'infra'
  # ④ 根本没看到 run（懒建 / 参数被忽略）⇒ 同样不许放行
  printf '%s' '{"workflow_runs":[]}' > "$tmp/fixture.json"
  check '空判据集合不放行 ⇒ 退 2' 2 'total=0'
  # ⑤ 响应不可解析 ⇒ 退 3（不能退 2 被误读成"只是排队"，更不能退 0）
  printf '%s' 'not json at all' > "$tmp/fixture.json"
  check '响应不可解析 ⇒ 退 3 而不是空等' 3 '解析'
  # ⑥ 别的 commit 的 run 不能替本 commit 结论（head_sha 复核）
  printf '%s' "{\"workflow_runs\":[{\"head_sha\":\"OTHER\",\"status\":\"completed\",\"conclusion\":\"success\"}]}" > "$tmp/fixture.json"
  TIMEOUT_MIN=1
  check '别的 commit 的 success 不算数 ⇒ 不放行' 2 'total=0'
  # ⑦ runs 取满一页 ⇒ 判据集合可能不完整，不许据此下"CI 失败"结论（退 3）
  { printf '{"workflow_runs":['
    for i in $(seq 1 "$PAGE_SIZE"); do
      [ "$i" -gt 1 ] && printf ','
      printf '{"head_sha":"%s","status":"completed","conclusion":"failure"}' "$SHA"
    done
    printf ']}'
  } > "$tmp/fixture.json"
  check 'runs 取满一页 ⇒ 退 3（不把分页说成 CI 失败）' 3 '取满一页'
  # ⑧ 配对验证：少一条就没触发 ⇒ 证明 ⑦ 不是"恒退 3"的假通过
  { printf '{"workflow_runs":['
    for i in $(seq 1 "$((PAGE_SIZE - 1))"); do
      [ "$i" -gt 1 ] && printf ','
      printf '{"head_sha":"%s","status":"completed","conclusion":"failure"}' "$SHA"
    done
    printf ']}'
  } > "$tmp/fixture.json"
  check '一页差一条 ⇒ 照常下结论退 1，不是退 3' 1 '无一 success'
  # ⑨ 缺 SHA/REPO 不许静默成功
  out="$(bash "$0" --sha '' --repo '' --timeout-minutes 1 --poll-interval 0 2>&1)"; rc=$?
  if [ "$rc" != 0 ] && printf '%s' "$out" | grep -q '判据无法建立'; then
    echo "  [PASS] 缺 SHA/REPO 判红而不是放行（rc=$rc）"
  else
    echo "  [FAIL] 缺 SHA/REPO 却 rc=$rc"; ok=0
  fi
  # ⑩ 超时与 CI 失败两条文案必须不同（红起来要能一眼归因）
  printf '%s' "{\"workflow_runs\":[{\"head_sha\":\"$SHA\",\"status\":\"completed\",\"conclusion\":\"failure\"}]}" > "$tmp/fixture.json"
  m1="$(WAIT_ON_CI_STUB_CMD="$STUB" bash "$0" --sha "$SHA" --repo "$REPO" --timeout-minutes 1 --poll-interval 0 2>&1 | grep -c '无一 success')"
  printf '%s' "{\"workflow_runs\":[{\"head_sha\":\"$SHA\",\"status\":\"queued\",\"conclusion\":null}]}" > "$tmp/fixture.json"
  m2="$(WAIT_ON_CI_STUB_CMD="$STUB" bash "$0" --sha "$SHA" --repo "$REPO" --timeout-minutes 0 --poll-interval 0 2>&1 | grep -c 'infra')"
  if [ "$m1" -ge 1 ] && [ "$m2" -ge 1 ]; then
    echo "  [PASS] CI 失败与排队超时各有专属文案"
  else
    echo "  [FAIL] 文案未区分（CI失败=$m1 超时=$m2）"; ok=0
  fi
  rm -rf "$tmp"
  [ "$ok" = 1 ] && echo "SELFTEST=PASS  （夹具目录已删）" || echo "SELFTEST=FAIL"
  return $((ok == 1 ? 0 : 1))
}

if [ "$SELF_TEST" = 1 ]; then self_test; exit $?; fi
wait_ci; exit $?
