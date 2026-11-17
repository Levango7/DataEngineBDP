#!/usr/bin/env bash
# Maven JAR 取数「预算记账」回归测试（台账 #112 的 CI 治理线配套 · 5 处 JAR）
#
# 为什么需要它：
#   2026-11-15 CI 实测（PR #807 head e0a57673，`Docker Build Verify` job 132689363767，failed）
#   暴露了 Spark 取数段的记账缺陷：curl 带 `--retry 2` 时，**curl 的 --max-time 是每次传输尝试的
#   墙钟上限**（计时器按次重置）⇒ Dockerfile 里按「剩余预算 ÷ 剩余尝试数」切出来的片**并不是本源
#   实耗上限**：实测切片 140s ⇒ 单源实耗 **426.5s**（= 3×140 + 2×3），把 420s 预算吃到 −6s。
#   同族缺陷在 **Maven JAR 的 5 处取数**里同样存在（`--retry 2 --retry-delay 3 --max-time N`）：
#   单源最坏 3N+6 秒；aws-sdk（N=600）单源最坏 **1806s**，**远超**外层 build-retry 的 per-attempt
#   挂起超时 600s ⇒ 取数段还没判红就被外层 SIGKILL 成 `context canceled`，面板上长得像编译失败。
#   缺陷之所以能合进来，是因为**仓内没有任何测试覆盖这几处记账**。
#
# 本测试做什么：
#   ① 从 Dockerfile 里**原样抽取** 5 处 Maven JAR 取数循环（复刻 BuildKit 的两条续行语义：
#      行尾反斜杠续行、续行里的整行注释被整行删除）⇒ 测的就是构建时真正跑的那段文本；
#   ② 用「虚拟时钟」桩替代 `date +%s`、用复刻 curl 重试语义的桩替代 `curl`：
#      桩不真的睡墙钟，而是把**名义耗时**推进虚拟时钟 ⇒ 秒级内复现真实时间线；
#   ③ 对 **5 处逐一**断言不变量：
#      · curl 收到的 --retry 为 0（重试职责交给**跨源冗余**）、带 --speed-limit/--speed-time；
#      · 每处的 --max-time 与改前**完全一致**（120/600/60/120/120，不改传输容差）；
#      · **本源实耗独立计时**：一源烧满 N 秒失败后，下一源的 [ok] 实耗仍是它自己的实耗
#        （不是累计值）⇒ 记账恒等；
#      · 首源失败时第二源**确实被尝试**（跨源冗余真的生效）；
#      · 诊断里能看到「本源墙钟上限」「本源实耗」「curl 退出码 + 释义」；
#      · 两源都失败 ⇒ 整个 RUN 段自己判红（exit 1 + [FATAL]），不是被外层 SIGKILL；
#   ④ 静态断言：全文旧形态 0 处 / 新形态 5 处 / `DOWNLOADED=0` 守卫 5 处。
#
# 不碰网络、不调 docker，CI 里跑它成本可忽略（挂进 `Bash Syntax Check` 作业的一个 step）。
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# 可选参数：指定被测 Dockerfile（默认取仓内当前版本）。给别的路径是为了做**变异验证**：
# 拿旧版本 Dockerfile 跑同一份测试，必须转红 ⇒ 证明这测试真的能抓住记账缺陷。
DOCKERFILE="${1:-${REPO_ROOT}/platform/batch-pipeline/docker/spark-cluster/Dockerfile}"

if [ ! -f "${DOCKERFILE}" ]; then
  echo "!! 找不到 Dockerfile：${DOCKERFILE}"
  exit 2
fi

WORK="$(mktemp -d)"
STUBDIR="${WORK}/stub"
OUTDIR="${WORK}/out"
LOOPDIR="${WORK}/loops"
mkdir -p "${STUBDIR}" "${OUTDIR}" "${LOOPDIR}"
trap 'rm -rf "${WORK}"' EXIT

# 5 处制品的期望形态：(展示名, --max-time 原值, 版本片段)
# 顺序必须与 Dockerfile 里取数循环出现的先后一致（hadoop-aws → aws-sdk → analytics → iceberg×2）。
EXPECT_NAME_1="hadoop-aws";          EXPECT_MT_1="120"
EXPECT_NAME_2="aws-sdk-v2-bundle";   EXPECT_MT_2="600"
EXPECT_NAME_3="analyticsaccelerator-s3"; EXPECT_MT_3="60"
EXPECT_NAME_4="iceberg-spark-runtime"; EXPECT_MT_4="120"
EXPECT_NAME_5="iceberg-aws-bundle";  EXPECT_MT_5="120"
# 各处取数循环里引用的 jar 名变量（抽取块必须自带它们的赋值行，见 T0b）
EXPECT_JARVAR_1="HADOOP_AWS_JAR"
EXPECT_JARVAR_2="AWS_SDK_JAR"
EXPECT_JARVAR_3="JAR_NAME"
EXPECT_JARVAR_4="ICEBERG_SPARK_JAR"
EXPECT_JARVAR_5="ICEBERG_AWS_JAR"

# ── ① 抽取器 A：5 处取数循环（逐个成文件） ───────────────────────────────────
# 复刻 BuildKit 的两条续行语义：
#   1) 行尾反斜杠 + 换行被去掉，其余（含下一行行首缩进）原样保留；
#   2) 续行里夹的**整行注释**会被整行删掉。
# 每处循环的边界：`DOWNLOADED=0;`（初始化，循环自洽需要它）→ `done;`（闭合）。
# ⚠️ 必须**连同紧邻在 DOWNLOADED=0; 之前的变量赋值行一起抽出来**（jar 名的定义就在那里）：
#    漏掉它 ⇒ 循环里引用的 ${XXX_JAR} 是未绑定变量 ⇒ 在 set -u 下直接退出（非零），
#    在不继承 set -u 时退化成空串（-o /tmp/ 根本写不进文件）⇒ 两种都是「测试看着绿、
#    其实没测到东西」的假绿风险。T0b 有专门的抽取器自证断言兜住这一点。
extract_loops() {  # extract_loops <Dockerfile> <输出目录>
  awk -v OUTDIR="$2" '
    { lines[NR] = $0 }
    END {
      n = 0
      for (i = 1; i <= NR; i++) {
        if (lines[i] !~ /for BASE_URL in/) continue
        s = i - 1; found = 0
        while (s > 0) { if (lines[s] ~ /DOWNLOADED=0;/) { found = 1; break } s-- }
        if (!found) { print "!! for 之前没有 DOWNLOADED=0 初始化" > "/dev/stderr"; exit 3 }
        # 往前吞掉紧邻的变量赋值行（jar 名定义），直到不再是赋值行为止
        while (s > 1 && lines[s-1] ~ /^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*="/) s--
        e = i
        while (e <= NR && lines[e] !~ /done;/) e++
        if (e > NR) { print "!! for 循环没有闭合 done" > "/dev/stderr"; exit 3 }
        buf = ""
        for (j = s; j <= e; j++) {
          t = lines[j]
          sub(/\\$/, "", t)
          buf = buf t
        }
        n++
        printf "%s\n", buf > sprintf("%s/loop_%d.sh", OUTDIR, n)
        printf "%s\n", "echo \"[RESULT] DOWNLOADED=${DOWNLOADED}\"" >> sprintf("%s/loop_%d.sh", OUTDIR, n)
      }
      if (n != 5) { print "!! 期望 5 个取数循环，实际 " n > "/dev/stderr"; exit 3 }
    }
  ' "$1"
}

# ── ② 抽取器 B：第一个含 MAVEN_MIRROR 的完整 RUN 段（截断到「供应链校验」） ────
# 用途：验证「两源都失败 ⇒ 段自己 exit 1 + [FATAL]」（完整段含 DOWNLOADED=0 守卫）。
extract_full_run() {  # extract_full_run <Dockerfile> <输出文件>
  awk '
    function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }
    { lines[NR] = $0 }
    END {
      i = 1
      while (i <= NR) {
        if (lines[i] ~ /^RUN /) {
          buf = ""; j = i; hit = 0
          while (j <= NR) {
            t = trim(lines[j])
            if (t ~ /^#/) {
              if (t ~ /供应链校验/) break
              j++; continue
            }
            if (lines[j] ~ /\\$/) { sub(/\\$/, "", lines[j]); buf = buf lines[j]; j++; continue }
            buf = buf lines[j]; j++; break
          }
          if (buf ~ /MAVEN_MIRROR/ && buf ~ /DOWNLOADED=0/) {
            sub(/^RUN[ \t]+/, "", buf)
            gsub(/[ \t]+$/, "", buf)
            if (buf !~ /;$/) buf = buf ";"
            printf "%s\n", buf
            printf "echo \"[RESULT] DOWNLOADED=${DOWNLOADED}\"\n"
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
  ' "$1" > "$2"
}

if ! extract_loops "${DOCKERFILE}" "${LOOPDIR}"; then
  echo "!! 没能在 Dockerfile 里定位到 5 处 Maven JAR 取数循环"
  exit 2
fi
if ! extract_full_run "${DOCKERFILE}" "${WORK}/full_run.sh"; then
  echo "!! 没能在 Dockerfile 里定位到含 MAVEN_MIRROR 的完整 RUN 段"
  exit 2
fi

# ── ③ 桩：虚拟时钟（date）+ 复刻 curl 重试语义（curl） ───────────────────────
cat >"${STUBDIR}/date" <<'STUB'
#!/usr/bin/env bash
# 虚拟时钟：只实现 `date +%s`（取数段仅用这一种调用）。
# 不自行推进；推进由 curl 桩按**名义耗时**完成 ⇒ 时间线完全确定、零真实等待。
cat "${STUB_CLOCK_FILE}"
STUB
chmod +x "${STUBDIR}/date"

cat >"${STUBDIR}/curl" <<'STUB'
#!/usr/bin/env bash
# 忠实复刻真实 curl 的关键语义（这是本测试的核心）：
#   · --max-time 是**每次传输尝试**的墙钟上限；
#   · 带 --retry N 时整个进程最坏跑 (N+1)×max-time + N×retry-delay；
#   · 每次尝试各自计时（重试不共享一次计时器）。
# 桩不真的睡墙钟，而是把名义耗时推进虚拟时钟 ⇒ 秒级复现真实时间线。
out=""; prev=""; max_time=0; retries=0; delay=0
for a in "$@"; do
  case "${prev}" in
    -o)           out="${a}" ;;
    --max-time)   max_time="${a}" ;;
    --retry)      retries="${a}" ;;
    --retry-delay) delay="${a}" ;;
  esac
  prev="${a}"
done
[ -n "${out}" ] && : > "${out}"
printf '%s\n' "$*" >> "${STUB_ARGV_LOG}"

n="$(cat "${STUB_CALL_FILE}" 2>/dev/null || echo 0)"
n=$((n + 1))
echo "${n}" > "${STUB_CALL_FILE}"

now="$(cat "${STUB_CLOCK_FILE}")"
if [ "${STUB_FAIL_TIMES:-0}" -gt 0 ] && [ "${n}" -le "${STUB_FAIL_TIMES}" ]; then
  # 失败：按真实 curl 语义推进**名义**耗时 = (1+重试次数)×max-time + 重试次数×delay
  adv=$(( (max_time) * (retries + 1) + delay * retries ))
  echo $(( now + adv )) > "${STUB_CLOCK_FILE}"
  exit "${STUB_FAIL_RC:-28}"
fi
adv="${STUB_OK_SECONDS:-1}"
echo $(( now + adv )) > "${STUB_CLOCK_FILE}"
[ -n "${out}" ] && printf 'FAKE-JAR' > "${out}"
exit 0
STUB
chmod +x "${STUBDIR}/curl"

# ── ④ 断言工具 ────────────────────────────────────────────────────────────────
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

# run_loop <用例名> <循环块> <环境赋值...>
run_loop() {
  name="$1"; shift
  script="$1"; shift
  log="${OUTDIR}/${name}.log"
  argvlog="${OUTDIR}/${name}.argv"
  clock="${OUTDIR}/${name}.clock"
  callfile="${OUTDIR}/${name}.calls"
  echo 0 > "${clock}"
  : > "${argvlog}"
  echo 0 > "${callfile}"
  ( export PATH="${STUBDIR}:${PATH}" \
           STUB_CLOCK_FILE="${clock}" \
           STUB_ARGV_LOG="${argvlog}" \
           STUB_CALL_FILE="${callfile}" \
           MAVEN_MIRROR="https://maven.aliyun.com/repository/central" \
           MAVEN_BASE="https://repo1.maven.org/maven2" \
           HADOOP_AWS_VERSION="3.5.0" \
           AWS_SDK_V2_BUNDLE_VERSION="2.20.162" \
           ANALYTICS_ACCELERATOR_VERSION="1.5.0" \
           ICEBERG_SPARK_MAJOR="4.1" \
           ICEBERG_SCALA_VERSION="2.13" \
           ICEBERG_VERSION="1.11.0" \
           "$@" ; \
    bash "${script}" ) >"${log}" 2>&1
  echo $?
}

# 从桩的参数日志里取某项参数值（取第一次出现）
argv_value() {  # argv_value <argv 日志> <参数名>
  awk -v key="$2" '
    { prev=""; for (i=1; i<=NF; i++) { if (prev==key) { print $i; exit } prev=$i } }
  ' "$1"
}

# ── ⑤ 静态断言（全文形态） ───────────────────────────────────────────────────
echo "== T0 静态形态：旧形态清零 / 新形态 5 处 / 守卫 5 处 =="
n_old="$(grep -c 'curl -fsSL --retry 2 --retry-delay 3' "${DOCKERFILE}" || true)"
check "旧形态（--retry 2 --retry-delay 3）出现次数" "0" "${n_old}"
n_new="$(grep -c 'curl -fsSL --retry 0 --retry-delay 0 --speed-limit 51200 --speed-time 30' "${DOCKERFILE}" || true)"
check "新形态出现次数" "5" "${n_new}"
n_guard="$(grep -c 'if \[ "\${DOWNLOADED}" = "0" \]; then' "${DOCKERFILE}" || true)"
check "DOWNLOADED=0 守卫出现次数" "5" "${n_guard}"

# T0b 抽取器自证：每个抽取出的循环块必须**自带** jar 名变量的赋值行。
# 若抽取器只取到「DOWNLOADED=0 + for 循环」而漏掉上一行的赋值行，
# 循环里引用的 ${XXX_JAR} 就是未绑定变量 ⇒ set -u 下直接退出、不继承 set -u 时退化成空串，
# 两种都是「测试看着绿、其实没测到东西」的假绿风险。这里把抽取器本身纳入断言。
echo "== T0b 抽取器自证：每个循环块自带 jar 名变量赋值 =="
i=1
while [ "${i}" -le 5 ]; do
  eval "JARVAR=\${EXPECT_JARVAR_${i}}"
  check_contains "[循环 ${i}] 块内含 ${JARVAR} 的赋值行" "${LOOPDIR}/loop_${i}.sh" "${JARVAR}=\""
  i=$((i + 1))
done

# ── ⑥ 场景 T1：5 处逐条 —— 快乐路径 + 参数形态 ───────────────────────────────
echo "== T1 5 处逐条：快乐路径 + curl 参数形态 =="
i=1
while [ "${i}" -le 5 ]; do
  eval "NAME=\${EXPECT_NAME_${i}}"
  eval "MT=\${EXPECT_MT_${i}}"
  rc="$(run_loop "t1_${i}" "${LOOPDIR}/loop_${i}.sh")"
  log="${OUTDIR}/t1_${i}.log"
  argvlog="${OUTDIR}/t1_${i}.argv"
  check "[${NAME}] 退出码" "0" "${rc}"
  check_contains "[${NAME}] 取数成功（DOWNLOADED=1）" "${log}" "[RESULT] DOWNLOADED=1"
  n_attempt="$(grep -c '^[[:space:]]*>>> \[attempt\]' "${log}" || true)"
  check "[${NAME}] attempt 次数（首源即成功 ⇒ 只 1 次）" "1" "${n_attempt}"
  check_contains "[${NAME}] attempt 诊断带制品名" "${log}" ">>> [attempt] ${NAME}"
  check_contains "[${NAME}] attempt 诊断带本源墙钟上限" "${log}" "本源墙钟上限 ${MT}s"
  check_contains "[${NAME}] attempt 诊断带 curl 内部重试次数" "${log}" "curl 内部重试 0 次"
  check_contains "[${NAME}] ok 诊断带本源实耗" "${log}" "本源实耗"
  check " [${NAME}] curl 收到的 --retry" "0" "$(argv_value "${argvlog}" --retry)"
  check " [${NAME}] curl 收到的 --retry-delay" "0" "$(argv_value "${argvlog}" --retry-delay)"
  check " [${NAME}] curl 收到的 --max-time（与改前一致）" "${MT}" "$(argv_value "${argvlog}" --max-time)"
  check_contains "[${NAME}] curl 带停滞检测 --speed-limit" "${argvlog}" "--speed-limit 51200"
  check_contains "[${NAME}] curl 带停滞检测 --speed-time" "${argvlog}" "--speed-time 30"
  i=$((i + 1))
done

# ── ⑦ 场景 T2：5 处逐条 —— 首源烧满上限失败 ⇒ 次源接管，且**本源实耗独立计时** ──
echo "== T2 5 处逐条：首源失败（烧满 N 秒）⇒ 次源接管；本源实耗不得累计 =="
i=1
while [ "${i}" -le 5 ]; do
  eval "NAME=\${EXPECT_NAME_${i}}"
  eval "MT=\${EXPECT_MT_${i}}"
  rc="$(run_loop "t2_${i}" "${LOOPDIR}/loop_${i}.sh" STUB_FAIL_TIMES=1 STUB_OK_SECONDS=7)"
  log="${OUTDIR}/t2_${i}.log"
  argvlog="${OUTDIR}/t2_${i}.argv"
  check "[${NAME}] 退出码" "0" "${rc}"
  check_contains "[${NAME}] 次源接管（DOWNLOADED=1）" "${log}" "[RESULT] DOWNLOADED=1"
  n_attempt="$(grep -c '^[[:space:]]*>>> \[attempt\]' "${log}" || true)"
  check "[${NAME}] attempt 次数（首源失败 ⇒ 2 次）" "2" "${n_attempt}"
  check_contains "[${NAME}] miss 诊断带 curl 退出码" "${log}" "curl 退出码 28"
  check_contains "[${NAME}] miss 诊断带释义" "${log}" "超时"
  # 记账恒等：首源实耗 == N（烧满上限），次源实耗 == 7（自己的实耗，不是累计）
  check_contains "[${NAME}] 首源实耗 = 标称上限 ${MT}s" "${log}" "本源实耗 ${MT}s"
  check_contains "[${NAME}] 次源实耗独立计时（7s，不是累计）" "${log}" "本源实耗 7s"
  # 两源的名义总实耗 = N + 7 ≤ 2N（记账不超上界）
  total=$(( MT + 7 ))
  check_le "[${NAME}] 两源名义总实耗 ≤ 2×标称上限" "$(( MT * 2 ))" "${total}"
  i=$((i + 1))
done

# ── ⑧ 场景 T3：两源都失败 ⇒ 完整 RUN 段自己判红（exit 1 + [FATAL]） ──────────
echo "== T3 两源都失败 ⇒ 段自己 exit 1 并报 [FATAL]（不被外层 SIGKILL 吃掉诊断） =="
rc="$(run_loop "t3" "${WORK}/full_run.sh" STUB_FAIL_TIMES=9 STUB_FAIL_RC=28)"
log="${OUTDIR}/t3.log"
check "退出码" "1" "${rc}"
check_contains "报 [FATAL]" "${log}" "[FATAL]"
check_contains "给出下载失败诊断" "${log}" "下载失败"

# ── ⑨ 汇总 ───────────────────────────────────────────────────────────────────
echo
echo "桩测结果：通过 ${pass} 项 / 失败 ${fail} 项"
[ "${fail}" -eq 0 ] || exit 1
exit 0
