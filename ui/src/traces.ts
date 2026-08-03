// The committed execution traces the showcase replays. These are recordings of real `forge run`
// executions produced by benchmarks/scripts/export-traces.py — the showcase never computes or
// invents a number, it only renders what a trace already measured.
export type TaskStatus = "RUN" | "CACHE_HIT" | "SKIP" | "FAILED";

export interface TraceTask {
    id: string;
    status: TaskStatus;
    reason: string;
    dependsOn?: string[];
    worker?: string;
    durationMs?: number;
}

export interface TraceEvent {
    sequence: number;
    type: string;
    atMs: number;
    task?: string;
    worker?: string;
    detail?: string;
}

export interface Trace {
    schemaVersion: 1;
    scenario: string;
    scenarioLabel?: string;
    sourceCommit: string;
    benchmarkRunId: string;
    environment: {
        profile: string;
        os: string;
        arch?: string;
        cpuCores: number;
        memoryGb: number;
        javaVersion: string;
    };
    repository: { name: string; moduleCount: number; taskCount: number };
    graph: { nodes: { id: string; module: string; kind?: string }[]; edges: { from: string; to: string }[] };
    changedFiles: string[];
    workerCount: number;
    cacheState: "cold" | "warm";
    tasks: TraceTask[];
    events: TraceEvent[];
    totals: {
        durationMs: number;
        tasksExecuted: number;
        tasksCacheHit: number;
        tasksSkipped: number;
        tasksFailed?: number;
    };
    baseline?: { tasksExecuted: number; durationMs: number; taskIds?: string[] };
}

const modules = import.meta.glob("../../demo/traces/*.json", { eager: true, import: "default" });

// scenario order is the story order the showcase walks a visitor through
const ORDER = ["leaf-module", "shared-library", "warm-remote-cache", "config-change", "cold-full-build"];

export const traces: Trace[] = Object.entries(modules)
    .filter(([path]) => !path.endsWith("trace.schema.json"))
    .map(([, mod]) => mod as Trace)
    .sort((a, b) => ORDER.indexOf(a.scenario) - ORDER.indexOf(b.scenario));

export function findTrace(scenario: string): Trace | undefined {
    return traces.find((t) => t.scenario === scenario);
}
