#!/bin/bash
set -e

echo ""
echo "  Claude Remote — Setup"
echo "  ====================="
echo ""

# Check Node.js
if ! command -v node &> /dev/null; then
  echo "  ❌ Node.js not found. Install it from https://nodejs.org"
  exit 1
fi
echo "  ✓ Node.js $(node -v)"

# Check Claude CLI
if ! command -v claude &> /dev/null; then
  echo "  ❌ Claude Code CLI not found."
  echo "    Install it with: npm install -g @anthropic-ai/claude-code"
  exit 1
fi
echo "  ✓ Claude Code CLI found"

# Install dependencies
echo ""
echo "  Installing dependencies..."
npm install --production

echo ""
echo "  ✅ Setup complete!"
echo ""
echo "  To start the server, run:"
echo "    npm start"
echo ""
echo "  Then open the URL shown on your phone's browser"
echo "  and tap 'Add to Home Screen' to install the app."
echo ""
