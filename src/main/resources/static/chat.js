const byId = id => document.getElementById(id);
const messages = byId('messages');
const input = byId('message');
const streamPath = '/directchat/stream';
let sessionId = crypto.randomUUID();
let busy = false;
let productPending = false;

function bubble(text, role) {
  const el = document.createElement('div');
  el.className = `bubble ${role}`;
  el.textContent = text;
  messages.append(el);
  messages.scrollTop = messages.scrollHeight;
  return el;
}

function display(content, answer) {
  let data;
  try { data = JSON.parse(content); } catch { answer.append(document.createTextNode(content)); return; }
  if (data?.status === 'AWAITING_INPUT') {
    answer.textContent = data.question;
    const choices = document.createElement('div');
    choices.className = 'choices';
    for (const choice of data.options ?? []) {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = choice;
      button.onclick = () => submit(choice);
      choices.append(button);
    }
    answer.append(choices);
  } else if (Array.isArray(data) && data.length && data.every(row => row && typeof row === 'object' && !Array.isArray(row))) {
    const table = document.createElement('table');
    const keys = Object.keys(data[0]);
    const head = table.createTHead().insertRow();
    keys.forEach(key => { const th = document.createElement('th'); th.textContent = key; head.append(th); });
    const body = table.createTBody();
    data.forEach(row => { const tr = body.insertRow(); keys.forEach(key => { tr.insertCell().textContent = row[key] ?? ''; }); });
    answer.append(table);
  } else {
    answer.append(document.createTextNode(typeof data === 'string' ? data : JSON.stringify(data, null, 2)));
  }
}

async function submit(message, startProduct = false) {
  message = message.trim();
  if (busy || !message) return;
  if (startProduct && productPending) return;
  const productRequest = startProduct || productPending;
  busy = true;
  byId('send').disabled = byId('reset').disabled = true;
  messages.querySelectorAll('.choices button').forEach(button => { button.disabled = true; });
  input.value = '';
  bubble(message, 'user');
  const answer = bubble('', 'assistant');
  byId('status').textContent = 'Working…';
  function consume(event) {
    if (event.eventType === 'content') {
      if (productRequest) {
        try { productPending = JSON.parse(event.content)?.status === 'AWAITING_INPUT'; } catch { /* Keep prior state. */ }
      }
      display(event.content, answer);
    }
    if (event.eventType === 'status') byId('status').textContent = event.message;
    if (event.eventType === 'error') answer.textContent = event.message;
    messages.scrollTop = messages.scrollHeight;
  }
  try {
    const payload = startProduct ? {sessionId} : productPending ? {sessionId, answer: message} : {sessionId, message};
    const response = await fetch(productRequest ? '/api/products/add' : streamPath, {method: 'POST',
      headers: {'Content-Type': 'application/json', 'Accept': 'text/event-stream'},
      body: JSON.stringify(payload)});
    if (!response.ok || !response.body) throw new Error('Chat request failed');
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';
    let data = [];
    while (true) {
      const {value, done} = await reader.read();
      if (done) break;
      buffer += value;
      const lines = buffer.split('\n');
      buffer = lines.pop();
      for (const raw of lines) {
        const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;
        if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
        if (line === '' && data.length) {
          consume(JSON.parse(data.join('\n')));
          data = [];
        }
      }
    }
    if (!answer.hasChildNodes()) answer.textContent = 'No response was returned.';
  } catch {
    answer.textContent = 'Could not complete the request. Check that the local demo is running.';
  } finally {
    busy = false;
    byId('send').disabled = byId('reset').disabled = false;
    byId('status').textContent = '';
    input.focus();
  }
}

byId('composer').onsubmit = event => { event.preventDefault(); submit(input.value); };
byId('examples').querySelectorAll('button').forEach(button => {
  button.onclick = () => { if (!productPending) submit(button.textContent, button.id === 'add-product'); };
});
byId('reset').onclick = () => { sessionId = crypto.randomUUID(); productPending = false; messages.replaceChildren(); input.focus(); };