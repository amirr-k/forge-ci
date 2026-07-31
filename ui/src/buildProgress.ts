import { useEffect, useRef, useState } from "react";
import { BuildEvent, BuildState, getBuild, subscribeToBuildEvents } from "./api";

export type TaskStatus = "reused" | "affected" | "running" | "succeeded" | "failed" | "skipped";

export interface TaskLine {
  name: string;
  status: TaskStatus;
  detail: string;
}

const RUNNING_TYPES = new Set(["TASK_RUN_RUNNING", "TASK_RUN_RETRY_WAIT", "TASK_RUN_LEASED"]);

function statusForEvent(eventType: BuildEvent["eventType"]): TaskStatus | null {
  switch (eventType) {
    case "TASK_RUN_SUCCEEDED":
      return "succeeded";
    case "TASK_RUN_FAILED":
      return "failed";
    case "TASK_RUN_SKIPPED":
      return "skipped";
    case "TASK_RUN_CACHED":
      return "reused";
    default:
      return RUNNING_TYPES.has(eventType) ? "running" : null;
  }
}

function detailFor(status: TaskStatus, name: string): string {
  switch (status) {
    case "reused":
      return `${name} — reused previous output`;
    case "affected":
      return `${name} — queued`;
    case "running":
      return `${name} — running…`;
    case "succeeded":
      return `${name} — done`;
    case "failed":
      return `${name} — FAILED`;
    case "skipped":
      return `${name} — skipped (upstream failed)`;
  }
}

/** Live per-task lines and elapsed time for one build, from its initial roster plus its SSE event stream. */
export function useBuildProgress(
  buildId: number | null,
  initialTasks: { name: string; status: TaskStatus }[],
): { lines: TaskLine[]; elapsedMs: number; buildState: BuildState | null } {
  const [lines, setLines] = useState<TaskLine[]>(() =>
    initialTasks.map((t) => ({ name: t.name, status: t.status, detail: detailFor(t.status, t.name) })),
  );
  const [elapsedMs, setElapsedMs] = useState(0);
  const [buildState, setBuildState] = useState<BuildState | null>(null);
  const startRef = useRef<number>(Date.now());
  const doneRef = useRef(false);

  useEffect(() => {
    if (buildId == null) {
      return;
    }
    startRef.current = Date.now();
    doneRef.current = false;
    setElapsedMs(0);

    const tick = window.setInterval(() => {
      if (!doneRef.current) {
        setElapsedMs(Date.now() - startRef.current);
      }
    }, 100);

    const poll = window.setInterval(() => {
      getBuild(buildId)
        .then((build) => {
          setBuildState(build.state);
          if (build.state === "SUCCEEDED" || build.state === "FAILED" || build.state === "CANCELED") {
            doneRef.current = true;
            if (build.startedAt && build.completedAt) {
              setElapsedMs(new Date(build.completedAt).getTime() - new Date(build.startedAt).getTime());
            }
            window.clearInterval(poll);
          }
        })
        .catch(() => undefined);
    }, 500);

    const unsubscribe = subscribeToBuildEvents(buildId, (event) => {
      if (!event.taskName) {
        return;
      }
      const status = statusForEvent(event.eventType);
      if (!status) {
        return;
      }
      setLines((current) =>
        current.map((line) => (line.name === event.taskName ? { ...line, status, detail: detailFor(status, line.name) } : line)),
      );
    });

    return () => {
      window.clearInterval(tick);
      window.clearInterval(poll);
      unsubscribe();
    };
  }, [buildId]);

  useEffect(() => {
    setLines(initialTasks.map((t) => ({ name: t.name, status: t.status, detail: detailFor(t.status, t.name) })));
  }, [initialTasks]);

  return { lines, elapsedMs, buildState };
}

export function formatElapsed(ms: number): string {
  return `${(ms / 1000).toFixed(1)}s`;
}
