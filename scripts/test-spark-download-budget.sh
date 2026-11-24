#!/usr/bin/env bash
# Spark 取数「预算记账」回归测试（台账 #112 的 CI 治理线配套）
#
# 为什么需要它：
#   2026-11-15 CI 实测（PR #807 head e0a57673，`Docker Build Verify` job 132689363767，failed）
#   暴露了一个记账缺陷：取数段 curl 带 `--retry 2`，而 **curl 的 `--max-time` 是每次传输尝试的
#   墙钟上限**（带重试时整个进程最坏跑 (N+1)×max-time + N×delay）。于是 Dockerfile 里
#   「剩余预算 ÷ 剩余尝试数」切出来的片**并不是本源实耗上限**：实测切片 140s ⇒ 单源实耗
#   **426.5s**（= 3×140 + 2×3），把 420s 预算吃到 −6s，源 2/3 与回退版本全被跳过 ⇒
#   跨源冗余等于没生效。缺陷之所以能合进来，是因为**仓内没有任何测试覆盖这段记账**。
#
# 本测试做什么：
#   ① 从 Dockerfile 里**原样抽取**取数那段 RUN 的 shell 文本（复刻 BuildKit 的两条续行语义：
#      行尾反斜杠续行、续行里的整行注释被整行删除）⇒ 测的就是构建时真正跑的那段文本；
#   ② 用「虚拟时钟」桩替代 `date +%s`、用「忠实复刻 curl 重试语义」的桩替代 `curl`：
#      桩不真的睡墙钟，而是把**名义耗时**推进虚拟时钟 ⇒ 秒级内复现真实时间线；
#   ③ 断言取数阶段的不变量：
#      · 每个源的**名义实耗上限 == 该源切片**（记账不漂移）；
#      · 各源名义实耗之和 ≤ 总预算（预算不会被单源吃穿）；
#      · 源 2/源 3 **确实被尝试**（跨源冗余真的生效）；
#      · 诊断里能看到「本源实耗」「单次 curl 墙钟上限」；
#      · curl 收到的 --retry 为 0，且带停滞检测 --speed-limit/--speed-time；
#      · 变体：把 CURL_RETRIES 改回 2 时，切片必须自动摊薄成 1/(1+R)（记账公式与重试次数同步）。
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
mkdir -p "${STUBDIR}" "${OUTDIR}"
trap 'rm -rf "${WORK}"' EXIT

MIRRORS="https://mirrors.tuna.tsinghua.edu.cn/apache/spark,https://mirrors.aliyun.com/apache/spark,https://archive.apache.org/dist/spark"

# ── ① 抽取取数 RUN 的 shell 文本 ───────────────────────────────────────────────
# 复刻 BuildKit 的两条续行语义：
#   1) 行尾反斜杠 + 换行被去掉，其余（含下一行行首缩进）原样保留；
#   2) 续行里夹的**整行注释**会被整行删掉（仓内原 Dockerfile 就靠这个形态构建通过）。
# 截断点取「供应链校验」注释：其后的 sha512 校验与解包需要真实压缩包，桩测不覆盖。
extract_run() {  # extract_run <Dockerfile> <输出文件>
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
          if (buf ~ /TOTAL_BUDGET/ && buf ~ /SPARK_DOWNLOAD_BUDGET_SECONDS/) {
            sub(/^RUN[ \t]+/, "", buf)
            gsub(/[ \t]+$/, "", buf)
            if (buf !~ /;$/) buf = buf ";"
            printf "%s\n", buf
            printf "echo \"[RESULT] SPARK_VER=${SPARK_VER} DOWNLOADED=${DOWNLOADED}\"\n"
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

if ! extract_run "${DOCKERFILE}" "${WORK}/dl_run.sh"; then
  echo "!! 没能在 Dockerfile 里定位到取数 RUN 段"
  exit 2
fi

# ── ② 桩：虚拟时钟（date）+ 忠实复刻 curl 重试语义（curl） ───────────────────
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
url="${*: -1}"
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

now="$(cat "${STUB_CLOCK_FILE}")"
mode="${STUB_MODE:-ok}"
[ -n "${STUB_FAIL_PATTERN:-}" ] && case "${url}" in *"${STUB_FAIL_PATTERN}"*) exit 22 ;; esac

case "${mode}" in
  ok)
    # 成功：推进「下载耗时」（默认 1s，可用 STUB_OK_SECONDS 改）
    adv="${STUB_OK_SECONDS:-1}"
    echo $(( now + adv )) > "${STUB_CLOCK_FILE}"
    [ -n "${out}" ] && printf 'FAKE-TARBALL' > "${out}"
    exit 0
    ;;
  404)
    echo $(( now )) > "${STUB_CLOCK_FILE}"
    exit 22
    ;;
  reset)
    # 传输类失败：真实 curl 会把 stderr 原样写出；本测试要验证取数段**不**回显它
    echo "curl: (56) Recv failure: Connection reset by peer" >&2
    echo $(( now )) > "${STUB_CLOCK_FILE}"
    exit 56
    ;;
  timeout)
    # 超时：推进**名义**耗时 = (1+重试次数)×max-time + 重试次数×delay
    adv=$(( (max_time) * (retries + 1) + delay * retries ))
    echo $(( now + adv )) > "${STUB_CLOCK_FILE}"
    exit 28
    ;;
esac
exit 0
STUB
chmod +x "${STUBDIR}/curl"

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
  # SPARK_FORCE_FALLBACK 必须在环境里给值：抽取出来的取数段带 `set -u`，
  # 而 Dockerfile 的 ARG 在真实构建里由 BuildKit 注入 ⇒ 桩测侧必须显式补上，
  # 否则会因「未绑定变量」直接失败（不是被测逻辑的红）。缺省 0 = 生产行为。
  ( export PATH="${STUBDIR}:${PATH}" \
           STUB_CLOCK_FILE="${clock}" \
           STUB_ARGV_LOG="${argvlog}" \
           SPARK_MIRRORS="${MIRRORS}" \
           SPARK_FAST_MIRRORS=2 \
           SPARK_DOWNLOAD_BUDGET_SECONDS="${BUDGET:-420}" \
           SPARK_VERSION_PRIMARY=4.2.1 \
           SPARK_VERSION_FALLBACK=3.5.10 \
           SPARK_FORCE_FALLBACK=0 \
           "$@" ; \
    bash "${runscript}" ) >"${log}" 2>&1
  echo $?
}

# 从桩的参数日志里复算「名义实耗」：每次调用 (1+R)×max-time + R×delay
nominal_total() {  # nominal_total <argv 日志>
  awk '
    { mt=0; r=0; d=0; prev=""
      for (i=1; i<=NF; i++) {
        if (prev=="--max-time") mt=$i
        else if (prev=="--retry") r=$i
        else if (prev=="--retry-delay") d=$i
        prev=$i
      }
      total += (1+r)*mt + r*d
    }
    END { print total+0 }
  ' "$1"
}

# ── ④ 场景 ───────────────────────────────────────────────────────────────────
echo "== S1 快乐路径：第一个源成功 =="
rc="$(run_case s1 "${WORK}/dl_run.sh" STUB_MODE=ok)"
check "退出码" "0" "${rc}"
check_contains "取到了主版本" "${OUTDIR}/s1.log" "[RESULT] SPARK_VER=4.2.1 DOWNLOADED=1"
check_contains "只尝试了 1 个源" "${OUTDIR}/s1.log" "源1/3"
n_attempt="$(grep -c '^>>> \[attempt\]' "${OUTDIR}/s1.log" || true)"
check "attempt 次数（只数 echo 输出行，set -x 的回显行不算）" "1" "${n_attempt}"

echo "== S2 主版本三源全 404 ⇒ 回退版本取到 =="
rc="$(run_case s2 "${WORK}/dl_run.sh" STUB_MODE=ok STUB_FAIL_PATTERN=spark-4.2.1)"
check "退出码" "0" "${rc}"
check_contains "取到回退版本" "${OUTDIR}/s2.log" "[RESULT] SPARK_VER=3.5.10 DOWNLOADED=1"
check_contains "打印了主版本失败" "${OUTDIR}/s2.log" "个源均未取到"
n_attempt="$(grep -c '^>>> \[attempt\]' "${OUTDIR}/s2.log" || true)"
check "attempt 次数（主版本 3 + 回退版本 1）" "4" "${n_attempt}"

echo "== S3 传输类失败 ⇒ curl stderr 不回显（不误命中 build-retry 的传输签名） =="
rc="$(run_case s3 "${WORK}/dl_run.sh" STUB_MODE=reset)"
check "退出码" "1" "${rc}"
for sig in "Recv failure" "Connection reset by peer" "TLS handshake timeout" "no such host" "context canceled"; do
  check_not_contains "日志不含传输类签名：${sig}" "${OUTDIR}/s3.log" "${sig}"
done
check_contains "仍留下 curl 退出码供诊断" "${OUTDIR}/s3.log" "curl 退出码 56"

echo "== S4 预算耗尽 ⇒ 跳过回退版本并早失败 =="
# 预算 30s、每个源名义吃掉 6s ⇒ 主版本三源各切一片（slice 10/10/10）后预算余 0 < 30s
rc="$(BUDGET=30 run_case s4 "${WORK}/dl_run.sh" STUB_MODE=timeout)"
check "退出码" "1" "${rc}"
check_contains "明说跳过回退版本" "${OUTDIR}/s4.log" "跳过回退版本"
check_contains "给出预算耗尽的判定" "${OUTDIR}/s4.log" "取数阶段预算耗尽"
n_attempt="$(grep -c '^>>> \[attempt\]' "${OUTDIR}/s4.log" || true)"
check "attempt 次数（3 个源各切一片）" "3" "${n_attempt}"
check_contains "切片上限随剩余预算递减" "${OUTDIR}/s4.log" "本源上限 10s"

echo "== S5 源清单畸形（只有一个源）⇒ fail-closed =="
rc="$(run_case s5 "${WORK}/dl_run.sh" STUB_MODE=ok SPARK_MIRRORS=https://mirrors.tuna.tsinghua.edu.cn/apache/spark)"
check "退出码" "1" "${rc}"
check_contains "报源根数量不足" "${OUTDIR}/s5.log" "只解析出 1 个源根"

echo "== S6 回归：源慢且 curl 带内部重试 ⇒ 记账不许漂移、跨源冗余必须生效 =="
# 桩按真实 curl 语义推进名义耗时。缺陷形态（curl --retry 2 + --max-time=切片）：
#   首源名义耗 (1+2)×140 + 2×3 = 426s ⇒ 420s 预算被吃穿 ⇒ 源 2/3 与回退版本全被跳过。
# 正确形态（curl --retry 0 + --max-time=切片）：每个源恰好耗掉自己那一片 ⇒ 三源都试到。
rc="$(run_case s6 "${WORK}/dl_run.sh" STUB_MODE=timeout)"
check "退出码（预算耗尽 ⇒ 自己判红）" "1" "${rc}"
n_attempt="$(grep -c '^>>> \[attempt\]' "${OUTDIR}/s6.log" || true)"
check "三个源都被尝试（跨源冗余生效）" "3" "${n_attempt}"
nom="$(nominal_total "${OUTDIR}/s6.argv")"
check_le "各源名义实耗之和 ≤ 总预算（记账不漂移）" "420" "${nom}"
check_contains "诊断给出本源实耗" "${OUTDIR}/s6.log" "本源实耗"
check_contains "诊断给出单次 curl 墙钟上限" "${OUTDIR}/s6.log" "单次 curl 墙钟上限"
retry_used="$(awk '{for(i=1;i<=NF;i++) if($i=="--retry") print $(i+1)}' "${OUTDIR}/s6.argv" | head -1)"
check "curl 收到的 --retry 次数（重试职责交给跨源冗余）" "0" "${retry_used}"
check_contains "curl 带停滞检测 --speed-limit" "${OUTDIR}/s6.argv" "--speed-limit 51200"
check_contains "curl 带停滞检测 --speed-time" "${OUTDIR}/s6.argv" "--speed-time 30"

echo "== S7 变体：把 CURL_RETRIES 改回 2 ⇒ 切片必须自动摊薄（记账公式与重试次数同步） =="
# 抽取后的取数文本是**拼成一行**的（BuildKit 续行语义：反斜杠+换行被去掉），故按子串替换。
sed 's/CURL_RETRIES=0;/CURL_RETRIES=2;/' "${WORK}/dl_run.sh" > "${WORK}/dl_run_r2.sh"
if cmp -s "${WORK}/dl_run.sh" "${WORK}/dl_run_r2.sh"; then
  fail=$((fail + 1)); printf '  ✗ 没能在抽取文本里定位到 CURL_RETRIES=0（变体没生效）\n'
else
  rc="$(run_case s7 "${WORK}/dl_run_r2.sh" STUB_MODE=timeout)"
  n_attempt="$(grep -c '^>>> \[attempt\]' "${OUTDIR}/s7.log" || true)"
  check "变体下三个源仍都被尝试" "3" "${n_attempt}"
  nom="$(nominal_total "${OUTDIR}/s7.argv")"
  check_le "变体下名义实耗之和仍 ≤ 总预算" "420" "${nom}"
  check_contains "变体下单次 curl 墙钟上限被摊薄（切片 ÷ (1+重试次数)）" "${OUTDIR}/s7.log" "单次 curl 墙钟上限 46s"
fi

echo "== S8 强制主版本失效（SPARK_FORCE_FALLBACK=1）⇒ 主版本一次都不试，直接回退 =="
# 演练开关（CI 的端到端演练用）：SPARK_FORCE_FALLBACK=1 ⇒ 取数段**跳过主版本**，
# 一个源都不试，直接走回退版本这条路径。本场景在**桩层**证明那个开关的语义正确：
# 主版本的取数尝试次数必须为 0（不是「试了但失败」），回退版本必须被取到。
rc="$(run_case s8 "${WORK}/dl_run.sh" STUB_MODE=ok SPARK_FORCE_FALLBACK=1)"
check "退出码" "0" "${rc}"
check_contains "打印了演练标记" "${OUTDIR}/s8.log" "[DRILL] 强制主版本失效"
check_contains "取到回退版本" "${OUTDIR}/s8.log" "[RESULT] SPARK_VER=3.5.10 DOWNLOADED=1"
n_attempt_primary="$(grep -c '^\+ >>> \[attempt\] Spark 4\.2\.1' "${OUTDIR}/s8.log" || true)"
check "主版本取数尝试次数（开关命中时必须为 0）" "0" "${n_attempt_primary}"
n_attempt_all="$(grep -c '^>>> \[attempt\]' "${OUTDIR}/s8.log" || true)"
check "总 attempt 次数（只试回退版本的第一个源）" "1" "${n_attempt_all}"
check_not_contains "没有打印主版本均未取到（主版本压根没试）" "${OUTDIR}/s8.log" "个源均未取到"

echo
echo "桩测结果：通过 ${pass} 项 / 失败 ${fail} 项"
[ "${fail}" -eq 0 ] || exit 1
exit 0
