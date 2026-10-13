import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DshEngine } from '../src/engine.mjs';
import { DemoAdapter } from '../src/models.mjs';

const evidence = { deviceId: 'D-DEMO-01', status: 'answered', answer: '历史原因待现场核实 [C-1] [DOC-1]',
  cases: [{ id: 'C-1', cause: '合成原因' }], documents: [{ id: 'DOC-1', content: '合成说明' }], citations: ['C-1', 'DOC-1'], phenomenon: '扭矩不合格' };
const executeTool = async ({ name }) => ({ evidence, result: name === 'get_device' ? { id: 'D-DEMO-01' } : name === 'search_cases' ? evidence.cases : evidence.documents });
async function wait(engine, run, owner = 'local-demo') {
  for (let i = 0; i < 200; i++) {
    const value = engine.view(run.id, owner);
    if (!['running', 'cancelling'].includes(value.status)) return value;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error('Run timeout');
}
test('official DSH dispatches three tools, persists events, continues and resumes after restart', async () => {
  const root = await mkdtemp(join(tmpdir(), 'maintenance-dsh-'));
  let engine;
  try {
    engine = await new DshEngine({ root, executeTool }).start();
    const run = await engine.submit({ question: '扭矩不合格', deviceId: 'D-DEMO-01' });
    const done = await wait(engine, run);
    assert.equal(done.status, 'completed', JSON.stringify(done));
    assert.equal(done.response.kernel, 'dsh');
    assert.deepEqual(done.response.trace.map(t => t.tool), ['get_device', 'search_cases', 'search_documents']);
    assert.ok(done.events.some(e => e.type === 'tool/result'));
    assert.ok(done.events.some(e => e.type === 'turn/end'));
    assert.equal(done.response.mode, 'dsh_demo');
    assert.throws(() => engine.view(run.id, 'another-user'), /不存在/);
    await assert.rejects(engine.submit({ question: '继续', deviceId: 'D-DEMO-03', sessionId: run.sessionId }), /设备已改变/);
    const next = await engine.submit({ question: '继续核对', deviceId: 'D-DEMO-01', sessionId: run.sessionId });
    assert.equal((await wait(engine, next)).status, 'completed');
    await engine.close(); engine = null;
    engine = await new DshEngine({ root, executeTool }).start();
    const resumed = await engine.submit({ question: '继续查证', deviceId: 'D-DEMO-01', sessionId: run.sessionId });
    assert.equal((await wait(engine, resumed)).status, 'completed');
    assert.equal(engine.sessions('local-demo').length, 1);
    const state = JSON.parse(await readFile(join(root, 'host-state.json'), 'utf8'));
    assert.equal(Object.keys(state.runs).length, 3);
  } finally { if (engine) await engine.close(); await rm(root, { recursive: true, force: true }); }
});
test('cancellation drains the DSH turn and permits the next prompt', async () => {
  const root = await mkdtemp(join(tmpdir(), 'maintenance-dsh-cancel-'));
  const engine = await new DshEngine({ root, executeTool, adapter: new DemoAdapter({ delayMs: 200 }) }).start();
  try {
    const run = await engine.submit({ question: '扭矩不合格', deviceId: 'D-DEMO-01' });
    await engine.cancel(run.id, 'local-demo');
    assert.equal((await wait(engine, run)).status, 'cancelled');
    const next = await engine.submit({ question: '继续查证', deviceId: 'D-DEMO-01', sessionId: run.sessionId });
    assert.equal((await wait(engine, next)).status, 'completed');
  } finally { await engine.close(); await rm(root, { recursive: true, force: true }); }
});
test('a running session rejects competing submissions and step limits terminate tool loops', async () => {
  const root = await mkdtemp(join(tmpdir(), 'maintenance-dsh-admission-'));
  const engine = await new DshEngine({ root, executeTool, adapter: new DemoAdapter({ delayMs: 100 }), maxSteps: 1 }).start();
  try {
    const run = await engine.submit({ question: '扭矩不合格', deviceId: 'D-DEMO-01' });
    await assert.rejects(engine.submit({ question: '继续', deviceId: 'D-DEMO-01', sessionId: run.sessionId }), /正在执行/);
    const done = await wait(engine, run);
    assert.equal(done.status, 'failed');
    assert.ok(done.error);
    assert.equal(done.response, undefined);
  } finally { await engine.close(); await rm(root, { recursive: true, force: true }); }
});
test('missing business evidence uses the authoritative missing-data response', async () => {
  const root = await mkdtemp(join(tmpdir(), 'maintenance-dsh-no-evidence-'));
  const missing = { deviceId: 'D-DEMO-01', status: 'insufficient_evidence', answer: '没有适用资料，未生成维修步骤。', cases: [], documents: [], citations: [] };
  const engine = await new DshEngine({ root, executeTool: async () => ({ evidence: missing, result: [] }) }).start();
  try {
    const run = await engine.submit({ question: '设备异响', deviceId: 'D-DEMO-01' });
    const done = await wait(engine, run);
    assert.equal(done.status, 'completed'); assert.equal(done.response.answer, missing.answer);
    assert.deepEqual(done.response.citations, []);
  } finally { await engine.close(); await rm(root, { recursive: true, force: true }); }
});
