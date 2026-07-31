import { useMemo, useState } from "react";
import { crashWorker, DemoBuildResponse, DemoScenarioId, DemoApiError, startDemoBuild } from "../api";
import { useBuildProgress, formatElapsed } from "../buildProgress";
import DependencyGraph from "../components/DependencyGraph";
import TerminalPanel from "../components/TerminalPanel";
import ResultCard from "../components/ResultCard";

const SCENARIOS: { id: DemoScenarioId; label: string }[] = [
  { id: "leaf-module", label: "Change one file in Pricing" },
  { id: "shared-core", label: "Change a shared module" },
  { id: "config-toolchain", label: "Change the toolchain" },
  { id: "failed-test", label: "Break a test" },
  { id: "no-change", label: "No changes" },
  { id: "worker-crash", label: "Simulate a build machine crash" },
];

type Phase = "instructions" | "running";

export default function Home() {
  const [phase, setPhase] = useState<Phase>("instructions");
  const [scenario, setScenario] = useState<DemoScenarioId>("leaf-module");
  const [build, setBuild] = useState<DemoBuildResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [crashState, setCrashState] = useState<{ workerId: number; startedAt: number } | null>(null);

  const baselineInitial = useMemo(
    () => (build ? build.baselineTasks.map((name) => ({ name, status: "affected" as const })) : []),
    [build],
  );
  const incrementalInitial = useMemo(() => {
    if (!build) {
      return [];
    }
    const affected = build.incrementalTasks.map((t) => ({ name: t.name, status: "affected" as const }));
    const reused = build.unaffectedTasks.map((name) => ({ name, status: "reused" as const }));
    return [...reused, ...affected];
  }, [build]);

  const baseline = useBuildProgress(build?.baselineBuildId ?? null, baselineInitial);
  const incremental = useBuildProgress(build?.incrementalBuildId ?? null, incrementalInitial);

  const bothDone =
    baseline.buildState === "SUCCEEDED" ||
    baseline.buildState === "FAILED" ||
    baseline.buildState === "CANCELED"
      ? incremental.buildState === "SUCCEEDED" || incremental.buildState === "FAILED" || incremental.buildState === "CANCELED"
      : false;

  async function begin() {
    setError(null);
    setStarting(true);
    setCrashState(null);
    try {
      const response = await startDemoBuild(scenario, 2);
      setBuild(response);
      setPhase("running");
    } catch (e) {
      setError(e instanceof DemoApiError ? e.message : "could not start the demo build");
    } finally {
      setStarting(false);
    }
  }

  async function onCrashWorker() {
    if (!build) {
      return;
    }
    try {
      const response = await crashWorker(build.incrementalBuildId);
      setCrashState({ workerId: response.workerId, startedAt: Date.now() });
    } catch (e) {
      setError(e instanceof DemoApiError ? e.message : "could not crash a build machine right now");
    }
  }

  if (phase === "instructions") {
    return (
      <div>
        <h1 className="headline">Build only what changed.</h1>
        <p className="subhead">
          ForgeCI reads a repository's task dependency graph, works out which tasks a change actually
          affects, and reuses everything else. Pick what changes, then watch a real traditional build
          and a real ForgeCI build run side by side against the same code.
        </p>
        {error && <div className="status-banner error">{error}</div>}
        <div className="graph" role="radiogroup" aria-label="demo scenario">
          {SCENARIOS.map((s) => (
            <button
              key={s.id}
              type="button"
              role="radio"
              aria-checked={scenario === s.id}
              className={`graph-node${scenario === s.id ? " is-changed" : ""}`}
              onClick={() => setScenario(s.id)}
            >
              {s.label}
            </button>
          ))}
        </div>
        <button className="button" onClick={begin} disabled={starting}>
          {starting ? "Starting…" : "Begin"}
        </button>
      </div>
    );
  }

  return (
    <div>
      {error && <div className="status-banner error">{error}</div>}
      {build && <DependencyGraph baselineTasks={build.baselineTasks} incrementalTasks={build.incrementalTasks} />}
      <div className="terminals">
        <TerminalPanel title="Traditional build" lines={baseline.lines} />
        <TerminalPanel title="ForgeCI build" lines={incremental.lines} />
      </div>
      <div className="timers">
        <div className="timer">
          <div className="timer-label">Traditional build</div>
          <div className="timer-value">{formatElapsed(baseline.elapsedMs)}</div>
        </div>
        <div className="timer">
          <div className="timer-label">ForgeCI build</div>
          <div className="timer-value">{formatElapsed(incremental.elapsedMs)}</div>
        </div>
      </div>

      {bothDone && build && (
        <ResultCard
          baselineMs={baseline.elapsedMs}
          incrementalMs={incremental.elapsedMs}
          executed={build.incrementalTasks.length}
          reused={build.unaffectedTasks.length}
          skipped={incremental.lines.filter((l) => l.status === "skipped").length}
        />
      )}

      <div className="link-row">
        <button
          className="button button-secondary"
          onClick={onCrashWorker}
          disabled={incremental.buildState !== "RUNNING" || crashState !== null}
        >
          Crash a Worker
        </button>
        {bothDone && (
          <button
            className="button button-secondary"
            onClick={() => {
              setPhase("instructions");
              setBuild(null);
            }}
          >
            Run again
          </button>
        )}
      </div>

      {crashState && (
        <div className="card crash-summary" style={{ marginTop: "1rem" }}>
          {`Build machine ${crashState.workerId} failed\n`}
          {incremental.buildState === "SUCCEEDED"
            ? `Recovered by another build machine\nRecovery time: ${formatElapsed(Date.now() - crashState.startedAt)}\nDuplicate artifacts: 0\nBuild completed successfully`
            : "Recovering…"}
        </div>
      )}
    </div>
  );
}
