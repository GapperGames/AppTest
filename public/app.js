// ---- State ----
const state = {
  ws: null,
  connected: false,
  currentSessionId: null,
  currentProcessId: null,
  isStreaming: false,
  sessions: [],
  streamBuffer: ''
};

// ---- DOM refs ----
const $ = (sel) => document.querySelector(sel);
const chatArea = $('#chatArea');
const inputField = $('#inputField');
const sendBtn = $('#sendBtn');
const menuBtn = $('#menuBtn');
const sidebar = $('#sidebar');
const sidebarOverlay = $('#sidebarOverlay');
const sessionList = $('#sessionList');
const newSessionBtn = $('#newSessionBtn');
const connectionDot = $('#connectionDot');
const headerTitle = $('#headerTitle');
const statusBar = $('#statusBar');
const typingIndicator = $('#typingIndicator');
const welcome = $('#welcome');

// ---- WebSocket ----
function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const url = `${proto}//${location.host}`;

  connectionDot.className = 'connection-dot connecting';
  connectionDot.title = 'Connecting...';

  const ws = new WebSocket(url);

  ws.onopen = () => {
    state.ws = ws;
    state.connected = true;
    connectionDot.className = 'connection-dot connected';
    connectionDot.title = 'Connected';
    loadSessions();
  };

  ws.onmessage = (evt) => {
    try {
      const msg = JSON.parse(evt.data);
      handleServerMessage(msg);
    } catch {
      // Ignore unparseable messages
    }
  };

  ws.onclose = () => {
    state.ws = null;
    state.connected = false;
    connectionDot.className = 'connection-dot';
    connectionDot.title = 'Disconnected';
    // Reconnect after 2 seconds
    setTimeout(connect, 2000);
  };

  ws.onerror = () => {
    ws.close();
  };
}

// ---- Server message handler ----
function handleServerMessage(msg) {
  switch (msg.type) {
    case 'prompt_accepted':
      state.currentProcessId = msg.processId;
      break;

    case 'status':
      if (msg.sessionId) {
        state.currentSessionId = msg.sessionId;
      }
      if (msg.status === 'started') {
        state.isStreaming = true;
        state.streamBuffer = '';
        typingIndicator.classList.add('visible');
        updateSendButton();
        showStatus('Claude is thinking...');
      } else if (msg.status === 'done') {
        finishStream();
        showStatus(msg.exitCode === 0 ? '' : `Exited with code ${msg.exitCode}`);
      } else if (msg.status === 'error') {
        finishStream();
        showStatus('Error occurred');
      }
      break;

    case 'assistant_text':
      hideWelcome();
      typingIndicator.classList.remove('visible');
      appendToStream(msg.text);
      break;

    case 'tool_use_start':
      appendToolCard(msg.tool, msg.toolUseId);
      break;

    case 'tool_input_delta':
      appendToToolInput(msg.delta);
      break;

    case 'result':
      if (msg.sessionId) {
        state.currentSessionId = msg.sessionId;
        headerTitle.textContent = `Session ${msg.sessionId.slice(0, 8)}...`;
      }
      if (msg.cost) {
        appendCostBadge(msg.cost, msg.duration);
      }
      finishStream();
      break;

    case 'error':
      appendMessage('error', msg.text);
      finishStream();
      break;

    case 'sessions_updated':
      loadSessions();
      break;

    // Forward raw stream events for tool results
    case 'stream_event':
      handleStreamEvent(msg.data);
      break;
  }
}

function handleStreamEvent(event) {
  if (!event) return;

  // Handle tool results from content blocks
  if (event.type === 'content_block_start') {
    const block = event.content_block;
    if (block?.type === 'tool_result') {
      const text = typeof block.content === 'string' ? block.content :
        Array.isArray(block.content) ? block.content.filter(b => b.type === 'text').map(b => b.text).join('\n') : '';
      if (text) appendToolResult(text);
    }
  }
}

// ---- Stream management ----
let currentStreamEl = null;

function appendToStream(text) {
  if (!currentStreamEl) {
    currentStreamEl = document.createElement('div');
    currentStreamEl.className = 'message assistant';
    chatArea.appendChild(currentStreamEl);
  }
  state.streamBuffer += text;
  currentStreamEl.innerHTML = renderMarkdown(state.streamBuffer);
  scrollToBottom();
}

function finishStream() {
  state.isStreaming = false;
  state.currentProcessId = null;
  currentStreamEl = null;
  state.streamBuffer = '';
  typingIndicator.classList.remove('visible');
  updateSendButton();
  scrollToBottom();
}

// ---- Tool cards ----
let currentToolCard = null;
let currentToolInput = '';

function appendToolCard(toolName, toolUseId) {
  const icons = {
    Read: '📄', Edit: '✏️', Write: '📝', Bash: '💻',
    Glob: '🔍', Grep: '🔎', Agent: '🤖', TodoWrite: '📋'
  };

  const card = document.createElement('div');
  card.className = 'tool-card';
  card.dataset.toolUseId = toolUseId || '';
  card.innerHTML = `
    <div class="tool-card-header">
      <span class="tool-card-icon">${icons[toolName] || '🔧'}</span>
      <span class="tool-card-name">${escapeHtml(toolName)}</span>
      <span class="tool-card-chevron">▶</span>
    </div>
    <div class="tool-card-body"><pre></pre></div>
  `;

  card.querySelector('.tool-card-header').addEventListener('click', () => {
    card.classList.toggle('open');
  });

  // Insert before current stream element or at end
  if (currentStreamEl) {
    chatArea.insertBefore(card, currentStreamEl);
  } else {
    chatArea.appendChild(card);
  }

  currentToolCard = card;
  currentToolInput = '';
  scrollToBottom();
}

function appendToToolInput(delta) {
  if (!currentToolCard) return;
  currentToolInput += delta;
  const pre = currentToolCard.querySelector('.tool-card-body pre');
  if (pre) {
    // Try to format as JSON for readability
    try {
      const parsed = JSON.parse(currentToolInput);
      pre.textContent = JSON.stringify(parsed, null, 2);
    } catch {
      pre.textContent = currentToolInput;
    }
  }
}

function appendToolResult(text) {
  if (!currentToolCard) return;
  const body = currentToolCard.querySelector('.tool-card-body pre');
  if (body) {
    body.textContent += '\n--- Result ---\n' + text;
  }
}

function appendCostBadge(cost, duration) {
  const badge = document.createElement('div');
  badge.className = 'cost-badge';
  const parts = [];
  if (cost) parts.push(`$${cost.toFixed(4)}`);
  if (duration) parts.push(`${(duration / 1000).toFixed(1)}s`);
  badge.textContent = parts.join(' · ');
  chatArea.appendChild(badge);
  scrollToBottom();
}

// ---- Messages ----
function appendMessage(role, text) {
  hideWelcome();
  const el = document.createElement('div');
  el.className = `message ${role}`;
  if (role === 'assistant') {
    el.innerHTML = renderMarkdown(text);
  } else {
    el.textContent = text;
  }
  chatArea.appendChild(el);
  scrollToBottom();
}

function hideWelcome() {
  if (welcome) welcome.style.display = 'none';
}

function showWelcome() {
  if (welcome) welcome.style.display = '';
}

function clearChat() {
  chatArea.innerHTML = '';
  chatArea.appendChild(welcome);
  showWelcome();
  currentStreamEl = null;
  currentToolCard = null;
}

// ---- Markdown renderer (basic) ----
function renderMarkdown(text) {
  let html = escapeHtml(text);

  // Code blocks (``` ... ```)
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
    return `<pre><code>${code}</code></pre>`;
  });

  // Inline code
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

  // Bold
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');

  // Italic
  html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');

  // Links
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');

  // Unordered lists
  html = html.replace(/^- (.+)$/gm, '<li>$1</li>');
  html = html.replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>');

  // Line breaks → paragraphs (double newline)
  html = html.replace(/\n\n/g, '</p><p>');
  html = '<p>' + html + '</p>';

  // Single newlines within paragraphs
  html = html.replace(/([^>])\n([^<])/g, '$1<br>$2');

  // Clean up empty paragraphs
  html = html.replace(/<p>\s*<\/p>/g, '');

  return html;
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// ---- Sending messages ----
function sendMessage() {
  const text = inputField.value.trim();
  if (!text || !state.connected) return;

  if (state.isStreaming) {
    // Kill current process
    if (state.currentProcessId) {
      state.ws.send(JSON.stringify({ type: 'kill', processId: state.currentProcessId }));
    }
    return;
  }

  appendMessage('user', text);
  inputField.value = '';
  autoResize();

  state.ws.send(JSON.stringify({
    type: 'prompt',
    text,
    sessionId: state.currentSessionId || undefined
  }));
}

function updateSendButton() {
  if (state.isStreaming) {
    sendBtn.innerHTML = '&#9632;'; // Stop square
    sendBtn.classList.add('stop-btn');
    sendBtn.disabled = false;
  } else {
    sendBtn.innerHTML = '&#9654;'; // Play triangle
    sendBtn.classList.remove('stop-btn');
    sendBtn.disabled = false;
  }
}

// ---- Sessions ----
async function loadSessions() {
  try {
    const res = await fetch('/api/sessions');
    const data = await res.json();
    state.sessions = data.sessions || [];
    renderSessionList();
  } catch {
    sessionList.innerHTML = '<div class="loading-sessions">Failed to load sessions</div>';
  }
}

function renderSessionList() {
  if (state.sessions.length === 0) {
    sessionList.innerHTML = '<div class="loading-sessions">No sessions found</div>';
    return;
  }

  sessionList.innerHTML = state.sessions.map(s => `
    <div class="session-item${s.id === state.currentSessionId ? ' active' : ''}" data-id="${escapeHtml(s.id)}">
      <div class="session-item-title">${escapeHtml(s.title)}</div>
      <div class="session-item-meta">${formatTime(s.lastActiveISO)} · ${s.messageCount} messages</div>
      <div class="session-item-project">${escapeHtml(s.projectPath)}</div>
    </div>
  `).join('');

  // Click handlers
  sessionList.querySelectorAll('.session-item').forEach(el => {
    el.addEventListener('click', () => openSession(el.dataset.id));
  });
}

async function openSession(sessionId) {
  closeSidebar();
  state.currentSessionId = sessionId;
  clearChat();
  hideWelcome();

  const session = state.sessions.find(s => s.id === sessionId);
  headerTitle.textContent = session ? session.title.slice(0, 40) : `Session ${sessionId.slice(0, 8)}`;

  // Show loading
  appendMessage('system', 'Loading conversation...');

  try {
    const res = await fetch(`/api/sessions/${sessionId}/messages`);
    const data = await res.json();

    // Clear loading message
    chatArea.innerHTML = '';

    if (data.messages && data.messages.length > 0) {
      for (const msg of data.messages) {
        appendMessage(msg.role === 'user' ? 'user' : 'assistant', msg.text);
      }
    } else {
      appendMessage('system', 'No messages in this session yet.');
    }
  } catch {
    appendMessage('error', 'Failed to load session messages.');
  }

  // Mark active in sidebar
  renderSessionList();
  scrollToBottom();
}

// ---- Sidebar ----
function openSidebar() {
  sidebar.classList.add('open');
  sidebarOverlay.classList.add('open');
}

function closeSidebar() {
  sidebar.classList.remove('open');
  sidebarOverlay.classList.remove('open');
}

// ---- Status bar ----
function showStatus(text) {
  if (!text) {
    statusBar.classList.remove('visible');
    return;
  }
  statusBar.textContent = text;
  statusBar.classList.add('visible');
}

// ---- Helpers ----
function scrollToBottom() {
  requestAnimationFrame(() => {
    chatArea.scrollTop = chatArea.scrollHeight;
  });
}

function formatTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const now = new Date();
  const diffMs = now - d;
  const diffMin = Math.floor(diffMs / 60000);
  const diffHr = Math.floor(diffMs / 3600000);
  const diffDay = Math.floor(diffMs / 86400000);

  if (diffMin < 1) return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;
  if (diffHr < 24) return `${diffHr}h ago`;
  if (diffDay < 7) return `${diffDay}d ago`;
  return d.toLocaleDateString();
}

function autoResize() {
  inputField.style.height = 'auto';
  inputField.style.height = Math.min(inputField.scrollHeight, 120) + 'px';
}

// ---- Event listeners ----

// Send button
sendBtn.addEventListener('click', sendMessage);

// Enter to send (Shift+Enter for newline)
inputField.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    sendMessage();
  }
});

// Auto-resize textarea
inputField.addEventListener('input', autoResize);

// Menu
menuBtn.addEventListener('click', openSidebar);
sidebarOverlay.addEventListener('click', closeSidebar);

// New session
newSessionBtn.addEventListener('click', () => {
  state.currentSessionId = null;
  headerTitle.textContent = 'Claude Remote';
  clearChat();
  closeSidebar();
  inputField.focus();
});

// ---- Service Worker ----
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}

// ---- Init ----
connect();
