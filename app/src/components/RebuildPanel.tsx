import { useState } from "react";
import type { OpeningsData } from "../openings/types";
import { fetchChessComGames } from "../data/chesscom";
import { buildOpeningsFromGames } from "../openings/build";
import { saveOpenings } from "../data/openingsStore";

interface RebuildPanelProps {
  currentUser: string;
  isCustom: boolean;
  onRebuilt: (data: OpeningsData) => void;
}

const TIME_CLASSES = [
  { key: "", label: "All" },
  { key: "bullet", label: "Bullet" },
  { key: "blitz", label: "Blitz" },
  { key: "rapid", label: "Rapid" },
];

type Phase = "idle" | "working" | "done" | "error";

export function RebuildPanel({ currentUser, isCustom, onRebuilt }: RebuildPanelProps) {
  const [open, setOpen] = useState(false);
  const [user, setUser] = useState(currentUser === "SampleUser" ? "" : currentUser);
  const [timeClass, setTimeClass] = useState("bullet");
  const [phase, setPhase] = useState<Phase>("idle");
  const [status, setStatus] = useState("");

  const busy = phase === "working";

  async function rebuild() {
    const handle = user.trim();
    if (!handle) {
      setPhase("error");
      setStatus("Enter your chess.com username first.");
      return;
    }
    setPhase("working");
    setStatus("Fetching your games from chess.com…");
    try {
      const games = await fetchChessComGames(handle, 200, {
        timeClass: timeClass || undefined,
        onProgress: (msg) => setStatus(msg),
      });
      if (games.length === 0) {
        setPhase("error");
        setStatus(`No ${timeClass || "standard"} games found for "${handle}".`);
        return;
      }
      setStatus(`Building your openings tree from ${games.length} games…`);
      // Let the status paint before the (synchronous) build.
      await new Promise((r) => setTimeout(r, 30));
      const data = buildOpeningsFromGames(games, { user: handle, maxPly: 24 });
      if (data.meta.sourceGameCount === 0) {
        setPhase("error");
        setStatus(`Fetched ${games.length} games but none matched "${handle}". Check the spelling.`);
        return;
      }
      const saved = saveOpenings(data);
      onRebuilt(data);
      setPhase("done");
      setStatus(
        `Done — trained on ${data.meta.sourceGameCount} games` +
          (saved ? "." : " (kept for this session; too large to store)."),
      );
    } catch (e) {
      setPhase("error");
      setStatus(
        `Couldn't reach chess.com for "${handle}". Check the username and your connection. (${String(
          e,
        )})`,
      );
    }
  }

  if (!open) {
    return (
      <button className="rebuild-collapsed" onClick={() => setOpen(true)}>
        <span>
          {isCustom ? `Trained on ${currentUser}'s games` : "Using sample games"} · tap to use your
          chess.com games
        </span>
        <span className="rebuild-chev">›</span>
      </button>
    );
  }

  return (
    <div className="rebuild-panel">
      <div className="rebuild-head">
        <strong>Train on your chess.com games</strong>
        <button className="ghost-btn" onClick={() => setOpen(false)} disabled={busy}>
          ✕
        </button>
      </div>

      <input
        className="rebuild-input"
        placeholder="chess.com username"
        value={user}
        autoCapitalize="none"
        autoCorrect="off"
        spellCheck={false}
        onChange={(e) => setUser(e.target.value)}
        disabled={busy}
      />

      <div className="segmented small-seg rebuild-tc">
        {TIME_CLASSES.map((tc) => (
          <button
            key={tc.key}
            className={"seg" + (timeClass === tc.key ? " seg-on" : "")}
            onClick={() => setTimeClass(tc.key)}
            disabled={busy}
          >
            {tc.label}
          </button>
        ))}
      </div>

      <button className="primary-btn rebuild-go" onClick={rebuild} disabled={busy}>
        {busy ? "Rebuilding…" : "Rebuild openings"}
      </button>

      {status && (
        <p className={"rebuild-status " + (phase === "error" ? "err" : phase === "done" ? "ok" : "")}>
          {busy && <span className="inline-spinner" />}
          {status}
        </p>
      )}
      <p className="muted small rebuild-note">
        Fetches your last ~200 games, newest first, and builds your opening lines. Runs on your phone
        — nothing is uploaded.
      </p>
    </div>
  );
}
