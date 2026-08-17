/**
 * 安全测试总运行器 —— 依次执行所有测试套件并汇总
 *
 * 用法：
 *   node run-all.js              # 控制台输出
 *   node run-all.js --json       # 输出 JSON 到 reports/
 */

const fs = require('fs');
const path = require('path');

const suites = [
  { name: 'P0 安全机制验证',     file: './p0-security-mechanisms.test.js' },
  { name: '被动安全扫描',        file: './passive-scan.test.js' },
  { name: '认证安全测试',        file: './auth-security.test.js' },
  { name: '注入安全测试',        file: './injection-tests.test.js' },
  { name: 'CSRF 安全测试',       file: './csrf-tests.test.js' },
  { name: '访问控制安全测试',    file: './access-control-tests.test.js' },
];

async function main() {
  const jsonMode = process.argv.includes('--json');
  const results = [];
  const startTime = Date.now();

  console.log('╔════════════════════════════════════════════════════════════╗');
  console.log('║   DataEngineBDP 安全测试套件 - OWASP Top 10 + 等保三级     ║');
  console.log('╚════════════════════════════════════════════════════════════╝');
  console.log(`开始时间: ${new Date().toISOString()}`);
  console.log(`目标: ${process.env.BASE_URL || 'http://localhost:18086'}\n`);

  for (const suite of suites) {
    console.log(`\n━━━ ${suite.name} ━━━`);
    try {
      const fn = require(suite.file);
      const summary = await fn();
      results.push(summary);
    } catch (e) {
      console.error(`  💥 套件崩溃: ${e.message}`);
      results.push({
        name: suite.name,
        pass: 0, fail: 1, warn: 0, total: 1,
        results: [{ label: '套件执行', status: 'FAIL', error: e.message }],
      });
    }
  }

  const durationMs = Date.now() - startTime;

  // 汇总
  const totalPass = results.reduce((s, r) => s + r.pass, 0);
  const totalFail = results.reduce((s, r) => s + r.fail, 0);
  const totalWarn = results.reduce((s, r) => s + r.warn, 0);
  const total = totalPass + totalFail + totalWarn;

  console.log('\n╔════════════════════════════════════════════════════════════╗');
  console.log('║                       总体汇总                              ║');
  console.log('╠════════════════════════════════════════════════════════════╣');
  for (const r of results) {
    const line = `║ ${r.name.padEnd(20)} | PASS: ${String(r.pass).padStart(3)} | FAIL: ${String(r.fail).padStart(3)} | WARN: ${String(r.warn).padStart(3)} ║`;
    console.log(line);
  }
  console.log('╠════════════════════════════════════════════════════════════╣');
  console.log(`║ 总计: ${total}  PASS: ${totalPass}  FAIL: ${totalFail}  WARN: ${totalWarn}  耗时: ${durationMs}ms`.padEnd(62) + ' ║');
  console.log('╚════════════════════════════════════════════════════════════╝');

  if (totalFail > 0) {
    console.log('\n❌ 存在 FAIL 项，请检查上述日志');
  } else if (totalWarn > 0) {
    console.log('\n⚠️  存在 WARN 项，建议优化');
  } else {
    console.log('\n✅ 所有测试通过');
  }

  // 输出 JSON
  if (jsonMode) {
    const reportDir = path.join(__dirname, '..', 'reports');
    if (!fs.existsSync(reportDir)) fs.mkdirSync(reportDir, { recursive: true });
    const ts = new Date().toISOString().replace(/[:.]/g, '-');
    const outFile = path.join(reportDir, `api-security-${ts}.json`);
    const report = {
      timestamp: new Date().toISOString(),
      target: process.env.BASE_URL || 'http://localhost:18086',
      durationMs,
      summary: { total, pass: totalPass, fail: totalFail, warn: totalWarn },
      suites: results,
    };
    fs.writeFileSync(outFile, JSON.stringify(report, null, 2));
    console.log(`\nJSON 报告: ${outFile}`);
  }

  // 退出码：有 FAIL 返回 1
  process.exit(totalFail > 0 ? 1 : 0);
}

main().catch((e) => {
  console.error('FATAL:', e);
  process.exit(1);
});