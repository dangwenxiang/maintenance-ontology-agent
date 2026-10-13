const $ = id => document.getElementById(id);
let latest = null, toastTimer, runtimeEnabled = false, sessionId = null, activeRun = null;
const toolLabels = { get_device: '核对设备档案', search_cases: '查询已审核维修案例', search_documents: '检索适用资料' };
function el(tag, text, cls) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (cls) node.className = cls;
  return node;
}
function toast(message) {
  $('toast').textContent = message;
  $('toast').classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $('toast').classList.add('hidden'), 6000);
}
async function api(path, body, token) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers['X-Admin-Token'] = token;
  const response = await fetch('/api' + path, { method: body === undefined ? 'GET' : 'POST', headers,
    body: body === undefined ? undefined : JSON.stringify(body) });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || data.message || '请求失败');
  return data;
}
function table(target, heads, rows) {
  const wrap = el('div', undefined, 'table-wrap'), table = el('table'), header = el('tr');
  heads.forEach(x => header.append(el('th', x)));
  table.append(header);
  rows.forEach(row => { const tr = el('tr'); row.forEach(x => tr.append(el('td', x ?? '—'))); table.append(tr); });
  wrap.append(table);
  $(target).replaceChildren(wrap);
  if (!rows.length) $(target).append(el('p', '暂无记录', 'muted'));
}
const titles = { ask: '维修问答', cases: '设备与案例', knowledge: '知识与关系', review: '反馈审核' };
document.querySelectorAll('[data-page]').forEach(button => button.addEventListener('click', async () => {
  document.querySelectorAll('.page').forEach(n => n.classList.add('hidden'));
  $('page-' + button.dataset.page).classList.remove('hidden');
  document.querySelectorAll('[data-page]').forEach(n => n.classList.toggle('active', n === button));
  $('page-title').textContent = titles[button.dataset.page];
  try {
    if (button.dataset.page === 'cases') await loadCases();
    if (button.dataset.page === 'knowledge') await loadKnowledge();
  } catch (error) { toast(error.message); }
}));
document.querySelectorAll('[data-question]').forEach(button => button.addEventListener('click', () => {
  $('question').value = button.dataset.question;
  if (!$('device').value) $('device').value = 'D-DEMO-01';
  $('question').focus();
}));
async function loadSessions() {
  if (!runtimeEnabled) return;
  const sessions = await api('/sessions');
  const first = el('option', '新会话'); first.value = '';
  $('session-select').replaceChildren(first);
  for (const session of sessions) {
    const option = el('option', session.deviceId + ' / ' + new Date(session.createdAt).toLocaleString('zh-CN'));
    option.value = session.id; option.dataset.device = session.deviceId;
    $('session-select').append(option);
  }
  $('session-select').value = sessionId || '';
}
$('session-select').addEventListener('change', () => {
  sessionId = $('session-select').value || null;
  const option = $('session-select').selectedOptions[0];
  if (option.dataset.device) $('device').value = option.dataset.device;
  $('run-status').textContent = sessionId ? '将在已有会话中继续，保留DSH历史记录。' : '下一次提问会新建会话。';
});
$('new-session').addEventListener('click', () => {
  sessionId = null; $('session-select').value = '';
  $('run-status').textContent = '下一次提问会新建会话。已有会话仍保留。';
});
$('device').addEventListener('change', () => { sessionId = null; $('session-select').value = ''; });
async function init() {
  try {
    const [health, devices] = await Promise.all([api('/health'), api('/devices')]);
    runtimeEnabled = health.runtimeEnabled;
    $('mode').textContent = !runtimeEnabled ? '业务证据查询 · DSH未启用' : health.runtime.status !== 'ok'
      ? 'DSH暂不可用' : health.runtime.mode === 'demo' ? 'DSH · 演示模型' : 'DSH · 模型服务';
    devices.forEach(d => { const option = el('option', d.id + ' / ' + d.location + ' / ' + d.model); option.value = d.id; $('device').append(option); });
    $('device').value = 'D-DEMO-01';
    $('question').value = '同型号设备出现扭矩不合格，历史上确认过哪些原因？';
    await loadSessions();
  } catch (error) { $('mode').textContent = '连接失败'; toast(error.message); }
}
function showEvents(events) {
  for (const event of events) {
    let label;
    if (event.type === 'turn/start') label = 'DSH开始本轮查证';
    else if (event.type === 'tool/call') label = toolLabels[event.data?.name] || '执行只读工具';
    else if (event.type === 'tool/result') label = '工具已返回业务结果';
    else if (event.type === 'turn/end') label = 'DSH本轮执行已结束';
    if (label) $('run-events').append(el('div', label, 'run-event'));
  }
}
async function executeQuestion(question, deviceId) {
  if (!runtimeEnabled) return api('/ask', { question, deviceId });
  const submitted = await api('/runs', { question, deviceId, sessionId });
  activeRun = submitted.id; sessionId = submitted.sessionId;
  $('cancel-run').classList.remove('hidden'); $('cancel-run').disabled = false;
  $('run-events').replaceChildren(); $('run-events').classList.remove('hidden');
  let cursor = 0;
  const deadline = Date.now() + 140000;
  while (Date.now() < deadline) {
    const state = await api('/runs/' + activeRun + '?cursor=' + cursor);
    showEvents(state.events || []);
    for (const event of state.events || []) cursor = Math.max(cursor, event.cursor);
    $('run-status').textContent = { running: 'DSH正在查证，可取消当前任务。', cancelling: '正在等待本轮执行停止…', completed: '查证完成，会话与执行记录已保留。' }[state.status] || state.status;
    if (state.status === 'completed') { await loadSessions(); return state.response; }
    if (!['running', 'cancelling'].includes(state.status)) {
      await loadSessions();
      throw new Error(state.status === 'cancelled' ? '任务已取消，可以继续原会话或新建会话。' : state.error || 'DSH任务未完成');
    }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  await api('/runs/' + activeRun + '/cancel', {});
  throw new Error('执行超过等待时限，已请求取消。');
}
$('cancel-run').addEventListener('click', async () => {
  if (!activeRun) return;
  $('cancel-run').disabled = true;
  try { await api('/runs/' + activeRun + '/cancel', {}); } catch (error) { toast(error.message); }
});
function renderAnswer(answer) {
  $('answer-panel').classList.remove('hidden');
  $('answer-text').textContent = answer.answer;
  $('answer-mode').textContent = { evidence: '确定性查询', dsh_demo: 'DSH · 演示模型', dsh_model: 'DSH · 模型工具调用', dsh_evidence_fallback: 'DSH · 业务依据' }[answer.mode] || answer.mode;
  $('answer-notice').textContent = answer.notice || '';
  $('answer-notice').classList.toggle('hidden', !answer.notice);
  $('evidence-list').replaceChildren();
  for (const item of answer.cases || []) {
    const card = el('div', undefined, 'evidence');
    card.append(el('strong', item.cause), el('p', item.deviceId + ' / ' + item.model + ' · 已审核历史案例'), el('code', item.id));
    $('evidence-list').append(card);
  }
  for (const doc of answer.documents || []) {
    const card = el('div', undefined, 'evidence');
    card.append(el('strong', doc.title + ' · ' + doc.version), el('p', doc.section + ' / ' + doc.model + ' / ' + doc.configVersion), el('code', doc.id));
    $('evidence-list').append(card);
  }
  $('trace-list').replaceChildren();
  for (const step of answer.trace || []) {
    const row = el('div', undefined, 'trace');
    row.append(el('strong', step.step + ' · ' + (toolLabels[step.tool] || step.tool)), el('pre', JSON.stringify(step.result, null, 2)));
    $('trace-list').append(row);
  }
  $('feedback-open').classList.toggle('hidden', !answer.deviceId || !answer.phenomenon);
  $('feedback-form').classList.add('hidden');
}
$('ask-form').addEventListener('submit', async event => {
  event.preventDefault();
  const question = $('question').value, deviceId = $('device').value || null;
  $('ask-button').disabled = true; $('ask-button').textContent = '正在查证…';
  for (const id of ['device', 'new-session', 'session-select']) $(id).disabled = true;
  $('answer-panel').classList.add('hidden'); latest = null;
  try { latest = await executeQuestion(question, deviceId); renderAnswer(latest); }
  catch (error) { toast(error.message); $('run-status').textContent = error.message; }
  finally {
    activeRun = null; $('cancel-run').classList.add('hidden');
    for (const id of ['ask-button', 'device', 'new-session', 'session-select']) $(id).disabled = false;
    $('ask-button').replaceChildren(document.createTextNode('查找维修依据 '), el('span', '→'));
  }
});
$('feedback-open').addEventListener('click', () => $('feedback-form').classList.toggle('hidden'));
$('feedback-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true;
  try {
    const data = await api('/feedback', { traceId: latest.id, cause: $('cause').value, action: $('action').value, result: $('result').value });
    toast('已提交，等待审核。记录：' + data.caseId); $('feedback-form').reset(); $('feedback-form').classList.add('hidden');
  } catch (error) { toast(error.message); } finally { button.disabled = false; }
});
async function loadCases() {
  const [devices, cases] = await Promise.all([api('/devices'), api('/cases')]);
  table('devices-table', ['设备编号', '型号', '位置', '配置'], devices.map(d => [d.id, d.model, d.location, d.configVersion]));
  table('cases-table', ['案例', '设备', '现象', '已确认原因', '处理记录', '结果'], cases.map(c => [c.id, c.deviceId, c.phenomenon, c.cause, c.action, c.result]));
}
async function loadKnowledge() {
  const [ontology, docs] = await Promise.all([api('/ontology'), api('/documents')]);
  const chips = el('div', undefined, 'chips'); ontology.concepts.forEach(c => chips.append(el('span', c, 'chip')));
  $('ontology').replaceChildren(chips);
  ontology.relations.forEach(r => $('ontology').append(el('span', r.from + ' → ' + r.relation + ' → ' + r.to, 'relation')));
  $('ontology').append(el('p', ontology.note, 'muted')); $('documents').replaceChildren();
  docs.forEach(d => {
    const card = el('div', undefined, 'doc ' + (d.status === 'retired' ? 'retired' : ''));
    card.append(el('strong', d.title + ' · ' + d.version + ' / ' + (d.status === 'active' ? '有效' : '停用')),
      el('div', d.model + ' / 配置 ' + d.configVersion + ' / ' + d.section, 'muted'), el('p', d.content), el('small', d.id));
    $('documents').append(card);
  });
}
$('document-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true;
  try {
    await api('/documents', { title: $('doc-title').value, version: $('doc-version').value, model: $('doc-model').value,
      configVersion: $('doc-config').value, section: $('doc-section').value, content: $('doc-content').value }, $('doc-token').value);
    toast('资料已发布'); $('doc-token').value = ''; await loadKnowledge();
  } catch (error) { toast(error.message); } finally { button.disabled = false; }
});
async function loadPending() {
  const rows = await api('/review', undefined, $('admin-token').value); $('pending').replaceChildren();
  if (!rows.length) $('pending').append(el('p', '没有待审核记录', 'muted'));
  rows.forEach(item => {
    const card = el('div', undefined, 'pending-card');
    card.append(el('strong', item.deviceId + ' / ' + item.phenomenon), el('p', '实际原因：' + item.cause), el('p', '处理过程：' + item.action), el('p', '结果：' + item.result), el('small', item.id));
    for (const [label, approve] of [['审核通过', true], ['驳回', false]]) {
      const button = el('button', label, approve ? 'primary' : 'secondary');
      button.addEventListener('click', async () => {
        button.disabled = true;
        try { await api('/review/' + encodeURIComponent(item.id), { approve, reviewer: $('reviewer').value }, $('admin-token').value); toast(approve ? '已审核，正式查询现可使用该案例' : '已驳回'); await loadPending(); }
        catch (error) { toast(error.message); } finally { button.disabled = false; }
      });
      card.append(button);
    }
    $('pending').append(card);
  });
}
$('review-load').addEventListener('submit', async event => { event.preventDefault(); try { await loadPending(); } catch (error) { toast(error.message); } });
init();
