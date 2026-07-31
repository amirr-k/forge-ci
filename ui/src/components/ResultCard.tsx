import { formatElapsed } from "../buildProgress";

export default function ResultCard({
  baselineMs,
  incrementalMs,
  executed,
  reused,
  skipped,
}: {
  baselineMs: number;
  incrementalMs: number;
  executed: number;
  reused: number;
  skipped: number;
}) {
  const savedMs = Math.max(baselineMs - incrementalMs, 0);
  const savedPercent = baselineMs > 0 ? Math.round((savedMs / baselineMs) * 100) : 0;

  return (
    <div className="card" aria-live="polite">
      <p className="result-headline">Traditional CI rebuilt everything. ForgeCI safely reused most of the work.</p>
      <div className="result-grid">
        <Stat label="Traditional build" value={formatElapsed(baselineMs)} />
        <Stat label="ForgeCI build" value={formatElapsed(incrementalMs)} />
        <Stat label="Tasks executed" value={String(executed)} />
        <Stat label="Tasks reused" value={String(reused)} />
        <Stat label="Tasks skipped" value={String(skipped)} />
        <Stat label="Time saved" value={`${formatElapsed(savedMs)} (${savedPercent}%)`} />
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="result-stat-label">{label}</div>
      <div className="result-stat-value">{value}</div>
    </div>
  );
}
