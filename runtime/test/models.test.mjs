import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { CompatibleAdapter, wireMessages } from '../src/models.mjs';

test('compatible adapter sends native tools and translates provider response to DSH chunks', async () => {
  let captured;
  const server = createServer(async (request, response) => {
    let body = ''; for await (const chunk of request) body += chunk;
    captured = JSON.parse(body);
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ choices: [{ message: { content: null, tool_calls: [{ id: 'call-test', type: 'function', function: { name: 'get_device', arguments: '{}' } }] }, finish_reason: 'tool_calls' }], usage: { prompt_tokens: 12, completion_tokens: 4 } }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const adapter = new CompatibleAdapter({ baseUrl: `http://127.0.0.1:${server.address().port}/v1`, model: 'test-model' });
    const chunks = [];
    for await (const chunk of adapter.stream({ provider: 'maintenance', model: 'test-model', messages: [{ role: 'user', content: [{ type: 'text', text: 'query' }] }], tools: [{ name: 'get_device', description: 'read only', parameters: { type: 'object', properties: {} } }] })) chunks.push(chunk);
    assert.equal(captured.tools[0].function.name, 'get_device');
    assert.equal(chunks.find(c => c.type === 'block-end').block.name, 'get_device');
    assert.equal(chunks.at(-2).type, 'usage'); assert.equal(chunks.at(-1).reason.kind, 'tool-calls');
  } finally { await new Promise(resolve => server.close(resolve)); }
});
test('tool outputs preserve call correlation in the compatible request', () => {
  const messages = wireMessages([{ role: 'tool', toolCallId: 'call-1', content: [{ type: 'text', text: '{"result":1}' }] }]);
  assert.equal(messages[0].tool_call_id, 'call-1'); assert.equal(messages[0].content, '{"result":1}');
});
