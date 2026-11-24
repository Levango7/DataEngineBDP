#!/usr/bin/env bash
# 判定「本次 PR 的改动是否触及镜像构建面」——CI 触发面收敛的单一事实源。
#
# 为什么需要它：
#   .github/workflows/ci.yml 的 `docker-build` 作业（Docker Build Verify）会遍历
#   platform/ 下全部含 Dockerfile 的组件逐个 docker build（实测 44 个，单波 47m34s）。
#   但 ci.yml 的 `on:` **没有 paths 过滤** ⇒ 连只改 2 行 docs 的 PR 也会全量重建 44 个镜像。
#   实测 PR #807（仅改 docs/KNOWN-FAILURES.md + docs/messageboard/board.md 两行）
#   仍触发 44 个 Dockerfile 全量重建 ⇒ CI 成本的主因与被验证的内容无关。
#
# 为什么不做 workflow 级 paths 过滤：
#   分支保护把 9 条 required check 写死在名单里（strict=true）。若在 workflow 级用 paths
#   过滤，那些不该触发的作业**根本不会产生 check-run** ⇒ required check 永远处于
#   "Expected — Waiting for status to be reported" ⇒ PR 永久阻塞。
#   所以收敛必须发生在**作业内部**：作业照常跑、照常出结论，只是把「与镜像无关」的
#   PR 判为「本波不做镜像重建」，并在日志里明确写出跳过原因与改动文件清单。
#
# 判定口径（哪些改动会改变镜像构建结果）：
#   · platform/**            —— Dockerfile 本身、构建上下文（COPY 的源文件）、entrypoint.sh
#   · pom.xml（仓库根）      —— 统一父 POM。**实测**：21 个 Dockerfile 用「仓库根」上下文，
#                               其 builder 阶段都有 `COPY pom.xml ./pom.xml`（例：
#                               platform/tag-engine/Dockerfile:13）⇒ 改根 pom.xml 会改变
#                               这 21 个镜像的构建结果，故必须计入。
#   · scripts/build-retry.sh —— 构建重试与挂起预算逻辑（外层包裹 docker build）
#   · scripts/pick-base-registry.sh —— 基座镜像源选择（--build-arg BASE_REGISTRY）
#   · scripts/check-pinned-downloads.sh —— 钉版外链预检（决定构建前是否早失败）
#   · scripts/check-docker-build-relevance.sh —— 本脚本自身（收敛判据的治理面，保守计入）
#   · .github/workflows/ci.yml —— 触发面本身（改它必须重新验证）
#   其余（docs/**、frontend/**、tests/**、design/**、deploy/** 等）⇒ 与镜像构建结果无关。
#
# 口径的实测依据（2026-11-15，44 个 Dockerfile 全量扫描）：
#   · 上下文分布：21 个「仓库根」/ 23 个「模块目录」（判据逐字复刻 ci.yml 的
#     `grep -qE '^COPY[[:space:]]+platform/'`，注意它作用于**原始行**且不允许前导空白）；
#   · 214 条 COPY/ADD 指令里，40 条带 `--from=`（构建阶段产物，不是上下文源）；
#   · 按实际上下文解析后，落在 platform/ 之外的上下文源**只有仓库根的 pom.xml**；
#   · 仓内**无任何 .dockerignore** ⇒ 不存在排除规则改写上下文的情形；
#   · 「仓库根上下文 且 COPY . / COPY . .」的 Dockerfile：**0 个**
#     （否则仓库内任意文件都会进镜像，本判据不成立）。
#
# 取数失败一律 fail-closed（判为「要重建」）：
#   判定错误的代价不对称——误判「不相关」会让镜像构建**根本不再执行**（静默失去验证），
#   误判「相关」只是多花一次构建时间。故任何取数异常（含改动文件清单为空）都倒向「重建」。
#
# 用法：
#   bash scripts/check-docker-build-relevance.sh --self-test
#       离线自测（喂改动文件清单夹具，不碰网络不碰 git）
#   bash scripts/check-docker-build-relevance.sh --files <file>
#       判定给定清单（每行一个路径）
#   bash scripts/check-docker-build-relevance.sh --api
#       用 GitHub REST API 取当前 PR 的改动文件
#   bash scripts/check-docker-build-relevance.sh
#       默认用 API（等价于 --api）
#
# 输出：
#   stdout  —— 人读的诊断（改动文件清单 + 判定 + 理由）
#   $GITHUB_OUTPUT —— 若已设置则写 `docker_relevant=true|false`
#                    （true = 必须重建，false = 本波可跳过重建）
#                    与 `spark_drill_relevant=true|false`
#                    （Spark 主版本失效回退演练是否要跑；判据面**独立于**上面的构建面）
#
# 2026-11-24（台账 #112 续）：新增第二个输出 spark_drill_relevant。
# 为什么要有第二个：回退演练（scripts/test-spark-fallback-drill.sh）只关心 **Spark 面**，
#   而「镜像构建面」的口径是 `^platform/` ⇒ 改任何一个别的 Dockerfile 都会把它算进去。
#   两个口径混用会让演练在无关 PR 上白跑几分钟；反之只认 Spark 面又可能漏掉
#   「改了演练脚本自身却没跑演练」的情形。故并列给出两个判定，各取所需。
#   演练面口径（改动下列任一文件 ⇒ 本波要跑回退演练）：
#     platform/batch-pipeline/docker/spark-cluster/Dockerfile  —— 回退逻辑本体
#     platform/batch-pipeline/docker/spark-cluster/CHECKSUMS.md —— 钉扎清单（版本/校验和）
#     scripts/test-spark-fallback-drill.sh                        —— 演练脚本自身
#     scripts/test-spark-download-budget.sh                       —— 回退逻辑的桩测
#   取数失败仍一律 fail-closed（两个判定都倒向「要跑」）。
set -u

SELF_TEST=0
FILES_FILE=""
USE_API=0

while [ $# -gt 0 ]; do
  case "$1" in
    --self-test) SELF_TEST=1 ;;
    --files) FILES_FILE="${2:-}"; USE_API=0; shift ;;
    --api) USE_API=1 ;;
    -h|--help) sed -n '2,62p' "$0"; exit 0 ;;
    *) echo "!! 未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

# ── 判定核心（纯函数式：读清单文件，吐 RELEVANT/IRRELEVANT）────────────────────
# 路径判据写在这里，便于 --self-test 与真跑共用同一份逻辑（避免两处漂移）。
RELEVANT_PATTERNS='^platform/|^pom\.xml$|^scripts/build-retry\.sh$|^scripts/pick-base-registry\.sh$|^scripts/check-pinned-downloads\.sh$|^scripts/check-docker-build-relevance\.sh$|^\.github/workflows/ci\.yml$'

# Spark 回退演练面（2026-11-24 新增，与上面的构建面**并列而非包含**）。
# 口径窄且具体：只有改到回退逻辑本体、钉扎清单、演练脚本自身、回退桩测这四类文件，
# 本波才需要重跑「强制主版本失效 ⇒ 回退到 3.5.10」的端到端演练。
SPARK_DRILL_PATTERNS='^platform/batch-pipeline/docker/spark-cluster/Dockerfile$|^platform/batch-pipeline/docker/spark-cluster/CHECKSUMS\.md$|^scripts/test-spark-fallback-drill\.sh$|^scripts/test-spark-download-budget\.sh$'

# 判定「本波是否触及 Spark 回退演练面」——与 judge() 同构（同样 fail-closed）。
judge_spark() {  # judge_spark <路径清单文件>
  local list="$1"
  if [ ! -f "${list}" ]; then
    echo "RELEVANT"
    echo "改动文件清单不存在（${list}），无法判定 ⇒ fail-closed 判为需要演练"
    return 0
  fi
  local hit=""
  local line
  local count=0
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    count=$((count + 1))
    if printf '%s' "${line}" | grep -qE "${SPARK_DRILL_PATTERNS}"; then
      hit="${line}"
      break
    fi
  done < "${list}"
  if [ -n "${hit}" ]; then
    echo "RELEVANT"
    echo "命中 Spark 回退演练面文件：${hit}"
  elif [ "${count}" -eq 0 ]; then
    echo "RELEVANT"
    echo "改动文件清单为空，无法判定 ⇒ fail-closed 判为需要演练"
  else
    echo "IRRELEVANT"
    echo "已扫描 ${count} 个改动文件，均不触及 Spark 回退演练面"
  fi
}

judge() {  # judge <路径清单文件>；结果写 stdout
  local list="$1"
  local hit=""
  local line
  local count=0
  if [ ! -f "${list}" ]; then
    # 清单文件缺失：与「空清单」同属「判不了」⇒ 同样 fail-closed，
    # 但**理由必须写出来**，否则夹具路径写错会被误读成「判定正确」。
    echo "RELEVANT"
    echo "改动文件清单不存在（${list}），无法判定 ⇒ fail-closed 判为需要重建"
    return 0
  fi
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    count=$((count + 1))
    if printf '%s' "${line}" | grep -qE "${RELEVANT_PATTERNS}"; then
      hit="${line}"
      break
    fi
  done < "${list}"
  if [ -n "${hit}" ]; then
    echo "RELEVANT"
    echo "命中构建面文件：${hit}"
  elif [ "${count}" -eq 0 ]; then
    # fail-closed：清单为空 ⇒ 无法判定 ⇒ 倒向「要重建」。
    echo "RELEVANT"
    echo "改动文件清单为空，无法判定 ⇒ fail-closed 判为需要重建"
  else
    echo "IRRELEVANT"
    echo "已扫描 ${count} 个改动文件，均不触及镜像构建面"
  fi
}

# 把判定写进 $GITHUB_OUTPUT（供 GitHub Actions 的 step output 消费）。
# 两个输出并列：docker_relevant（镜像构建面）与 spark_drill_relevant（Spark 回退演练面）。
# 第二个参数缺省时与第一个相同（保持旧调用点语义不变）。
emit_verdict() {  # emit_verdict <true|false> [spark true|false]
  local v="$1"
  local sv="${2:-$1}"
  echo "DOCKER_RELEVANT=${v}"
  echo "SPARK_DRILL_RELEVANT=${sv}"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "docker_relevant=${v}" >> "${GITHUB_OUTPUT}"
    echo "spark_drill_relevant=${sv}" >> "${GITHUB_OUTPUT}"
  fi
}

# ── API 取数（fail-closed）──────────────────────────────────────────────────
resolve_pr_number() {
  local pr="${PR_NUMBER:-}"
  if [ -z "${pr}" ] && [ -n "${GITHUB_REF:-}" ]; then
    # GitHub Actions 的 PR 触发：refs/pull/<n>/merge
    case "${GITHUB_REF}" in
      refs/pull/*/merge|refs/pull/*/head) pr="${GITHUB_REF#refs/pull/}"; pr="${pr%%/*}" ;;
    esac
  fi
  printf '%s' "${pr}"
}

fetch_via_gh() {  # fetch_via_gh <out>
  local repo="$1" pr="$2" out="$3"
  command -v gh >/dev/null 2>&1 || return 2
  gh api --paginate "repos/${repo}/pulls/${pr}/files" --jq '.[].filename' > "${out}.tmp" 2>/dev/null || return 2
  [ -s "${out}.tmp" ] || return 2
  mv "${out}.tmp" "${out}"
  return 0
}

fetch_via_curl() {  # fetch_via_curl <out>
  local repo="$1" pr="$2" out="$3"
  local token="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
  command -v python3 >/dev/null 2>&1 || return 2
  python3 -c '
import json, os, sys, urllib.request
repo, pr = sys.argv[1], sys.argv[2]
token = sys.argv[3]
names = []
for page in range(1, 31):
    url = "https://api.github.com/repos/%s/pulls/%s/files?per_page=100&page=%d" % (repo, pr, page)
    headers = {"Accept": "application/vnd.github+json",
               "User-Agent": "DataEngineBDP-ci-relevance-check"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.load(resp)
    if not isinstance(data, list) or not data:
        break
    names.extend(f.get("filename", "") for f in data)
    if len(data) < 100:
        break
if not names:
    sys.exit(3)
sys.stdout.write("\n".join(names) + "\n")
' "${repo}" "${pr}" "${token}" > "${out}.tmp" 2>/dev/null || return 2
  [ -s "${out}.tmp" ] || return 2
  mv "${out}.tmp" "${out}"
  return 0
}

fetch_via_api() {  # fetch_via_api <out>
  local out="$1"
  local repo="${GITHUB_REPOSITORY:-}"
  local pr
  pr="$(resolve_pr_number)"
  if [ -z "${pr}" ] || [ -z "${repo}" ]; then
    echo "!! 取 PR 改动文件失败：缺少 PR 编号 / GITHUB_REPOSITORY" >&2
    return 2
  fi
  if fetch_via_gh "${repo}" "${pr}" "${out}"; then
    return 0
  fi
  if fetch_via_curl "${repo}" "${pr}" "${out}"; then
    return 0
  fi
  echo "!! 取 PR 改动文件失败：gh API 与 curl 回退路径都未取到非空清单" >&2
  rm -f "${out}.tmp"
  return 2
}

# ── 离线自测 ───────────────────────────────────────────────────────────────
run_self_test() {
  local pass=0 fail=0
  check() {  # check <描述> <期望> <实际>
    if [ "$2" = "$3" ]; then
      pass=$((pass + 1)); printf '  ✓ %s ⇒ %s\n' "$1" "$3"
    else
      fail=$((fail + 1)); printf '  ✗ %s ⇒ %s（期望 %s）\n' "$1" "$3" "$2"
    fi
  }

  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/docker-build-relevance.XXXXXX")"
  trap 'rm -rf "${tmp}"' RETURN

  printf '%s\n' 'docs/KNOWN-FAILURES.md' 'docs/messageboard/board.md' > "${tmp}/docs.txt"
  printf '%s\n' 'frontend/src/main.ts' 'tests/unit/foo.test.ts' > "${tmp}/frontend.txt"
  printf '%s\n' 'platform/catalog/Dockerfile' > "${tmp}/df.txt"
  printf '%s\n' 'platform/catalog/src/main/java/A.java' > "${tmp}/src.txt"
  printf '%s\n' 'scripts/build-retry.sh' > "${tmp}/retry.txt"
  printf '%s\n' 'scripts/pick-base-registry.sh' > "${tmp}/registry.txt"
  printf '%s\n' 'scripts/check-pinned-downloads.sh' > "${tmp}/pinned.txt"
  printf '%s\n' 'scripts/check-docker-build-relevance.sh' > "${tmp}/self.txt"
  printf '%s\n' '.github/workflows/ci.yml' > "${tmp}/ciyml.txt"
  printf '%s\n' 'pom.xml' > "${tmp}/rootpom.txt"
  printf '%s\n' 'platform/pom.xml' > "${tmp}/subpom.txt"
  printf '%s\n' 'scripts/other-unrelated.sh' > "${tmp}/other.txt"
  : > "${tmp}/empty.txt"

  echo "-- 判定：与镜像无关 ⇒ 跳过重建 --"
  check "纯 docs 改动" "IRRELEVANT" "$(judge "${tmp}/docs.txt" | head -1)"
  check "前端源码改动" "IRRELEVANT" "$(judge "${tmp}/frontend.txt" | head -1)"
  check "无关脚本改动" "IRRELEVANT" "$(judge "${tmp}/other.txt" | head -1)"

  echo "-- 判定：触及构建面 ⇒ 必须重建 --"
  check "Dockerfile 改动" "RELEVANT" "$(judge "${tmp}/df.txt" | head -1)"
  check "构建上下文源码改动" "RELEVANT" "$(judge "${tmp}/src.txt" | head -1)"
  check "构建重试脚本改动" "RELEVANT" "$(judge "${tmp}/retry.txt" | head -1)"
  check "基座源选择脚本改动" "RELEVANT" "$(judge "${tmp}/registry.txt" | head -1)"
  check "钉版预检脚本改动" "RELEVANT" "$(judge "${tmp}/pinned.txt" | head -1)"
  check "收敛判据脚本自身改动" "RELEVANT" "$(judge "${tmp}/self.txt" | head -1)"
  check "ci.yml 本身改动" "RELEVANT" "$(judge "${tmp}/ciyml.txt" | head -1)"
  check "仓库根父 POM 改动" "RELEVANT" "$(judge "${tmp}/rootpom.txt" | head -1)"
  check "platform/ 下的 pom.xml（属 platform/ 面）" "RELEVANT" "$(judge "${tmp}/subpom.txt" | head -1)"

  echo "-- 判定：相似但不等价的路径不得误判为相关 --"
  printf '%s\n' 'platformer/foo.txt' > "${tmp}/prefix.txt"
  printf '%s\n' 'scripts/build-retry.sh.bak' > "${tmp}/suffix.txt"
  printf '%s\n' 'docs/platform/notes.md' > "${tmp}/nested.txt"
  printf '%s\n' 'docs/pom.xml' > "${tmp}/docspom.txt"
  printf '%s\n' '.github/workflows/release.yml' > "${tmp}/otherwf.txt"
  check "platform 前缀相似但不在 platform/ 下" "IRRELEVANT" "$(judge "${tmp}/prefix.txt" | head -1)"
  check "build-retry.sh.bak 不等于 build-retry.sh" "IRRELEVANT" "$(judge "${tmp}/suffix.txt" | head -1)"
  check "docs/platform/ 不是 platform/" "IRRELEVANT" "$(judge "${tmp}/nested.txt" | head -1)"
  check "docs/pom.xml 不是仓库根 pom.xml" "IRRELEVANT" "$(judge "${tmp}/docspom.txt" | head -1)"
  check "其它 workflow 文件不影响镜像构建" "IRRELEVANT" "$(judge "${tmp}/otherwf.txt" | head -1)"

  echo "-- fail-closed：取数失败 / 清单为空必须倒向重建 --"
  check "空清单判为 RELEVANT（不是 IRRELEVANT）" "RELEVANT" "$(judge "${tmp}/empty.txt" | head -1)"

  echo "-- Spark 回退演练面判定（与构建面并列的第二判定）--"
  printf '%s\n' 'platform/batch-pipeline/docker/spark-cluster/Dockerfile' > "${tmp}/spark_df.txt"
  printf '%s\n' 'platform/batch-pipeline/docker/spark-cluster/CHECKSUMS.md' > "${tmp}/spark_sums.txt"
  printf '%s\n' 'scripts/test-spark-fallback-drill.sh' > "${tmp}/spark_drill.txt"
  printf '%s\n' 'scripts/test-spark-download-budget.sh' > "${tmp}/spark_budget.txt"
  printf '%s\n' 'platform/catalog/Dockerfile' > "${tmp}/other_df.txt"
  check "Spark Dockerfile 改动 ⇒ 要跑演练" "RELEVANT" "$(judge_spark "${tmp}/spark_df.txt" | head -1)"
  check "Spark 钉扎清单改动 ⇒ 要跑演练" "RELEVANT" "$(judge_spark "${tmp}/spark_sums.txt" | head -1)"
  check "演练脚本自身改动 ⇒ 要跑演练" "RELEVANT" "$(judge_spark "${tmp}/spark_drill.txt" | head -1)"
  check "回退桩测脚本改动 ⇒ 要跑演练" "RELEVANT" "$(judge_spark "${tmp}/spark_budget.txt" | head -1)"
  check "别的 Dockerfile 改动 ⇒ 不跑演练（构建面口径是 ^platform/，此处只认 Spark 面）" "IRRELEVANT" "$(judge_spark "${tmp}/other_df.txt" | head -1)"
  check "纯 docs 改动 ⇒ 不跑演练" "IRRELEVANT" "$(judge_spark "${tmp}/docs.txt" | head -1)"
  check "演练面：空清单必须 fail-closed 判为要跑" "RELEVANT" "$(judge_spark "${tmp}/empty.txt" | head -1)"
  printf '%s\n' 'platform/batch-pipeline/docker/spark-cluster/Dockerfile.bak' > "${tmp}/spark_backup.txt"
  check "演练面：Dockerfile.bak 不等于 Dockerfile" "IRRELEVANT" "$(judge_spark "${tmp}/spark_backup.txt" | head -1)"
  local rc=0
  ( PR_NUMBER="" GITHUB_REPOSITORY="" GITHUB_REF="" fetch_via_api "${tmp}/api.txt" ) >/dev/null 2>&1 || rc=$?
  check "API 取数失败时返回非 0" "2" "${rc}"
  rc=0
  ( PR_NUMBER="" GITHUB_REPOSITORY="" GITHUB_REF="refs/heads/main" fetch_via_api "${tmp}/api2.txt" ) >/dev/null 2>&1 || rc=$?
  check "非 PR 触发面取数失败时返回非 0" "2" "${rc}"

  echo "-- 输出协议：GITHUB_OUTPUT 文件必须写出 docker_relevant 与 spark_drill_relevant --"
  # 注意（2026-11-24 加性修改）：本文件现在写**两行**（两个并列判定），
  # 故原先「整份文件 == 单行 docker_relevant=false」的断言在结构上已不可能成立
  # ⇒ 改为逐行断言（+ 行数断言）。既有断言的**语义**（docker_relevant 必须被写出、
  # 且只写一行）逐字保留，不放宽。
  local out_file="${tmp}/gh_output"
  : > "${out_file}"
  GITHUB_OUTPUT="${out_file}" emit_verdict false >/dev/null
  check "写出 docker_relevant=false" "docker_relevant=false" "$(grep -m1 '^docker_relevant=' "${out_file}" | tr -d ' ')"
  check "docker_relevant=false 只写一行" "1" "$(grep -c '^docker_relevant=false$' "${out_file}" | tr -d ' ')"
  check "写出 spark_drill_relevant=false（缺省随第一个）" "spark_drill_relevant=false" "$(grep -m1 '^spark_drill_relevant=' "${out_file}" | tr -d ' ')"
  check "spark_drill_relevant=false 只写一行" "1" "$(grep -c '^spark_drill_relevant=false$' "${out_file}" | tr -d ' ')"
  check "GITHUB_OUTPUT 共写两行" "2" "$(grep -c '.' "${out_file}" | tr -d ' ')"
  : > "${out_file}"
  GITHUB_OUTPUT="${out_file}" emit_verdict true >/dev/null
  check "写出 docker_relevant=true" "docker_relevant=true" "$(grep -m1 '^docker_relevant=' "${out_file}" | tr -d ' ')"
  check "docker_relevant=true 只写一行" "1" "$(grep -c '^docker_relevant=true$' "${out_file}" | tr -d ' ')"
  : > "${out_file}"
  GITHUB_OUTPUT="${out_file}" emit_verdict false true >/dev/null
  check "两个判定可分别给出（第一个 false）" "docker_relevant=false" "$(grep -m1 '^docker_relevant=' "${out_file}" | tr -d ' ')"
  check "两个判定可分别给出（第二个 true）" "spark_drill_relevant=true" "$(grep -m1 '^spark_drill_relevant=' "${out_file}" | tr -d ' ')"

  echo "-- ci.yml 接线断言（防「会永久阻塞 PR」的接线回归）--"
  local repo_root ci_file
  repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
  ci_file="${repo_root}/.github/workflows/ci.yml"
  if [ ! -f "${ci_file}" ]; then
    check "仓内存在 .github/workflows/ci.yml" "存在" "缺失"
  else
    check "仓内存在 .github/workflows/ci.yml" "存在" "存在"
    # ① 红线：workflow 级 paths: 过滤会让「不该触发的作业」不产生 check-run，
    #    而分支保护把 9 条 required check 写死在名单里（strict=true）⇒ PR 永久阻塞。
    local on_block
    on_block="$(awk '
      /^on:[ \t]*$/ {f=1; next}
      f && /^[A-Za-z_][A-Za-z0-9_-]*:/ {f=0}
      f {print}
    ' "${ci_file}")"
    check "on: 块内无 paths: 过滤" "0" "$(printf '%s\n' "${on_block}" | grep -c 'paths:' || true)"
    # ② 判定步骤存在且有 step id
    check "docker-build 作业含 id: relevance 的判定步骤" "1" "$(grep -c '^        id: relevance$' "${ci_file}" || true)"
    # ③ 发现步骤消费判定结果
    check "发现步骤消费 steps.relevance.outputs.docker_relevant" "1" "$(grep -c 'steps\.relevance\.outputs\.docker_relevant' "${ci_file}" || true)"
    # ④ 跳过开关仍由 docker_skip 门控（下游既有机制不被绕过）
    check "镜像重建循环仍由 docker_skip 门控" "1" "$(grep -c "if: env.docker_skip != 'true'" "${ci_file}" || true)"
    # ⑤ 本脚本的离线自测挂在 bash-check 作业上
    check "bash-check 作业挂了本脚本的自测步骤" "1" "$(grep -c 'check-docker-build-relevance\.sh --self-test' "${ci_file}" || true)"
    # ⑥ Spark 回退演练面（2026-11-24 新增）：第二个判定必须被 CI 消费，
    #    否则「改了回退逻辑却没人跑演练」会静默发生 ⇒ 接线断了必须判红。
    #    口径：**只数真正的调用点**，排除注释行（注释里也会提到脚本名）。
    #    「真的调用」= 行内不是注释（不以 # 开头）。
    check "CI 消费了 spark_drill_relevant 判定" "1" "$(grep -v '^[[:space:]]*#' "${ci_file}" | grep -c 'steps\.relevance\.outputs\.spark_drill_relevant' || true)"
    check "CI 里有回退演练步骤（真跑，不含 --self-test）" "1" "$(grep -v '^[[:space:]]*#' "${ci_file}" | grep 'scripts/test-spark-fallback-drill\.sh' | grep -vc -- '--self-test' || true)"
    # ⑦ 演练脚本的离线自测也必须挂在 bash-check 上（自测与真跑都要在 CI 里）
    check "bash-check 作业挂了演练脚本的自测步骤" "1" "$(grep -c 'test-spark-fallback-drill\.sh --self-test' "${ci_file}" || true)"
  fi

  echo
  echo "自测结果：通过 ${pass} 项 / 失败 ${fail} 项"
  [ "${fail}" -eq 0 ] || return 1
  return 0
}

if [ "${SELF_TEST}" = "1" ]; then
  run_self_test
  exit $?
fi

TMP_LIST="$(mktemp "${TMPDIR:-/tmp}/docker-build-relevance.XXXXXX")"
trap 'rm -f "${TMP_LIST}" "${TMP_LIST}.tmp"' EXIT

FAIL_CLOSED=0
if [ "${USE_API}" = "1" ] || [ -z "${FILES_FILE}" ]; then
  if ! fetch_via_api "${TMP_LIST}"; then
    FAIL_CLOSED=1
  fi
else
  if [ ! -f "${FILES_FILE}" ]; then
    echo "!! 找不到改动文件清单：${FILES_FILE}" >&2
    exit 2
  fi
  cp "${FILES_FILE}" "${TMP_LIST}"
fi

if [ "${FAIL_CLOSED}" = "1" ]; then
  echo "== 取数失败 ⇒ FAIL_CLOSED =="
  echo "== 判定：RELEVANT（必须重建，不静默跳过镜像验证）=="
  # 两个判定都 fail-closed（取数失败 ⇒ 两个面都要跑）
  emit_verdict true true
  exit 0
fi

echo "== 本次 PR 改动文件（$(wc -l < "${TMP_LIST}" | tr -d ' ') 个）=="
sed 's/^/  - /' "${TMP_LIST}"

RESULT="$(judge "${TMP_LIST}")"
VERDICT="$(printf '%s' "${RESULT}" | head -1)"
echo "== 判定：${VERDICT} =="
printf '%s\n' "${RESULT}" | tail -n +2

SPARK_RESULT="$(judge_spark "${TMP_LIST}")"
SPARK_VERDICT="$(printf '%s' "${SPARK_RESULT}" | head -1)"
echo "== Spark 回退演练面判定：${SPARK_VERDICT} =="
printf '%s\n' "${SPARK_RESULT}" | tail -n +2

# 两个判定互不隶属：构建面不相关 ≠ 演练面也不相关（演练面口径窄得多）。
# 收敛闸只跳过「44 个镜像重建」，**不**跳过回退演练 —— 演练由 CI 里另一个 step
# 按 spark_drill_relevant 决定，与 docker_skip 是两条独立的闸。
if [ "${VERDICT}" = "IRRELEVANT" ]; then
  SPARK_FLAG="false"
  [ "${SPARK_VERDICT}" = "RELEVANT" ] && SPARK_FLAG="true"
  emit_verdict false "${SPARK_FLAG}"
  echo ">> 改动不触及镜像构建面 ⇒ 本波跳过 44 个镜像重建"
  echo ">> 说明：这不是「验证通过」，而是「本波未验证镜像构建」；镜像面证据需回溯到最近一次真正执行过该构建的波次"
  exit 0
fi

SPARK_FLAG="false"
[ "${SPARK_VERDICT}" = "RELEVANT" ] && SPARK_FLAG="true"
emit_verdict true "${SPARK_FLAG}"
echo ">> 改动触及镜像构建面 ⇒ 必须全量重建"
exit 0
