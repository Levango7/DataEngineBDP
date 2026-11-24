#!/usr/bin/env bash
# Spark 回退路径端到端演练（台账 #112 续）
#
# 要证明的事：**主版本真的取不到时，构建能不能回退到回退版本（3.5.10）并完成安装。**
# 为什么以前没有这条证据：回退分支自设计以来**从未在真实构建里跑过**（主版本一直可取），
# 于是「回退版本的钉扎 SHA512 是否还能过」「装出来的 Spark 是不是真是 3.5.10」
# 都是**未验证假设**。2026-11-24 的回退版抬版（3.5.9→3.5.10）只验了**预检可达性**
# （Range GET 206），没验过「构建期真的回退并装成功」。
#
# 做法（**跑 Dockerfile 原文，不复刻逻辑**）：
#   ① 从 spark-cluster/Dockerfile **按内容锚点截断**出「取数 + 供应链校验 + 解包」段，
#      写成一份临时演练 Dockerfile。锚点 = 写入 /opt/spark/.version 的那一行
#      （它是那段 RUN 的末行）⇒ 截断点**不依赖行号**，Dockerfile 改行不会漂。
#      前面的 FROM / ARG / ENV 层逐字保留 ⇒ 与正常构建**同上下文、同基础层**
#      ⇒ BuildKit 层缓存命中 ⇒ 演练只付「重新下载回退版本 + 校验 + 解包」的代价。
#   ② 用 --build-arg SPARK_FORCE_FALLBACK=1 构建它 ⇒ Dockerfile 里的取数段**真的**跳过主版本，
#      走回退版本这条路径（开关语义见 Dockerfile 的 ARG SPARK_FORCE_FALLBACK）。
#   ③ 断言（全部取自构建日志与镜像内容，不靠人眼）：
#      · 构建退出码 0；
#      · 日志含「[DRILL] 强制主版本失效」；
#      · 日志**不含主版本的取数尝试痕迹**（证明主版本一个源都没试过）；
#      · 日志含「[ok] Spark <回退版本> 取数成功」与「Spark <回退版本> 安装完成」；
#      · 日志含钉扎 SHA512 的校验通过行；
#      · 镜像内 /opt/spark/.version == <回退版本>；
#      · 镜像内 /opt/spark 是指向 spark-<回退版本>-bin-hadoop3 的软链。
#
# 为什么「主版本没试过」断言的是**取数尝试痕迹**而不是「日志里没有主版本号」：
#   Dockerfile 自己的注释与 SHA512 映射表里就写着主版本号（那是**该文件自己的**文本，
#   会被 BuildKit 原样打进构建日志）⇒ 断言裸版本号会假红。真正的证据是
#   「[attempt] Spark <主版本>」这条**每次取数尝试都会打**的记录一条都没有。
#
# 为什么不跑完整镜像构建：完整构建还要装 pyspark sdist（450MB）与 5 个 Maven JAR，
# 实测整镜像 217–270 秒（PR #873 那轮 job 138521523958 = 217.1s、PR #867 那轮 job 138153904614 = 269.8s），
# 比演练的 64 秒贵 3–4 倍，且同一作业下方的「逐个 docker build」本就会完整构建 spark-cluster。
# 本演练只验「回退版本能取到 + 能过钉扎 SHA512 + 能解包安装」这一段；
# 后续层与版本选择无关——pyspark 的版本取自 /opt/spark/.version
# （Dockerfile：PYSPARK_VER="$(cat /opt/spark/.version)"），
# 而那份 .version 正是本演练断言的对象 ⇒ 「回退后 pyspark 跟着降」由此闭合。
#
# 为什么不走 scripts/build-retry.sh：演练要的是「一次构建里的确定性路径」，
# 换源重跑会把失败掩盖成「重跑就好了」，与本用例要证明的东西正好相反。
#
# 用法：
#   bash scripts/test-spark-fallback-drill.sh --self-test
#       离线自测（截断逻辑 + 断言逻辑；不碰 docker、不碰网络）⇒ 可进 CI 的 bash-check 作业。
#   bash scripts/test-spark-fallback-drill.sh --dry-run
#       只做静态前置断言与截断，并打印将要执行的 docker 命令（不构建）。
#   bash scripts/test-spark-fallback-drill.sh
#       真跑（需要 docker 与网络）。
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DOCKERFILE="${SPARK_DRILL_DOCKERFILE:-${REPO_ROOT}/platform/batch-pipeline/docker/spark-cluster/Dockerfile}"
CONTEXT_DIR="$(cd "$(dirname "${DOCKERFILE}")" && pwd)"
IMAGE_TAG="${SPARK_DRILL_IMAGE_TAG:-sq-spark-cluster:ci-fallback-drill}"

SELF_TEST=0
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --self-test) SELF_TEST=1 ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '2,50p' "$0"; exit 0 ;;
    *) echo "!! 未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

# ── 断言工具 ────────────────────────────────────────────────────────────────
pass=0
fail=0
check() {  # check <描述> <期望> <实际>
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1)); printf '  ✓ %s ⇒ %s\n' "$1" "$3"
  else
    fail=$((fail + 1)); printf '  ✗ %s ⇒ %s（期望 %s）\n' "$1" "$3" "$2"
  fi
}
check_contains() {  # check_contains <描述> <文件> <固定串>
  if grep -qF -- "$3" "$2"; then
    pass=$((pass + 1)); printf '  ✓ %s\n' "$1"
  else
    fail=$((fail + 1)); printf '  ✗ %s（文件里没有：%s）\n' "$1" "$3"
  fi
}
check_not_contains() {  # check_not_contains <描述> <文件> <固定串>
  if grep -qF -- "$3" "$2"; then
    fail=$((fail + 1)); printf '  ✗ %s（文件里出现了：%s）\n' "$1" "$3"
  else
    pass=$((pass + 1)); printf '  ✓ %s\n' "$1"
  fi
}

# ── 从 Dockerfile 实算坐标（一律不写死版本号） ───────────────────────────────
df_value() {  # df_value <正则>
  grep -m1 -E "$1" "${DOCKERFILE}" | sed -E 's/^[^=]*=//; s/[[:space:]]+$//; s/^"//; s/"$//'
}
PRIMARY_VER="$(df_value '^ARG SPARK_VERSION=')"
FALLBACK_VER="$(df_value '^ENV SPARK_VERSION_FALLBACK=')"
echo "== 坐标（从 Dockerfile 实算）=="
echo "  主版本（本次要被强制失效） = ${PRIMARY_VER:-<未取到>}"
echo "  回退版本（本次要验证的）   = ${FALLBACK_VER:-<未取到>}"

# ── 截断：取出「取数 + 供应链校验 + 解包」段 ────────────────────────────────
# 锚点：写入 /opt/spark/.version 的那一行（那段 RUN 的末行）。找不到 ⇒ fail-closed。
# 锚点匹配必须是**整行精确匹配**（剥掉前导/行尾空白后整行相等），不能用子串 grep：
#   子串匹配会把 `echo "${SPARK_VER}" > /opt/spark/.version.bak` 也当成锚点
#   ⇒ 截断点漂移到错误的位置却不报红（这是变异验证 M7 实测出来的真实缺陷）。
ANCHOR_LINE='echo "${SPARK_VER}" > /opt/spark/.version'
make_drill_dockerfile() {  # make_drill_dockerfile <源 Dockerfile> <输出>
  awk -v anchor="${ANCHOR_LINE}" '
    {
      l = $0
      sub(/^[ \t]+/, "", l)
      sub(/[ \t]+$/, "", l)
      print
      if (l == anchor) { found = 1; exit }
    }
    END { if (!found) exit 3 }
  ' "$1" > "$2"
  [ -s "$2" ] || return 1
  # 产物里必须存在**整行**（剥掉前导/行尾空白后）等于锚点的那一行 —— 与上面的截断口径同源。
  awk -v anchor="${ANCHOR_LINE}" '
    { l=$0; sub(/^[ \t]+/,"",l); sub(/[ \t]+$/,"",l); if (l == anchor) { found=1; exit } }
    END { exit (found ? 0 : 3) }
  ' "$2"
  return $?
}

# ── 日志判据（真跑与离线自测共用同一份断言） ────────────────────────────────
assert_drill_log() {  # assert_drill_log <构建日志文件>
  local log="$1"
  check_contains "日志含演练标记（主版本被强制跳过）" "${log}" "[DRILL] 强制主版本失效"
  check_not_contains "日志不含主版本的取数尝试（主版本一个源都没试）" "${log}" "[attempt] Spark ${PRIMARY_VER}"
  check_not_contains "日志不含主版本取数成功" "${log}" "Spark ${PRIMARY_VER} 取数成功"
  check_contains "日志含回退版本取数成功" "${log}" "[ok] Spark ${FALLBACK_VER} 取数成功"
  check_contains "日志含回退版本安装完成" "${log}" "Spark ${FALLBACK_VER} 安装完成"
  check_contains "日志含钉扎 SHA512 校验通过行" "${log}" "spark-${FALLBACK_VER}-bin-hadoop3.tgz: OK"
}

# ── 静态前置断言（对真实 Dockerfile 原文） ───────────────────────────────────
preflight() {
  local rc=0
  echo "== 静态前置断言（真实 Dockerfile 原文）=="
  if [ -z "${PRIMARY_VER}" ] || [ -z "${FALLBACK_VER}" ]; then
    check "能从 Dockerfile 实算主版本与回退版本" "两者都取到" "主=${PRIMARY_VER:-空} 回退=${FALLBACK_VER:-空}"
    rc=1
  else
    check "能从 Dockerfile 实算主版本与回退版本" "两者都取到" "两者都取到"
  fi
  check_contains "Dockerfile 声明了演练开关（缺省 0）" "${DOCKERFILE}" "ARG SPARK_FORCE_FALLBACK=0"
  check_contains "取数段认演练开关" "${DOCKERFILE}" '[ "${SPARK_FORCE_FALLBACK}" = "1" ]'
  check_contains "演练开关命中时跳过主版本" "${DOCKERFILE}" "[DRILL] 强制主版本失效"
  n_anchor="$(awk -v anchor="${ANCHOR_LINE}" '
    { l=$0; sub(/^[ \t]+/,"",l); sub(/[ \t]+$/,"",l); if (l == anchor) c++ }
    END { print c+0 }
  ' "${DOCKERFILE}")"
  check "截断锚点在 Dockerfile 里只出现一次（多处则截断点有歧义）" "1" "${n_anchor}"
  # pyspark 版本必须取自「实际安装版本」文件，而不是主版本号 ——
  # 否则回退到 3.5.10 时会装 pyspark 4.2.1，Worker 与集群 JVM 版本错配。
  check_contains "pyspark 版本取自 /opt/spark/.version（回退后自动跟随）" "${DOCKERFILE}" 'PYSPARK_VER="$(cat /opt/spark/.version)"'
  check_not_contains "pyspark 不按主版本号装（防回退后版本错配）" "${DOCKERFILE}" 'pyspark==${SPARK_VERSION}'
  return "${rc}"
}

# ── 离线自测 ────────────────────────────────────────────────────────────────
run_self_test() {
  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/spark-fallback-drill.XXXXXX")"
  trap 'rm -rf "${tmp}"' RETURN

  echo "== 截断逻辑 =="
  local cut="${tmp}/drill.Dockerfile"
  if make_drill_dockerfile "${DOCKERFILE}" "${cut}"; then
    check "能按锚点截断出演练 Dockerfile" "0" "$?"
    check_contains "截断产物含演练开关声明" "${cut}" "ARG SPARK_FORCE_FALLBACK=0"
    check_contains "截断产物含取数段" "${cut}" "try_version"
    check_contains "截断产物含钉扎 SHA512 校验" "${cut}" "SPARK_TGZ_SHA512"
    check_contains "截断产物含解包与版本文件写入" "${cut}" "/opt/spark/.version"
    check_not_contains "截断产物不含 pyspark 安装段（演练不装 pyspark）" "${cut}" "PYSPARK_VER"
    # 注意：Iceberg 的字面量在截断点**之前**就存在（文件头注释 L9 与 ARG 段的
    # ICEBERG_* 注释都在前面）⇒ 不能用「有没有 iceberg-spark-runtime」判段是否被截掉。
    # 真正区分「安装段被截掉」的标志是该段的**执行体**变量赋值（Dockerfile 的 Iceberg
    # 安装 RUN 里才有 ICEBERG_SPARK_JAR= / ICEBERG_AWS_JAR=）。
    check_not_contains "截断产物不含 Iceberg 安装段（演练不装 Iceberg JAR）" "${cut}" "ICEBERG_SPARK_JAR="
  else
    check "能按锚点截断出演练 Dockerfile" "0" "1"
  fi

  echo "== 静态前置断言（对真实 Dockerfile）=="
  preflight >/dev/null 2>&1 || true

  echo "== 断言逻辑：绿样本必须全过 =="
  local green="${tmp}/green.log"
  cat >"${green}" <<LOG
#8 [ 4/13] RUN set -eux; ...
+ echo >>> [DRILL] 强制主版本失效（SPARK_FORCE_FALLBACK=1）：跳过主版本 ${PRIMARY_VER}（未尝试任何源），直接验证回退版本 ${FALLBACK_VER}
>>> [DRILL] 强制主版本失效（SPARK_FORCE_FALLBACK=1）：跳过主版本 ${PRIMARY_VER}（未尝试任何源），直接验证回退版本 ${FALLBACK_VER}
>>> [attempt] Spark ${FALLBACK_VER} 源1/3：https://mirrors.tuna.tsinghua.edu.cn/apache/spark/spark-${FALLBACK_VER}/spark-${FALLBACK_VER}-bin-hadoop3.tgz
>>> [ok] Spark ${FALLBACK_VER} 取数成功（https://mirrors.tuna.tsinghua.edu.cn/apache/spark/spark-${FALLBACK_VER}/spark-${FALLBACK_VER}-bin-hadoop3.tgz；本源实耗 63s，预算余 357s）
/tmp/spark-${FALLBACK_VER}-bin-hadoop3.tgz: OK
>>> Spark ${FALLBACK_VER} 安装完成
LOG
  assert_drill_log "${green}"

  echo "== 断言逻辑：红样本必须判红 =="
  # 每个红样本都必须让上面的判据至少失败一条；失败计数在判完后回滚，
  # 避免红样本的预期失败污染总账（红样本只证明「判据能抓住」，不是真的失败）。
  judge_red_sample() {  # judge_red_sample <描述> <日志文件>
    local f0="${fail}"
    assert_drill_log "$2"
    local f1="${fail}"
    fail="${f0}"
    check "$1（必须被判红）" "1" "$(( f1 > f0 ? 1 : 0 ))"
  }

  local red_primary="${tmp}/red_primary.log"
  cp "${green}" "${red_primary}"
  printf '>>> [attempt] Spark %s 源1/3：https://example.invalid/x\n' "${PRIMARY_VER}" >>"${red_primary}"
  judge_red_sample "红样本1（主版本被取了）" "${red_primary}"

  local red_nodrill="${tmp}/red_nodrill.log"
  grep -v '\[DRILL\]' "${green}" >"${red_nodrill}"
  judge_red_sample "红样本2（缺演练标记）" "${red_nodrill}"

  local red_nook="${tmp}/red_nook.log"
  grep -v "\[ok\] Spark ${FALLBACK_VER} 取数成功" "${green}" >"${red_nook}"
  judge_red_sample "红样本3（缺回退取数成功）" "${red_nook}"

  local red_nosha="${tmp}/red_nosha.log"
  grep -v "spark-${FALLBACK_VER}-bin-hadoop3.tgz: OK" "${green}" >"${red_nosha}"
  judge_red_sample "红样本4（缺 SHA512 校验通过）" "${red_nosha}"

  local red_noinstall="${tmp}/red_noinstall.log"
  grep -v "Spark ${FALLBACK_VER} 安装完成" "${green}" >"${red_noinstall}"
  judge_red_sample "红样本5（缺安装完成）" "${red_noinstall}"

  echo
  echo "自测结果：通过 ${pass} 项 / 失败 ${fail} 项"
  [ "${fail}" -eq 0 ] || return 1
  return 0
}

if [ "${SELF_TEST}" = "1" ]; then
  run_self_test
  exit $?
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/spark-fallback-drill-run.XXXXXX")"
DRILL_DF="${WORK}/Dockerfile.spark-fallback-drill"
LOG="${WORK}/build.log"
cleanup() {
  # 只删本次自己产出的镜像与临时目录，不碰 runner 上的其它缓存（台账 #100 的口径）。
  docker rmi -f "${IMAGE_TAG}" >/dev/null 2>&1 || true
  rm -rf "${WORK}"
}
trap cleanup EXIT

if ! preflight; then
  echo "!! 静态前置断言未通过 ⇒ 不进行演练构建" >&2
  exit 1
fi

if ! make_drill_dockerfile "${DOCKERFILE}" "${DRILL_DF}"; then
  echo "!! 没能从 Dockerfile 截断出演练段（锚点缺失）⇒ fail-closed，不进行演练构建" >&2
  exit 1
fi
echo "== 演练 Dockerfile（截断到 .version 写入行）=="
echo "  源：${DOCKERFILE}"
echo "  产物：${DRILL_DF}（$(wc -l < "${DRILL_DF}" | tr -d ' ') 行）"

# 基座源与正常构建同一条路径（否则基础层不共享缓存，演练会平白多拉一遍基座）。
BASE_REGISTRY="${BASE_REGISTRY:-}"
if [ -z "${BASE_REGISTRY}" ]; then
  BASE_REGISTRY="$(bash "${SCRIPT_DIR}/pick-base-registry.sh" || true)"
  BASE_REGISTRY="${BASE_REGISTRY:-docker.io}"
fi
echo "  BASE_REGISTRY=${BASE_REGISTRY}"

# --progress=plain 让 BuildKit 打全量日志（断言要读日志里的取数/校验/安装行）。
# 老版本 docker CLI 不认这个 flag ⇒ 先探一次支持情况，不认就不带（不带时 CI 的
# 非 TTY 输出本就是 plain 形态，判据不受影响）。
PROGRESS_FLAG=""
if command -v docker >/dev/null 2>&1; then
  if docker build --help 2>/dev/null | grep -q -- '--progress'; then
    PROGRESS_FLAG="--progress=plain"
  fi
fi

BUILD_CMD=(docker build)
if [ -n "${PROGRESS_FLAG}" ]; then
  BUILD_CMD+=("${PROGRESS_FLAG}")
fi
BUILD_CMD+=(--build-arg "BASE_REGISTRY=${BASE_REGISTRY}"
           --build-arg "SPARK_FORCE_FALLBACK=1"
           -t "${IMAGE_TAG}"
           -f "${DRILL_DF}"
           "${CONTEXT_DIR}")
echo "  命令：${BUILD_CMD[*]}"

if [ "${DRY_RUN}" = "1" ]; then
  echo "== --dry-run：到此为止，不构建 =="
  exit 0
fi

command -v docker >/dev/null 2>&1 || { echo "!! 没有 docker 命令" >&2; exit 2; }

echo "== 开始演练构建（强制主版本失效 ⇒ 回退到 ${FALLBACK_VER}）=="
set -o pipefail
"${BUILD_CMD[@]}" 2>&1 | tee "${LOG}"
BUILD_RC=${PIPESTATUS[0]}
set +o pipefail
echo "== 构建退出码：${BUILD_RC} =="

echo "== 断言 =="
check "构建退出码" "0" "${BUILD_RC}"
if [ "${BUILD_RC}" != "0" ]; then
  echo "---- 演练日志尾部 40 行 ----"
  tail -n 40 "${LOG}"
  echo "--------------------------------"
  echo "自测结果：通过 ${pass} 项 / 失败 ${fail} 项"
  exit 1
fi
assert_drill_log "${LOG}"

echo "== 断言镜像内容 =="
VERSION_IN_IMAGE="$(docker run --rm --entrypoint cat "${IMAGE_TAG}" /opt/spark/.version 2>/dev/null | tr -d '\r\n')"
LINK_IN_IMAGE="$(docker run --rm --entrypoint readlink "${IMAGE_TAG}" /opt/spark 2>/dev/null | tr -d '\r\n')"
check "镜像内 /opt/spark/.version" "${FALLBACK_VER}" "${VERSION_IN_IMAGE}"
check "镜像内 /opt/spark 软链指向" "/opt/spark-${FALLBACK_VER}-bin-hadoop3" "${LINK_IN_IMAGE}"

echo
echo "演练结果：通过 ${pass} 项 / 失败 ${fail} 项"
[ "${fail}" -eq 0 ] || exit 1
exit 0
