import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { resolve } from 'node:path';
import { DshEngine, DSH_VERSION, RuntimeError } from './engine.mjs';

const token = process.env.DSH_RUNTIME_TOKEN, toolToken = process.env.DSH_TOOL_TOKEN;
if (!token || !toolToken) throw new Error('DSH service tokens are required; use scripts/dev.py');
const backend = process.env.DSH_BACKEND_URL || 'http://127.0.0.1:8087';
const engine = await new DshEngine({
  root: process.env.DSH_DATA_DIR || resolve('data'),
  executeTool: async (request, signal) => {
    const response = await fetch(backend + '/internal/dsh/tools', {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-DSH-Tool-Token': toolToken },
      body: JSON.stringify(request), signal: AbortSignal.any([signal, AbortSignal.timeout(10000)]),
    });
    if (!response.ok) throw new Error(`Business tool failed (${response.status})`);
    return response.json();
  },
}).start();
function authorized(request) {
  const supplied = Buffer.from(request.headers.authorization || '');
  const expected = Buffer.from('Bearer ' + token);
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}
async function body(request) {
  let size = 0, chunks = [];
  for await (const chunk of request) {
    size += chunk.length; if (size > 32000) throw new RuntimeError('Request too large', 413); chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new RuntimeError('Invalid JSON'); }
}
const server = createServer(async (request, response) => {
  response.setHeader('Content-Type', 'application/json; charset=utf-8'); response.setHeader('Cache-Control', 'no-store');
  try {
    if (!authorized(request)) throw new RuntimeError('Unauthorized', 401);
    const url = new URL(request.url, 'http://runtime.local'); let value;
    if (request.method === 'GET' && url.pathname === '/v1/health')
      value = { status: 'ok', kernel: 'dsh', version: DSH_VERSION, mode: engine.mode, model: engine.modelName };
    else if (request.method === 'GET' && url.pathname === '/v1/sessions') value = engine.sessions('local-demo');
    else if (request.method === 'POST' && url.pathname === '/v1/runs') {
      const input = await body(request);
      value = await engine.submit({ question: input.question, deviceId: input.deviceId, sessionId: input.sessionId, ownerId: 'local-demo' }); response.statusCode = 202;
    } else {
      const match = /^\/v1\/runs\/(Q-[a-f0-9-]+)(\/cancel)?$/.exec(url.pathname);
      if (!match) throw new RuntimeError('Not found', 404);
      if (request.method === 'GET' && !match[2]) {
        const cursor = Number(url.searchParams.get('cursor') || 0);
        if (!Number.isSafeInteger(cursor) || cursor < 0) throw new RuntimeError('Invalid cursor'); value = engine.view(match[1], 'local-demo', cursor);
      } else if (request.method === 'POST' && match[2]) value = await engine.cancel(match[1], 'local-demo');
      else throw new RuntimeError('Not found', 404);
    }
    response.end(JSON.stringify(value));
  } catch (error) {
    response.statusCode = error instanceof RuntimeError ? error.status : 503;
    response.end(JSON.stringify({ error: error instanceof RuntimeError ? error.message : 'DSH runtime request failed' }));
  }
});
server.listen(Number(process.env.DSH_PORT || 8117), '127.0.0.1', () => console.log(`DSH ${DSH_VERSION} ready on loopback (${engine.mode})`));
async function shutdown() { server.close(); await engine.close(); process.exit(0); }
process.on('SIGTERM', shutdown); process.on('SIGINT', shutdown);
