const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const path = require('path');
const os = require('os');
const { listSessions, getSessionMessages } = require('./session-reader');
const ClaudeManager = require('./claude-manager');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });
const manager = new ClaudeManager();

const PORT = process.env.PORT || 3456;

// Serve static files
app.use(express.static(path.join(__dirname, '..', 'public')));
app.use(express.json());

// --- REST API ---

app.get('/api/sessions', async (req, res) => {
  try {
    const sessions = await listSessions();
    res.json({ sessions });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/sessions/:id/messages', async (req, res) => {
  try {
    const messages = await getSessionMessages(req.params.id);
    if (!messages) {
      return res.status(404).json({ error: 'Session not found' });
    }
    res.json({ messages });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/active', (req, res) => {
  res.json({ processes: manager.getActive() });
});

app.get('/api/system', (req, res) => {
  res.json({
    hostname: os.hostname(),
    platform: os.platform(),
    homeDir: os.homedir(),
    cwd: process.cwd()
  });
});

// --- WebSocket ---

wss.on('connection', (ws) => {
  console.log('Client connected');

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      ws.send(JSON.stringify({ type: 'error', text: 'Invalid JSON' }));
      return;
    }

    if (msg.type === 'prompt') {
      if (!msg.text || !msg.text.trim()) {
        ws.send(JSON.stringify({ type: 'error', text: 'Empty prompt' }));
        return;
      }

      const processId = manager.spawnSession(msg.text, ws, {
        sessionId: msg.sessionId || null,
        cwd: msg.cwd || null,
        allowedTools: msg.allowedTools || null
      });

      ws.send(JSON.stringify({
        type: 'prompt_accepted',
        processId,
        sessionId: msg.sessionId || null
      }));
    } else if (msg.type === 'kill') {
      const killed = manager.killSession(msg.processId);
      ws.send(JSON.stringify({
        type: 'kill_result',
        processId: msg.processId,
        killed
      }));
    }
  });

  ws.on('close', () => {
    console.log('Client disconnected');
  });
});

// Cleanup on exit
process.on('SIGINT', () => {
  console.log('\nShutting down...');
  manager.killAll();
  process.exit(0);
});

process.on('SIGTERM', () => {
  manager.killAll();
  process.exit(0);
});

// --- Start server ---

function getLocalIP() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return 'localhost';
}

server.listen(PORT, '0.0.0.0', () => {
  const ip = getLocalIP();
  console.log('');
  console.log('  ╔═══════════════════════════════════════════╗');
  console.log('  ║         Claude Remote is running!         ║');
  console.log('  ╠═══════════════════════════════════════════╣');
  console.log(`  ║  Local:   http://localhost:${PORT}          ║`);
  console.log(`  ║  Phone:   http://${ip}:${PORT}    ║`);
  console.log('  ╠═══════════════════════════════════════════╣');
  console.log('  ║  Open the Phone URL on your mobile to     ║');
  console.log('  ║  install the app (Add to Home Screen)     ║');
  console.log('  ╚═══════════════════════════════════════════╝');
  console.log('');
});
