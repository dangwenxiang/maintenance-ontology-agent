import { LlmAdapter, ToolCallId } from '@deepseek-ai/dsh-llm';
import { randomUUID } from 'node:crypto';

function textChunks(text) {
  return [
    { type: 'block-start', index: 0, blockType: 'text' },
    { type: 'text-delta', index: 0, text },
    { type: 'block-end', index: 0, block: { type: 'text', text } },
  ];
}
function callChunks(call, index = 0) {
  const id = ToolCallId(call.id);
  return [
    { type: 'block-start', index, blockType: 'tool-call' },
    { type: 'tool-call-delta', index, id, name: call.name, argumentsDelta: call.arguments },
    { type: 'block-end', index, block: { type: 'tool-call', id, name: call.name, arguments: call.arguments } },
  ];
}
const textOf = message => message.content?.filter(b => b.type === 'text').map(b => b.text).join('\n') || '';

// A model fixture only. The agent loop, tool dispatch, cancellation and durable session are official DSH.
export class DemoAdapter extends LlmAdapter {
  constructor({ delayMs = 30 } = {}) { super(); this.delayMs = delayMs; }
  async resolveModel(provider, model) { return { provider, id: model, name: '合成数据演示适配器' }; }
  async *stream(options) {
    await new Promise((resolve, reject) => {
      if (options.signal?.aborted) return reject(options.signal.reason);
      const abort = () => { clearTimeout(timer); reject(options.signal.reason); };
      const timer = setTimeout(() => { options.signal?.removeEventListener('abort', abort); resolve(); }, this.delayMs);
      options.signal?.addEventListener('abort', abort, { once: true });
    });
    const userIndex = options.messages.findLastIndex(m => m.role === 'user' && m.source?.kind === 'user');
    const tail = options.messages.slice(userIndex + 1);
    const calls = tail.filter(m => m.role === 'assistant').flatMap(m => m.content.filter(b => b.type === 'tool-call'));
    const next = ['get_device', 'search_cases', 'search_documents'].find(name => !calls.some(c => c.name === name));
    if (next) {
      for (const chunk of callChunks({ id: randomUUID(), name: next, arguments: '{}' })) yield chunk;
    } else {
      const results = tail.filter(m => m.role === 'tool').map(m => {
        try { return JSON.parse(textOf(m)); } catch { return {}; }
      });
      const receipt = results.findLast(r => r.evidence)?.evidence;
      const final = JSON.stringify({ answer: receipt?.answer || '没有可核对的工具结果。', citations: receipt?.citations || [] });
      for (const chunk of textChunks(final)) yield chunk;
    }
    yield { type: 'usage', usage: { inputTokens: 0, outputTokens: 0 } };
    yield { type: 'finish', reason: { kind: next ? 'tool-calls' : 'stop' } };
  }
}

export function wireMessages(messages, system) {
  const result = system ? [{ role: 'system', content: system }] : [];
  for (const message of messages) {
    if (message.role === 'developer') continue; // Current tool inventory is sent in tools, not as DSH internal updates.
    if (message.content.some(b => !['text', 'tool-call', 'reasoning'].includes(b.type)))
      throw new Error('This adapter accepts text and native tool calls only');
    if (message.role === 'tool') {
      result.push({ role: 'tool', tool_call_id: message.toolCallId, content: textOf(message) });
    } else {
      const calls = message.content.filter(b => b.type === 'tool-call');
      const row = { role: message.role, content: textOf(message) || (calls.length ? null : '') };
      if (calls.length) row.tool_calls = calls.map(b => ({ id: b.id, type: 'function', function: { name: b.name, arguments: b.arguments } }));
      result.push(row);
    }
  }
  return result;
}

// A protocol adapter, not an agent loop. DSH owns request/step/tool/session lifecycle.
export class CompatibleAdapter extends LlmAdapter {
  constructor({ baseUrl, apiKey = '', model }) {
    super(); this.baseUrl = baseUrl.replace(/\/+$/, ''); this.apiKey = apiKey; this.model = model;
    const url = new URL(this.baseUrl);
    if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Invalid model URL');
  }
  async resolveModel(provider, model) { return { provider, id: model, name: model }; }
  async *stream(options) {
    if (options.reasoningEffort) throw new Error('Reasoning effort is not supported by this adapter');
    const body = { model: options.model, messages: wireMessages(options.messages, options.system), stream: false,
      temperature: options.temperature ?? 0, max_tokens: options.maxTokens ?? 1800 };
    if (options.stop) body.stop = options.stop;
    if (options.tools?.length) body.tools = options.tools.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } }));
    const response = await fetch(this.baseUrl + '/chat/completions', {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...(this.apiKey ? { Authorization: `Bearer ${this.apiKey}` } : {}) },
      body: JSON.stringify(body), signal: AbortSignal.any([options.signal || new AbortController().signal, AbortSignal.timeout(30000)]),
    });
    if (!response.ok) throw new Error(`Model request failed (${response.status})`);
    const payload = await response.json(), choice = payload.choices?.[0];
    if (!choice?.message) throw new Error('Invalid model response');
    let index = 0;
    if (typeof choice.message.content === 'string' && choice.message.content.length) {
      for (const chunk of textChunks(choice.message.content)) yield chunk;
      index++;
    }
    for (const call of choice.message.tool_calls || []) {
      if (typeof call.function?.arguments !== 'string') throw new Error('Invalid tool arguments');
      for (const chunk of callChunks({ id: call.id, name: call.function.name, arguments: call.function.arguments }, index++)) yield chunk;
    }
    yield { type: 'usage', usage: { inputTokens: payload.usage?.prompt_tokens || 0, outputTokens: payload.usage?.completion_tokens || 0 } };
    yield { type: 'finish', reason: { kind: choice.message.tool_calls?.length ? 'tool-calls' : choice.finish_reason === 'length' ? 'max-tokens' : 'stop' } };
  }
}
