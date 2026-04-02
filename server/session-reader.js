const fs = require('fs');
const path = require('path');
const readline = require('readline');
const os = require('os');

const CLAUDE_DIR = path.join(os.homedir(), '.claude', 'projects');

function getProjectDirs() {
  try {
    if (!fs.existsSync(CLAUDE_DIR)) return [];
    return fs.readdirSync(CLAUDE_DIR, { withFileTypes: true })
      .filter(d => d.isDirectory())
      .map(d => ({
        name: d.name,
        fullPath: path.join(CLAUDE_DIR, d.name),
        // Decode the project path from the directory name
        decodedPath: decodeProjectPath(d.name)
      }));
  } catch {
    return [];
  }
}

function decodeProjectPath(encoded) {
  // Claude encodes project paths by replacing / with -
  // e.g., "-home-user-myproject" → "/home/user/myproject"
  try {
    return '/' + encoded.replace(/^-/, '').replace(/-/g, '/');
  } catch {
    return encoded;
  }
}

async function parseSessionFile(filePath) {
  const stats = fs.statSync(filePath);
  const sessionId = path.basename(filePath, '.jsonl');

  let firstUserMessage = null;
  let lastTimestamp = stats.mtimeMs;
  let messageCount = 0;
  let projectPath = decodeProjectPath(path.basename(path.dirname(filePath)));

  return new Promise((resolve) => {
    const stream = fs.createReadStream(filePath, { encoding: 'utf-8' });
    const rl = readline.createInterface({ input: stream, crlfDelay: Infinity });
    let lineCount = 0;

    rl.on('line', (line) => {
      lineCount++;
      // Only parse first 50 lines to keep it fast
      if (lineCount > 50) {
        rl.close();
        return;
      }

      try {
        const obj = JSON.parse(line);

        if (obj.type === 'user' || obj.role === 'user') {
          messageCount++;
          if (!firstUserMessage) {
            const content = obj.content || obj.message?.content;
            if (typeof content === 'string') {
              firstUserMessage = content.slice(0, 120);
            } else if (Array.isArray(content)) {
              const textBlock = content.find(b => b.type === 'text');
              if (textBlock) firstUserMessage = textBlock.text.slice(0, 120);
            }
          }
        }

        if (obj.type === 'assistant' || obj.role === 'assistant') {
          messageCount++;
        }

        if (obj.timestamp) {
          lastTimestamp = new Date(obj.timestamp).getTime();
        }
      } catch {
        // Skip unparseable lines
      }
    });

    rl.on('close', () => {
      resolve({
        id: sessionId,
        title: firstUserMessage || 'Untitled session',
        projectPath,
        messageCount,
        lastActive: lastTimestamp,
        lastActiveISO: new Date(lastTimestamp).toISOString(),
        filePath
      });
    });

    rl.on('error', () => {
      resolve(null);
    });
  });
}

async function listSessions(limit = 50) {
  const projectDirs = getProjectDirs();
  const sessions = [];

  for (const dir of projectDirs) {
    try {
      const files = fs.readdirSync(dir.fullPath)
        .filter(f => f.endsWith('.jsonl'));

      for (const file of files) {
        const filePath = path.join(dir.fullPath, file);
        const session = await parseSessionFile(filePath);
        if (session) sessions.push(session);
      }
    } catch {
      // Skip inaccessible directories
    }
  }

  sessions.sort((a, b) => b.lastActive - a.lastActive);
  return sessions.slice(0, limit);
}

async function getSessionMessages(sessionId) {
  const projectDirs = getProjectDirs();

  // Find the session file
  let sessionFile = null;
  for (const dir of projectDirs) {
    const candidate = path.join(dir.fullPath, `${sessionId}.jsonl`);
    if (fs.existsSync(candidate)) {
      sessionFile = candidate;
      break;
    }
  }

  if (!sessionFile) return null;

  const messages = [];

  return new Promise((resolve) => {
    const stream = fs.createReadStream(sessionFile, { encoding: 'utf-8' });
    const rl = readline.createInterface({ input: stream, crlfDelay: Infinity });

    rl.on('line', (line) => {
      try {
        const obj = JSON.parse(line);
        const role = obj.type || obj.role;

        if (role === 'user' || role === 'assistant') {
          let text = '';
          const content = obj.content || obj.message?.content;

          if (typeof content === 'string') {
            text = content;
          } else if (Array.isArray(content)) {
            text = content
              .filter(b => b.type === 'text')
              .map(b => b.text)
              .join('\n');
          }

          if (text) {
            messages.push({
              role,
              text,
              timestamp: obj.timestamp || null
            });
          }
        }
      } catch {
        // Skip
      }
    });

    rl.on('close', () => resolve(messages));
    rl.on('error', () => resolve(messages));
  });
}

module.exports = { listSessions, getSessionMessages, CLAUDE_DIR };
