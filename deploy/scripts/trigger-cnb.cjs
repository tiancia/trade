#!/usr/bin/env node
'use strict';

// Only small control data crosses GitHub -> CNB. Images and registry/SSH secrets stay in CNB.
// API schema: https://api.cnb.cool/swagger.json, StartBuild / dto.BuildResult.
const fs = require('node:fs');

const ENDPOINT = 'https://api.cnb.cool/tiancia/trade-ci/-/build/start';
const LOG_PREFIX = 'https://cnb.cool/tiancia/trade-ci/-/build/logs/';
const SERVICES = new Set(['all', 'backend', 'cockpit', 'x-console', 'frontends', 'egress']);
const MODES = new Set(['build', 'build_deploy', 'deploy', 'rollback']);

class TriggerError extends Error {}
const fail = (message) => { throw new TriggerError(message); };

function buildRequest(env) {
  if (env.GITHUB_REPOSITORY !== 'tiancia/trade' || env.GITHUB_REF !== 'refs/heads/main') {
    fail('Only the tiancia/trade main branch may submit this task.');
  }
  if (env.GITHUB_EVENT_NAME !== 'workflow_dispatch') {
    fail('Only manual workflow runs may submit this task.');
  }
  if (!/^[a-f0-9]{40}$/.test(env.GITHUB_SHA || '')) fail('GITHUB_SHA must be a complete commit SHA.');
  if (!/^[1-9][0-9]{0,19}$/.test(env.GITHUB_RUN_ID || '')) fail('GITHUB_RUN_ID must be a positive run number.');

  const service = env.SERVICE;
  const mode = env.MODE;
  if (!SERVICES.has(service)) fail('Select a supported service.');
  if (!MODES.has(mode)) fail('Select a supported mode.');
  const build = ['build', 'build_deploy'].includes(mode) ? 'true' : 'false';
  const deploy = mode === 'build' ? 'false' : 'true';
  const action = mode === 'rollback' ? 'rollback' : 'release';
  const manifest = mode === 'rollback' ? '' : (env.EXISTING_RELEASE_MANIFEST || '');
  if (typeof manifest !== 'string' || Buffer.byteLength(manifest, 'utf8') > 16384 || manifest.includes('\0')) {
    fail('The release manifest must be plain text of at most 16 KiB.');
  }
  if (mode === 'deploy' && !manifest.trim()) fail('Deploy-only runs require a complete release.env manifest.');
  if (mode !== 'deploy' && manifest.trim()) fail('A historical manifest is only accepted in deploy mode.');

  return {
    branch: 'main',
    event: 'api_trigger_trade',
    sync: 'false',
    title: `GitHub ${env.GITHUB_RUN_ID}: ${mode} ${service} ${env.GITHUB_SHA.slice(0, 12)}`,
    env: {
      SOURCE_SHA: env.GITHUB_SHA,
      CI_RUN_ID: env.GITHUB_RUN_ID,
      SERVICE: service,
      BUILD_REQUESTED: build,
      DEPLOY_REQUESTED: deploy,
      CI_ACTION: action,
      EXISTING_RELEASE_MANIFEST: manifest,
      DEPLOY_ROOT: '/opt/trade',
      ACCESS_MODE: 'private',
    },
  };
}

async function triggerCnb({ env = process.env, fetchImpl = globalThis.fetch, timeoutMs = 30000 } = {}) {
  const payload = buildRequest(env);
  const token = env.CNB_TOKEN;
  if (typeof token !== 'string' || !token || /\s/.test(token)) {
    fail('Configure the CNB_TOKEN GitHub secret with access to the CI control repository.');
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    let response;
    try {
      response = await fetchImpl(ENDPOINT, {
        method: 'POST',
        redirect: 'error',
        signal: controller.signal,
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: 'application/vnd.cnb.api+json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      });
    } catch {
      // A timed-out POST may already have queued a task. Do not retry it automatically.
      fail('No CNB trigger response was received; inspect the CNB run list before retrying.');
    }
    if (!response.ok) {
      // Error bodies and transport exceptions may contain credentials or request details.
      const status = Number.isInteger(response.status) ? response.status : 'unknown';
      fail(`CNB rejected the trigger request (HTTP ${status}).`);
    }
    let result;
    try { result = await response.json(); } catch { fail('CNB returned an unreadable trigger response.'); }
    if (!result || result.success !== true) fail('CNB did not confirm that the task was submitted.');
    if (typeof result.sn !== 'string' || !/^cnb-[A-Za-z0-9-]{1,80}$/.test(result.sn)
      || result.buildLogUrl !== LOG_PREFIX + result.sn) {
      fail('CNB returned an unexpected task reference; inspect the CNB run list before retrying.');
    }
    return { sn: result.sn, buildLogUrl: result.buildLogUrl, payload };
  } finally {
    clearTimeout(timer);
  }
}

async function main({ env = process.env, fetchImpl = globalThis.fetch, log = console.log, error = console.error } = {}) {
  try {
    const result = await triggerCnb({ env, fetchImpl });
    log(`CNB 任务已提交：${result.sn}`);
    log(result.buildLogUrl);
    log('This result confirms submission only. Check CNB for the build and deployment result.');
    if (env.GITHUB_STEP_SUMMARY) {
      try {
        fs.appendFileSync(env.GITHUB_STEP_SUMMARY,
          `### CNB task submitted\n\n[View task](${result.buildLogUrl})\n\n`
          + `Source: \`${result.payload.env.SOURCE_SHA}\`\n\n`
          + `Services: \`${result.payload.env.SERVICE}\`\n\n`
          + 'This GitHub job confirms submission only; the build and deployment result is available in CNB.\n');
      } catch {
        error('Warning: the task was submitted, but its GitHub summary could not be written.');
      }
    }
    return 0;
  } catch (cause) {
    error('Error: ' + (cause instanceof TriggerError ? cause.message : 'Unexpected local trigger failure.'));
    return 1;
  }
}

module.exports = { buildRequest, triggerCnb, main };
if (require.main === module) main().then((code) => { process.exitCode = code; });
