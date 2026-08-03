import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { traces, type Trace, type TraceTask } from "../traces";

const SPEEDS = [0.5, 1, 2, 4];

interface Lane {
    id: string;
    tasks: { task: string; startMs: number; endMs: number; status: string }[];
}

/** Groups a trace's events into per-executor lanes, which is what makes concurrency legible. */
function toLanes(trace: Trace): Lane[] {
    const open = new Map<string, number>();
    const lanes = new Map<string, Lane>();
    for (const event of trace.events) {
        if (!event.task || !event.worker) continue;
        if (event.type === "TASK_STARTED") {
            open.set(event.task, event.atMs);
            continue;
        }
        const startMs = open.get(event.task) ?? event.atMs;
        open.delete(event.task);
        const laneId = event.worker;
        if (!lanes.has(laneId)) lanes.set(laneId, { id: laneId, tasks: [] });
        lanes.get(laneId)!.tasks.push({
            task: event.task,
            startMs,
            endMs: event.atMs,
            status: event.type === "TASK_CACHE_HIT" ? "CACHE_HIT" : "RUN",
        });
    }
    return [...lanes.values()].sort((a, b) => a.id.localeCompare(b.id));
}

/** Assigns each task a depth so the graph reads left-to-right along the dependency direction. */
function layout(trace: Trace) {
    const depth = new Map<string, number>();
    const deps = new Map(trace.tasks.map((t) => [t.id, t.dependsOn ?? []]));
    const resolve = (id: string, seen: Set<string>): number => {
        if (depth.has(id)) return depth.get(id)!;
        if (seen.has(id)) return 0;
        seen.add(id);
        const parents = deps.get(id) ?? [];
        const d = parents.length === 0 ? 0 : Math.max(...parents.map((p) => resolve(p, seen))) + 1;
        depth.set(id, d);
        return d;
    };
    trace.tasks.forEach((t) => resolve(t.id, new Set()));
    const columns = new Map<number, string[]>();
    trace.tasks.forEach((t) => {
        const d = depth.get(t.id) ?? 0;
        if (!columns.has(d)) columns.set(d, []);
        columns.get(d)!.push(t.id);
    });
    return { depth, columns: [...columns.entries()].sort((a, b) => a[0] - b[0]) };
}

function statusLabel(status: string) {
    if (status === "RUN") return "RUN";
    if (status === "CACHE_HIT") return "CACHE HIT";
    if (status === "SKIP") return "SKIP";
    return "FAILED";
}

export default function Showcase() {
    const [scenarioId, setScenarioId] = useState(traces[0]?.scenario ?? "");
    const trace = useMemo(
        () => traces.find((t) => t.scenario === scenarioId) ?? traces[0],
        [scenarioId],
    );

    const [started, setStarted] = useState(false);
    const [playing, setPlaying] = useState(false);
    const [speed, setSpeed] = useState(1);
    const [clockMs, setClockMs] = useState(0);
    const [selected, setSelected] = useState<TraceTask | null>(null);
    const frame = useRef<number | null>(null);
    const lastTick = useRef<number>(0);

    const totalMs = trace?.totals.durationMs ?? 0;
    const lanes = useMemo(() => (trace ? toLanes(trace) : []), [trace]);
    const { columns } = useMemo(
        () => (trace ? layout(trace) : { columns: [] as [number, string[]][] }),
        [trace],
    );

    const reset = useCallback(() => {
        setStarted(false);
        setPlaying(false);
        setClockMs(0);
        setSelected(null);
    }, []);

    useEffect(() => {
        reset();
    }, [scenarioId, reset]);

    useEffect(() => {
        if (!playing) return;
        lastTick.current = performance.now();
        const step = (now: number) => {
            const delta = (now - lastTick.current) * speed;
            lastTick.current = now;
            setClockMs((prev) => {
                const next = prev + delta;
                if (next >= totalMs) {
                    setPlaying(false);
                    return totalMs;
                }
                return next;
            });
            frame.current = requestAnimationFrame(step);
        };
        frame.current = requestAnimationFrame(step);
        return () => {
            if (frame.current !== null) cancelAnimationFrame(frame.current);
        };
    }, [playing, speed, totalMs]);

    if (!trace) {
        return (
            <section className="showcase-empty">
                <h1>No traces are committed yet</h1>
                <p>
                    The showcase renders recorded <code>forge run</code> executions. Run{" "}
                    <code>python3 benchmarks/scripts/export-traces.py</code> to record them.
                </p>
            </section>
        );
    }

    const events = trace.events.filter((e) => e.atMs <= clockMs);
    const doneTasks = new Set(
        events.filter((e) => e.type !== "TASK_STARTED").map((e) => e.task ?? ""),
    );
    const runningTasks = new Set(
        events
            .filter((e) => e.type === "TASK_STARTED" && !doneTasks.has(e.task ?? ""))
            .map((e) => e.task ?? ""),
    );

    const baselineTasks = trace.baseline?.tasksExecuted ?? trace.repository.taskCount;
    const baselineMs = trace.baseline?.durationMs ?? trace.totals.durationMs;
    const improvement =
        baselineMs > 0 ? Math.round(((baselineMs - trace.totals.durationMs) / baselineMs) * 100) : 0;

    const changed = trace.changedFiles[0];

    return (
        <section className="showcase">
            <header className="showcase-hero">
                <p className="showcase-eyebrow">Interactive showcase: verified execution replay</p>
                <h1>One file changed. What actually needs rebuilding?</h1>
                {changed ? (
                    <p className="showcase-changed">
                        Changed: <code>{changed}</code>
                    </p>
                ) : (
                    <p className="showcase-changed">Nothing cached — this is a first build.</p>
                )}

                <div className="showcase-controls">
                    <label>
                        Scenario
                        <select
                            value={scenarioId}
                            onChange={(e) => setScenarioId(e.target.value)}
                            aria-label="Choose a scenario"
                        >
                            {traces.map((t) => (
                                <option key={t.scenario} value={t.scenario}>
                                    {t.scenarioLabel ?? t.scenario}
                                </option>
                            ))}
                        </select>
                    </label>

                    {!started ? (
                        <button
                            className="showcase-run"
                            onClick={() => {
                                setStarted(true);
                                setPlaying(true);
                                setClockMs(0);
                            }}
                        >
                            Run comparison
                        </button>
                    ) : (
                        <div className="showcase-transport">
                            <button onClick={() => setPlaying((p) => !p)}>
                                {playing ? "Pause" : "Resume"}
                            </button>
                            <button onClick={reset}>Restart</button>
                            <button
                                onClick={() => {
                                    setPlaying(false);
                                    const next = trace.events.find((e) => e.atMs > clockMs);
                                    setClockMs(next ? next.atMs : totalMs);
                                }}
                            >
                                Step
                            </button>
                            <label>
                                Speed
                                <select
                                    value={speed}
                                    onChange={(e) => setSpeed(Number(e.target.value))}
                                    aria-label="Playback speed"
                                >
                                    {SPEEDS.map((s) => (
                                        <option key={s} value={s}>
                                            {s}×
                                        </option>
                                    ))}
                                </select>
                            </label>
                        </div>
                    )}
                </div>

                <p className="showcase-measured">
                    Measured benchmark: {trace.repository.moduleCount} modules ·{" "}
                    {trace.repository.taskCount} tasks · {trace.workerCount} parallel executors ·{" "}
                    {trace.environment.cpuCores}-core {trace.environment.os}
                </p>
            </header>

            {started && (
                <div className="showcase-compare">
                    <article className="compare-card baseline">
                        <h2>Traditional CI</h2>
                        <p className="compare-metric">{(baselineMs / 1000).toFixed(2)}s</p>
                        <p className="compare-sub">rebuilds all {baselineTasks} tasks</p>
                    </article>
                    <article className="compare-card forge">
                        <h2>ForgeCI</h2>
                        <p className="compare-metric">{(trace.totals.durationMs / 1000).toFixed(2)}s</p>
                        <p className="compare-sub">
                            ran {trace.totals.tasksExecuted}, reused {trace.totals.tasksCacheHit}
                        </p>
                    </article>
                    <article className="compare-card result">
                        <h2>Result</h2>
                        <p className="compare-metric">
                            {improvement > 0 ? `${improvement}% faster` : "no gain"}
                        </p>
                        <p className="compare-sub">
                            {improvement > 0
                                ? `${(baselineMs / trace.totals.durationMs).toFixed(2)}× speedup`
                                : "this change invalidates the graph"}
                        </p>
                    </article>
                </div>
            )}

            <div className="showcase-body">
                <div className="showcase-graph" role="img" aria-label="Task dependency graph">
                    {columns.map(([d, ids]) => (
                        <div className="graph-column" key={d}>
                            {ids.map((id) => {
                                const task = trace.tasks.find((t) => t.id === id)!;
                                const isDone = started && doneTasks.has(id);
                                const isRunning = started && runningTasks.has(id);
                                const cls = [
                                    "graph-node",
                                    `status-${task.status.toLowerCase()}`,
                                    isRunning ? "is-running" : "",
                                    isDone ? "is-done" : "",
                                    started && !isDone && !isRunning ? "is-pending" : "",
                                    selected?.id === id ? "is-selected" : "",
                                ].join(" ");
                                return (
                                    <button
                                        key={id}
                                        className={cls}
                                        onClick={() => setSelected(task)}
                                        title={`${id} — ${task.reason}`}
                                    >
                                        <span className="node-name">{id}</span>
                                        {started && (
                                            <span className="node-status">{statusLabel(task.status)}</span>
                                        )}
                                    </button>
                                );
                            })}
                        </div>
                    ))}
                </div>

                <aside className="showcase-side">
                    {selected ? (
                        <div className="task-detail">
                            <h3>{selected.id}</h3>
                            <p className="task-status">{statusLabel(selected.status)}</p>
                            <p className="task-reason">{selected.reason}</p>
                            {selected.worker && <p>Executor: {selected.worker}</p>}
                            {selected.durationMs ? <p>Took {selected.durationMs} ms</p> : null}
                            {selected.dependsOn?.length ? (
                                <p className="task-deps">Depends on: {selected.dependsOn.join(", ")}</p>
                            ) : null}
                        </div>
                    ) : (
                        <p className="showcase-hint">
                            Select any task to see why it ran, was reused, or was skipped.
                        </p>
                    )}

                    {started && lanes.length > 0 && (
                        <div className="lanes">
                            <h3>Executor lanes</h3>
                            {lanes.map((lane) => (
                                <div className="lane" key={lane.id}>
                                    <span className="lane-name">{lane.id}</span>
                                    <div className="lane-track">
                                        {lane.tasks.map((t) => (
                                            <span
                                                key={`${lane.id}-${t.task}`}
                                                className={`lane-block ${t.status === "CACHE_HIT" ? "cached" : ""} ${
                                                    t.startMs <= clockMs ? "visible" : ""
                                                }`}
                                                style={{
                                                    left: `${totalMs ? (t.startMs / totalMs) * 100 : 0}%`,
                                                    width: `${totalMs ? Math.max(((t.endMs - t.startMs) / totalMs) * 100, 1) : 0}%`,
                                                }}
                                                title={`${t.task} (${t.endMs - t.startMs} ms)`}
                                            />
                                        ))}
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </aside>
            </div>

            {started && clockMs >= totalMs && (
                <p className="showcase-verdict">
                    Traditional CI rebuilt {baselineTasks} tasks. ForgeCI executed{" "}
                    {trace.totals.tasksExecuted}, reused {trace.totals.tasksCacheHit}
                    {improvement > 0 ? `, and finished ${improvement}% faster.` : "."}
                </p>
            )}

            <footer className="showcase-evidence">
                Recorded on commit <code>{trace.sourceCommit}</code>, run{" "}
                <code>{trace.benchmarkRunId}</code>, profile <code>{trace.environment.profile}</code>.
                No backend is contacted — this page replays committed traces.
            </footer>
        </section>
    );
}
