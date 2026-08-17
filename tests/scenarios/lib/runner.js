/**
 * 场景验证运行器。
 *
 * 提供统一的场景执行框架，输出结构化结果，便于汇总报告生成。
 */

const fs = require('fs');
const path = require('path');
const { Logger } = require('./api-client');

/**
 * 单个场景步骤。
 * @typedef {Object} Step
 * @property {string} name - 步骤名
 * @property {string} description - 步骤描述
 * @property {boolean} requiresCluster - 是否需要完整 K8s 集群
 * @property {function} run - async (ctx) => StepResult
 */

/**
 * 运行场景。
 * @param {object} scenario - { name, description, steps: Step[] }
 * @param {object} ctx - 共享上下文（如 ApiClient）
 * @returns {Promise<object>} 场景结果
 */
async function runScenario(scenario, ctx) {
  const log = new Logger(`[${scenario.name}]`);
  log.info(`开始场景: ${scenario.description}`);
  const startTs = Date.now();
  const results = [];
  let passCount = 0, failCount = 0, skipCount = 0;

  for (let i = 0; i < scenario.steps.length; i++) {
    const step = scenario.steps[i];
    const stepStart = Date.now();
    log.step(`(${i + 1}/${scenario.steps.length}) ${step.name}`);

    // 需要集群但当前环境无集群时跳过实际执行
    if (step.requiresCluster && !ctx.clusterAvailable) {
      log.warn(`  → SKIP（需完整 K8s 集群环境）: ${step.description || ''}`);
      skipCount++;
      results.push({
        index: i + 1,
        name: step.name,
        description: step.description || '',
        status: 'SKIP',
        reason: '需完整 K8s 集群环境（Spark/Flink/Doris/Kafka/Iceberg）',
        durationMs: Date.now() - stepStart,
        checks: [],
      });
      continue;
    }

    try {
      const res = await step.run(ctx);
      const durationMs = Date.now() - stepStart;
      const status = res.status || (res.assert && res.assert.failed === 0 ? 'PASS' : 'FAIL');
      if (status === 'PASS') {
        passCount++;
        log.info(`  → PASS (${durationMs}ms)`);
      } else if (status === 'SKIP') {
        skipCount++;
        log.warn(`  → SKIP: ${res.reason || ''}`);
      } else {
        failCount++;
        log.error(`  → FAIL (${durationMs}ms): ${res.reason || ''}`);
      }
      results.push({
        index: i + 1,
        name: step.name,
        description: step.description || '',
        status,
        reason: res.reason || '',
        durationMs,
        checks: res.checks || (res.assert ? res.assert.records : []),
        summary: res.summary || (res.assert ? res.assert.summary() : null),
      });
    } catch (err) {
      const durationMs = Date.now() - stepStart;
      failCount++;
      log.error(`  → ERROR (${durationMs}ms): ${err && err.message || err}`);
      results.push({
        index: i + 1,
        name: step.name,
        description: step.description || '',
        status: 'ERROR',
        reason: String(err && err.message || err),
        durationMs,
        checks: [],
      });
    }
  }

  const durationMs = Date.now() - startTs;
  const summary = {
    scenario: scenario.name,
    description: scenario.description,
    industry: scenario.industry || '',
    totalSteps: scenario.steps.length,
    passed: passCount,
    failed: failCount,
    skipped: skipCount,
    durationMs,
    overall: failCount === 0 ? (passCount > 0 ? 'PASS' : 'SKIP') : 'FAIL',
  };

  log.info(`场景结束: ${summary.overall} (pass=${passCount} fail=${failCount} skip=${skipCount} duration=${durationMs}ms)`);

  return { summary, steps: results };
}

/**
 * 将场景结果写入 JSON 文件。
 */
function saveResult(result, filePath) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, JSON.stringify(result, null, 2), 'utf8');
}

module.exports = { runScenario, saveResult };