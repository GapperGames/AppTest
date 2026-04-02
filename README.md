# Claude Remote

Control Claude Code on your desktop from your phone.

A mobile PWA + desktop server that lets you dispatch and interact with Claude Code instances running on your computer — directly from your phone over local WiFi.

## Features

- **Browse existing sessions** — see all your Claude Code sessions from the Desktop App, CLI, and VS Code
- **Resume conversations** — pick up where you left off in any session
- **Start new sessions** — dispatch fresh Claude Code tasks from your phone
- **Real-time streaming** — watch Claude think and use tools live
- **Tool visibility** — see file edits, bash commands, and other tool usage in collapsible cards
- **Installable PWA** — add to your home screen for a native app experience
- **Dark theme** — easy on your eyes

## Prerequisites

1. **Node.js** (v18+) — [nodejs.org](https://nodejs.org)
2. **Claude Code CLI** — Install with:
   ```bash
   npm install -g @anthropic-ai/claude-code
   ```
3. Your phone and desktop must be on the **same WiFi network**

## Quick Start

```bash
# 1. Clone and enter the project
git clone <this-repo>
cd AppTest

# 2. Run setup (checks dependencies, installs packages)
bash setup.sh

# 3. Start the server
npm start
```

The server will print a URL like:

```
  Phone:   http://192.168.1.42:3456
```

Open that URL on your phone's browser, then tap **"Add to Home Screen"** to install.

## How It Works

```
Phone (PWA)  ──WebSocket──>  Desktop Server  ──spawns──>  Claude Code CLI
```

1. The server runs on your desktop and serves the mobile web app
2. When you send a prompt from your phone, it spawns a Claude Code CLI process
3. Claude's streaming output is forwarded to your phone in real-time
4. Session history is read from `~/.claude/projects/` — the same location used by the Desktop App

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `PORT` | `3456` | Server port |

## Troubleshooting

**Can't connect from phone?**
- Make sure both devices are on the same WiFi network
- Check that no firewall is blocking port 3456
- Try the IP address shown when the server starts

**"Failed to start Claude" error?**
- Make sure the Claude CLI is installed: `claude --version`
- Make sure you're logged in: `claude auth`

**No sessions showing?**
- Sessions are read from `~/.claude/projects/`
- If you've only used Claude on the web, there won't be local sessions
