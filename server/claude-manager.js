const { spawn } = require('child_process');
const { v4: uuidv4 } = require('uuid');

class ClaudeManager {
  constructor() {
    // Map of processId -> { process, sessionId, status, ws }
    this.active = new Map();
  }

  spawnSession(prompt, ws, options = {}) {
    const processId = uuidv4();

    const args = ['-p', prompt, '--output-format', 'stream-json', '--verbose'];

    if (options.sessionId) {
      args.push('--resume', options.sessionId);
    }

    if (options.cwd) {
      args.push('--cwd', options.cwd);
    }

    if (options.allowedTools) {
      args.push('--allowedTools', options.allowedTools);
    }

    const proc = spawn('claude', args, {
      env: { ...process.env },
      stdio: ['pipe', 'pipe', 'pipe']
    });

    const entry = {
      process: proc,
      processId,
      sessionId: options.sessionId || null,
      status: 'running',
      ws
    };

    this.active.set(processId, entry);

    let buffer = '';

    proc.stdout.on('data', (chunk) => {
      buffer += chunk.toString();

      // Process complete JSON lines
      const lines = buffer.split('\n');
      buffer = lines.pop(); // Keep incomplete last line in buffer

      for (const line of lines) {
        if (!line.trim()) continue;
        try {
          const event = JSON.parse(line);
          this.handleEvent(processId, event, ws);
        } catch {
          // Not valid JSON, skip
        }
      }
    });

    proc.stderr.on('data', (chunk) => {
      const text = chunk.toString();
      this.send(ws, {
        type: 'error',
        processId,
        text
      });
    });

    proc.on('close', (code) => {
      entry.status = 'done';

      // Process any remaining buffer
      if (buffer.trim()) {
        try {
          const event = JSON.parse(buffer);
          this.handleEvent(processId, event, ws);
        } catch {
          // Ignore
        }
      }

      this.send(ws, {
        type: 'status',
        processId,
        sessionId: entry.sessionId,
        status: code === 0 ? 'done' : 'error',
        exitCode: code
      });

      this.active.delete(processId);
    });

    proc.on('error', (err) => {
      this.send(ws, {
        type: 'error',
        processId,
        text: `Failed to start Claude: ${err.message}. Is the Claude CLI installed?`
      });
      this.active.delete(processId);
    });

    // Send initial acknowledgment
    this.send(ws, {
      type: 'status',
      processId,
      status: 'started',
      sessionId: entry.sessionId
    });

    return processId;
  }

  handleEvent(processId, event, ws) {
    const entry = this.active.get(processId);

    // Extract session_id from result events
    if (event.session_id && entry && !entry.sessionId) {
      entry.sessionId = event.session_id;
    }

    // Parse different event types from Claude's stream-json format
    if (event.type === 'result') {
      // Final result message
      this.send(ws, {
        type: 'result',
        processId,
        sessionId: event.session_id,
        text: event.result,
        cost: event.total_cost_usd,
        duration: event.duration_ms
      });
    } else if (event.type === 'content_block_delta' || event.type === 'stream_event') {
      // Streaming content — forward the raw event plus try to extract text
      const delta = event.delta || event.event?.delta;
      if (delta?.type === 'text_delta' && delta.text) {
        this.send(ws, {
          type: 'assistant_text',
          processId,
          text: delta.text
        });
      } else if (delta?.type === 'input_json_delta') {
        this.send(ws, {
          type: 'tool_input_delta',
          processId,
          delta: delta.partial_json
        });
      }

      // Also forward raw event for detailed UI
      this.send(ws, {
        type: 'stream_event',
        processId,
        data: event
      });
    } else if (event.type === 'content_block_start') {
      const block = event.content_block;
      if (block?.type === 'tool_use') {
        this.send(ws, {
          type: 'tool_use_start',
          processId,
          tool: block.name,
          toolUseId: block.id
        });
      }
      this.send(ws, { type: 'stream_event', processId, data: event });
    } else if (event.type === 'content_block_stop') {
      this.send(ws, { type: 'stream_event', processId, data: event });
    } else if (event.type === 'message_start' || event.type === 'message_delta' || event.type === 'message_stop') {
      this.send(ws, { type: 'stream_event', processId, data: event });
    } else {
      // Forward any other event types
      this.send(ws, { type: 'stream_event', processId, data: event });
    }
  }

  send(ws, data) {
    if (ws.readyState === 1) { // WebSocket.OPEN
      ws.send(JSON.stringify(data));
    }
  }

  killSession(processId) {
    const entry = this.active.get(processId);
    if (entry) {
      entry.process.kill('SIGTERM');
      entry.status = 'killed';
      this.active.delete(processId);
      return true;
    }
    return false;
  }

  getActive() {
    const result = [];
    for (const [processId, entry] of this.active) {
      result.push({
        processId,
        sessionId: entry.sessionId,
        status: entry.status
      });
    }
    return result;
  }

  killAll() {
    for (const [, entry] of this.active) {
      entry.process.kill('SIGTERM');
    }
    this.active.clear();
  }
}

module.exports = ClaudeManager;
