#!/usr/bin/env bash
# pyspark 取数段「墙钟上限」回归测试（台账 #112 的 CI 治理线配套 · 2 处 Dockerfile）
#
# 为什么需要它：
#   台账 #112 登记的残余边界是「pyspark pip 段无墙钟上限」——它是仓内**唯一**（改前是
#   两处）**从 PyPI 取数百 MB 且没有任何墙钟上限**的取数段：Spark tarball 有 420s 总预算，
#   5 处 Maven JAR 各有 120/600/60/120/120 的本源上限，而 pyspark 段是裸的 `pip install`。
#   后果不是「慢」，而是**红相被伪装**：scripts/build-retry.sh 的 per-attempt 挂起超时
#   （BASE_HANG_TIMEOUT，缺省 600s）会把整条镜像构建 SIGKILL 掉 ⇒ BuildKit 报
#   `failed to solve: Canceled: context canceled` ⇒ 面板上长得像编译失败，
#   实际是取数面速率问题（与台账 #112 的 2026-11-03 原始事故同族，只是这次落在 pip 段）。
#   缺陷之所以能合进来，是因为**仓内没有任何测试覆盖这几段的墙钟上限**。
#
# 措辞注记（别改回字面量）：本脚本**不执行**任何镜像构建命令，也不拉基座、不联网。
#   仓内门禁 scripts/check-dockerfile-base-registry.py 的 R7「脚本半边」按**整份文本**
#   扫「docker + build」命令形态（注释同样计入命中）⇒ 注释里若出现该命令字面量，本脚本
#   会被误分类成「手工构建腿」并被判红（要求它参选源）。本脚本根本不拉基座 ⇒ 无源可选，
#   故上方一律写「镜像构建」而非命令字面量。
#
# 覆盖的两处（改前形态）：
#   ① platform/batch-pipeline/docker/spark-cluster/Dockerfile 的 pyspark 安装段
#      （`python3 -m pip install ... || (pip3 install ... || exit 1)`）
#   ② platform/batch-pipeline/Dockerfile builder 段的运行时依赖安装段
#      （`pip install --no-cache-dir --target=/install -r requirements.txt`，requirements
#      里含 `pyspark>=4.0,<5.0` ⇒ 实测从 PyPI 拉 455.9MB sdist 并本地构建 wheel）
#
# 本测试做什么：
#   ① 从两个 Dockerfile 里**原样抽取**对应的 RUN 段（复刻 BuildKit 的两条续行语义：
#      行尾反斜杠续行、续行里的整行注释被整行删除）⇒ 测的就是构建时真正跑的那段文本；
#   ② 用「虚拟时钟」桩替代 `date +%s`、用「复刻 GNU timeout 墙钟语义」的桩替代 `timeout`、
#      用「按名义耗时推进虚拟时钟」的桩替代 `pip`/`pip3`/`python3`：
#      秒级内复现真实时间线（不真的睡墙钟、不碰网络、不调 docker）；
#   ③ 断言的不变量：
#      · 健康形态：安装成功，日志给出墙钟上限与实耗；
#      · 超过上限：**段自己**判红（exit 1 + [FATAL] + 明说「超出墙钟上限」），
#        而不是被外层 SIGKILL 成 `context canceled`；
#      · 被调命令自身失败（非 124）时，报的是「安装失败」而不是「超出墙钟上限」（不混判据）；
#      · `python3 -m pip` 失败后回退 `pip3` 的老语义仍然成立；
#      · 预算算术：上限 + 本段之前的耗时 + 余量 ≤ 外层 per-attempt 预算
#        （外层预算从 scripts/build-retry.sh 现场读，**不写死**）⇒ 上限调大即转红；
#      · 两处 Dockerfile 都声明了上限 ARG，且缺省值一致。
#
# 可选参数：指定被测 Dockerfile（默认取仓内当前版本）。给别的路径是为了做**变异验证**：
#   拿改前的旧版本跑同一份测试，必须转红 ⇒ 证明这测试真的能抓住「无墙钟上限」这个缺陷。
#   用法：bash scripts/test-pyspark-install-budget.sh <spark-cluster-Dockerfile> [batch-pipeline-Dockerfile]
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SPARK_DOCKERFILE="${1:-${REPO_ROOT}/platform/batch-pipeline/docker/spark-cluster/Dockerfile}"
BATCH_DOCKERFILE="${2:-${REPO_ROOT}/platform/batch-pipeline/Dockerfile}"
BUILD_RETRY="${REPO_ROOT}/scripts/build-retry.sh"

for f in "${SPARK_DOCKERFILE}" "${BATCH_DOCKERFILE}" "${BUILD_RETRY}"; do
  if [ ! -f "${f}" ]; then
    echo "!! 找不到文件：${f}"
    exit 2
  fi
done

WORK="$(mktemp -d)"
STUBDIR="${WORK}/stub"
OUTDIR="${WORK}/out"
mkdir -p "${STUBDIR}" "${OUTDIR}"
trap 'rm -rf "${WORK}"' EXIT
# 把工作目录切进临时目录：抽取/桩若意外写出文件，落点也在临时目录里，
# 绝不会把垃圾文件写进仓内工作区（容器里跑本测试时尤其重要）。
cd "${WORK}" || exit 2

# ── 预算常量（外层预算**现场读**，不写死） ───────────────────────────────────
# 外层 per-attempt 挂起超时：scripts/build-retry.sh 的 BASE_HANG_TIMEOUT 缺省值。
OUTER_TIMEOUT="$(sed -n 's/^HANG_TIMEOUT="\${BASE_HANG_TIMEOUT:-\([0-9][0-9]*\)}"/\1/p' "${BUILD_RETRY}" | head -1)"
if [ -z "${OUTER_TIMEOUT}" ]; then
  echo "!! 没能从 scripts/build-retry.sh 读出 BASE_HANG_TIMEOUT 缺省值（测试口径失效，拒绝空跑）"
  exit 2
fi
# 本段之前的耗时：CI 实测 spark-cluster 整镜像 224s − 取数 RUN 58.30s − pyspark 段
# 30.4s ≈ 135s（PR #805 CI，job 132626373067；pyspark 段 30.4s 见 PR #810 第四波
# job 132775309149 的 `#12 DONE 30.4s`）。
PRE_SEGMENT_RESERVE=135
# 目标余量：本段判红后仍要留出余量，避免与外层 600s 撞线。
SAFETY_MARGIN=165
MAX_CAP=$(( OUTER_TIMEOUT - PRE_SEGMENT_RESERVE - SAFETY_MARGIN ))
# 上限缺省值（两处 Dockerfile 都必须是它）。
EXPECTED_CAP=300

# ── ① 抽取器：定位含指定关键字的 RUN 段 ───────────────────────────────────
# 复刻 BuildKit 的两条续行语义：
#   1) 行尾反斜杠 + 换行被去掉，其余（含下一行行首缩进）原样保留；
#   2) 续行里夹的**整行注释**会被整行删掉（仓内原 Dockerfile 就靠这个形态构建通过）。
extract_run() {  # extract_run <Dockerfile> <关键字正则> <输出文件>
  awk -v want="$2" '
    function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }
    { lines[NR] = $0 }
    END {
      i = 1
      while (i <= NR) {
        if (lines[i] ~ /^RUN /) {
          buf = ""; j = i; hit = 0
          while (j <= NR) {
            t = trim(lines[j])
            if (t ~ /^#/) { j++; continue }
            if (lines[j] ~ /\\$/) { sub(/\\$/, "", lines[j]); buf = buf lines[j]; j++; continue }
            buf = buf lines[j]; j++; break
          }
          if (buf ~ want) {
            sub(/^RUN[ \t]+/, "", buf)
            gsub(/[ \t]+$/, "", buf)
            if (buf !~ /;$/) buf = buf ";"
            printf "%s\n", buf
            # 末尾必须**保住被测段的退出码**：不能只追加一句 echo（echo 的退出码会把
            # 被测段的失败态吞掉 ⇒ 失败用例看起来「成功」）。老写法
            # `... || (pip3 install ... || exit 1)` 的失败出口在**子 shell** 里，
            # 只退子 shell、不退外层 ⇒ 只能靠显式 `rc=$?` + `exit $rc` 才抓得住。
            # 新写法在顶层 `exit 1`，追加行不会执行，脚本照样以 1 退出（两种都覆盖）。
            printf "rc=$?; echo \"[RESULT] RC=$rc\"; exit $rc\n"
            hit = 1
            exit
          }
          i = j
          continue
        }
        i++
      }
      if (!hit) exit 3
    }
  ' "$1" > "$3"
}

if ! extract_run "${SPARK_DOCKERFILE}" 'PYSPARK_VER' "${WORK}/spark_run.sh"; then
  echo "!! 没能在 spark-cluster Dockerfile 里定位到 pyspark 安装 RUN 段"
  exit 2
fi
if ! extract_run "${BATCH_DOCKERFILE}" 'requirements\.txt' "${WORK}/batch_run.sh"; then
  echo "!! 没能在 batch-pipeline Dockerfile 里定位到依赖安装 RUN 段"
  exit 2
fi

# spark-cluster 的 RUN 读 `/opt/spark/.version`（构建期写、构建期读）。测试环境没有
# 那个路径 ⇒ 把抽取文本里的路径换成本测试的夹具文件（换的是**被测文本里的路径**，
# 被测逻辑一字不动）。
echo "4.2.1" > "${WORK}/spark.version"
sed "s#/opt/spark/.version#${WORK}/spark.version#g" "${WORK}/spark_run.sh" > "${WORK}/spark_run_fixed.sh"
mv "${WORK}/spark_run_fixed.sh" "${WORK}/spark_run.sh"

# ── ② 桩：虚拟时钟（date）+ 复刻 GNU timeout 语义（timeout）+ pip 三兄弟 ────
cat >"${STUBDIR}/date" <<'STUB'
#!/usr/bin/env bash
# 虚拟时钟：只实现 `date +%s`（被测段仅用这一种调用）。
# 不自行推进；推进由 pip 桩按**名义耗时**完成 ⇒ 时间线完全确定、零真实等待。
cat "${STUB_CLOCK_FILE}"
STUB
chmod +x "${STUBDIR}/date"

cat >"${STUBDIR}/timeout" <<'STUB'
#!/usr/bin/env bash
# 复刻 GNU timeout 的**墙钟上限**语义（虚拟时钟版）：
#   · 被调命令在上限内跑完 ⇒ 透传它的退出码；
#   · 被调命令的实耗超过上限 ⇒ 在上限处被收掉，timeout 自己以 124 退出。
# 实现方式：把上限通过 STUB_TIMEOUT_CAP 传给被调命令，由 pip 桩在推进虚拟时钟时
# 按上限截断（并以 124 退出）——等价于「在上限那一刻被 SIGTERM 掉」。
cap=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -*) shift ;;                       # 跳过选项（如 --foreground / -k）
    *)  cap="$1"; shift; break ;;
  esac
done
if [ -z "${cap}" ]; then
  echo "timeout: missing operand" >&2
  exit 125
fi
printf '%s\n' "cap=${cap}" >> "${STUB_ARGV_LOG}"
STUB_TIMEOUT_CAP="${cap}" export STUB_TIMEOUT_CAP
exec "$@"
STUB
chmod +x "${STUBDIR}/timeout"

cat >"${STUBDIR}/pip_stub_common.sh" <<'STUB'
#!/usr/bin/env bash
# pip 家族的共同桩：按**名义耗时**推进虚拟时钟；名义耗时超过 timeout 给的上限时，
# 在上限处被收掉并以 124 退出（等价于被 SIGTERM）。
now="$(cat "${STUB_CLOCK_FILE}")"
nominal="${STUB_PIP_SECONDS:-1}"
rc="${STUB_PIP_RC:-0}"
cap="${STUB_TIMEOUT_CAP:-0}"
printf '%s\n' "$*" >> "${STUB_ARGV_LOG}"
if [ "${cap}" -gt 0 ] && [ "${nominal}" -gt "${cap}" ]; then
  echo $(( now + cap )) > "${STUB_CLOCK_FILE}"
  echo "pip-stub: killed at wall-clock cap ${cap}s (nominal ${nominal}s)" >&2
  exit 124
fi
echo $(( now + nominal )) > "${STUB_CLOCK_FILE}"
exit "${rc}"
STUB

cat >"${STUBDIR}/pip" <<'STUB'
#!/usr/bin/env bash
. "$(dirname "${BASH_SOURCE[0]}")/pip_stub_common.sh"
STUB
chmod +x "${STUBDIR}/pip"

cat >"${STUBDIR}/pip3" <<'STUB'
#!/usr/bin/env bash
. "$(dirname "${BASH_SOURCE[0]}")/pip_stub_common.sh"
STUB
chmod +x "${STUBDIR}/pip3"

cat >"${STUBDIR}/python3" <<'STUB'
#!/usr/bin/env bash
# `python3 -m pip install ...` ⇒ 转交 pip 桩（同一套名义耗时/退出码语义）。
if [ "${1:-}" = "-m" ] && [ "${2:-}" = "pip" ]; then
  shift 2
  exec pip "$@"
fi
echo "python3-stub: unsupported invocation: $*" >&2
exit 2
STUB
chmod +x "${STUBDIR}/python3"

# ── ③ 断言工具 ────────────────────────────────────────────────────────────────
pass=0
fail=0
check() {  # check <描述> <期望> <实际>
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1)); printf '  ✓ %s ⇒ %s\n' "$1" "$3"
  else
    fail=$((fail + 1)); printf '  ✗ %s ⇒ %s（期望 %s）\n' "$1" "$3" "$2"
  fi
}
check_contains() {
  if grep -qF -- "$3" "$2"; then
    pass=$((pass + 1)); printf '  ✓ %s\n' "$1"
  else
    fail=$((fail + 1)); printf '  ✗ %s（日志里没有：%s）\n' "$1" "$3"
  fi
}
check_not_contains() {
  if grep -qF -- "$3" "$2"; then
    fail=$((fail + 1)); printf '  ✗ %s（日志里出现了：%s）\n' "$1" "$3"
  else
    pass=$((pass + 1)); printf '  ✓ %s\n' "$1"
  fi
}
check_le() {  # check_le <描述> <上限> <实际>
  if [ "$3" -le "$2" ]; then
    pass=$((pass + 1)); printf '  ✓ %s ⇒ %s ≤ %s\n' "$1" "$3" "$2"
  else
    fail=$((fail + 1)); printf '  ✗ %s ⇒ %s > %s\n' "$1" "$3" "$2"
  fi
}

# run_case <名> <RUN 脚本> <环境赋值...>
run_case() {
  name="$1"; shift
  runscript="$1"; shift
  log="${OUTDIR}/${name}.log"
  argvlog="${OUTDIR}/${name}.argv"
  clock="${OUTDIR}/${name}.clock"
  echo 0 > "${clock}"
  : > "${argvlog}"
  ( export PATH="${STUBDIR}:${PATH}" \
           STUB_CLOCK_FILE="${clock}" \
           STUB_ARGV_LOG="${argvlog}" \
           PYSPARK_INSTALL_TIMEOUT_SECONDS="${CAP:-${EXPECTED_CAP}}" \
           PY_DEPS_INSTALL_TIMEOUT_SECONDS="${CAP:-${EXPECTED_CAP}}" \
           "$@" ; \
     sh "${runscript}" ) >"${log}" 2>&1
  echo $?
}

# ── ④ 静态断言：两处 Dockerfile 的上限声明 ─────────────────────────────────
echo "== T0 静态断言：上限 ARG 声明与缺省值 =="
for pair in "${SPARK_DOCKERFILE}:PYSPARK_INSTALL_TIMEOUT_SECONDS" \
            "${BATCH_DOCKERFILE}:PY_DEPS_INSTALL_TIMEOUT_SECONDS"; do
  df="${pair%%:*}"; key="${pair##*:}"
  val="$(sed -n "s/^ARG ${key}=//p" "${df}" | head -1)"
  check "${df##*/} 声明 ARG ${key}" "1" "$([ -n "${val}" ] && echo 1 || echo 0)"
  check "${df##*/} 的 ${key} 缺省值" "${EXPECTED_CAP}" "${val}"
done

echo "== T1 预算算术：上限 + 本段之前的耗时 + 余量 ≤ 外层 per-attempt 预算 =="
echo "  外层 per-attempt 预算（现场读自 scripts/build-retry.sh）= ${OUTER_TIMEOUT}s"
echo "  本段之前耗时（CI 实测）= ${PRE_SEGMENT_RESERVE}s；目标余量 = ${SAFETY_MARGIN}s"
echo "  ⇒ 允许的上限上限 = ${MAX_CAP}s"
check_le "spark-cluster 上限不越界" "${MAX_CAP}" "${EXPECTED_CAP}"
check_le "batch-pipeline 上限不越界" "${MAX_CAP}" "${EXPECTED_CAP}"
check_le "外层预算为正（口径没读空）" "1" "$([ "${OUTER_TIMEOUT}" -gt 0 ] && echo 1 || echo 0)"

echo "== S1 spark-cluster 健康形态：安装成功 =="
rc="$(run_case s1 "${WORK}/spark_run.sh" STUB_PIP_SECONDS=30)"
check "退出码" "0" "${rc}"
check_contains "给出墙钟上限" "${OUTDIR}/s1.log" "墙钟上限 ${EXPECTED_CAP}s"
check_contains "给出实耗" "${OUTDIR}/s1.log" "安装完成"
check_contains "装的是 .version 里的版本" "${OUTDIR}/s1.argv" "pyspark==4.2.1"
check_contains "timeout 桩收到了上限" "${OUTDIR}/s1.argv" "cap=${EXPECTED_CAP}"

echo "== S2 spark-cluster 超过上限：段自己判红（不被外层 SIGKILL） =="
# 名义耗时 9300s = 本机当轮实测速率（43–54 kB/s）下 pyspark 整包所需墙钟。
rc="$(run_case s2 "${WORK}/spark_run.sh" STUB_PIP_SECONDS=9300)"
check "退出码（段自己判红）" "1" "${rc}"
check_contains "明说超出墙钟上限" "${OUTDIR}/s2.log" "超出墙钟上限 ${EXPECTED_CAP}s"
check_contains "判定为取数面速率不足" "${OUTDIR}/s2.log" "取数面速率不足"
check_not_contains "不是被外层 SIGKILL 伪装成编译失败" "${OUTDIR}/s2.log" "context canceled"

echo "== S3 spark-cluster 被调命令自身失败（非 124）⇒ 报「安装失败」而不是「超出上限」 =="
rc="$(run_case s3 "${WORK}/spark_run.sh" STUB_PIP_SECONDS=5 STUB_PIP_RC=1)"
check "退出码" "1" "${rc}"
check_contains "报安装失败" "${OUTDIR}/s3.log" "安装失败"
check_not_contains "不误报成超出上限" "${OUTDIR}/s3.log" "超出墙钟上限"

echo "== S4 spark-cluster 老语义保留：python3 -m pip 失败后回退 pip3 =="
# python3 桩在 STUB_PY3_RC 非 0 时失败（这里让主路失败、回退路成功）。
cat >"${STUBDIR}/python3" <<'STUB'
#!/usr/bin/env bash
# `python3 -m pip install ...` ⇒ 转交 pip 桩；STUB_PY3_RC 非 0 时主路失败。
if [ "${1:-}" = "-m" ] && [ "${2:-}" = "pip" ]; then
  shift 2
  if [ "${STUB_PY3_RC:-0}" -ne 0 ]; then
    echo "python3-stub: forced failure" >&2
    exit "${STUB_PY3_RC}"
  fi
  exec pip "$@"
fi
echo "python3-stub: unsupported invocation: $*" >&2
exit 2
STUB
chmod +x "${STUBDIR}/python3"
rc="$(run_case s4 "${WORK}/spark_run.sh" STUB_PY3_RC=2 STUB_PIP_SECONDS=10)"
check "退出码（回退成功 ⇒ 整体成功）" "0" "${rc}"
check_contains "回退到了 pip3" "${OUTDIR}/s4.argv" "pyspark==4.2.1"
n_py3="$(grep -c 'python3 -m pip' "${OUTDIR}/s4.log" || true)"
check "主路确实被试过（日志里能看到 python3 -m pip）" "1" "$([ "${n_py3}" -ge 1 ] && echo 1 || echo 0)"

echo "== S5 batch-pipeline 依赖段：健康形态与超上限 =="
rc="$(run_case s5 "${WORK}/batch_run.sh" STUB_PIP_SECONDS=33)"
check "健康形态退出码" "0" "${rc}"
check_contains "给出墙钟上限" "${OUTDIR}/s5.log" "墙钟上限 ${EXPECTED_CAP}s"
check_contains "给出实耗" "${OUTDIR}/s5.log" "安装完成"
rc="$(run_case s6 "${WORK}/batch_run.sh" STUB_PIP_SECONDS=9300)"
check "超上限退出码（段自己判红）" "1" "${rc}"
check_contains "明说超出墙钟上限" "${OUTDIR}/s6.log" "超出墙钟上限 ${EXPECTED_CAP}s"
check_contains "判定为取数面速率不足" "${OUTDIR}/s6.log" "取数面速率不足"
check_not_contains "不是被外层 SIGKILL 伪装成编译失败" "${OUTDIR}/s6.log" "context canceled"

echo
echo "桩测结果：通过 ${pass} 项 / 失败 ${fail} 项"
[ "${fail}" -eq 0 ] || exit 1
exit 0
