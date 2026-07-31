export type DemoScenarioId =
  | "no-change"
  | "leaf-module"
  | "shared-core"
  | "config-toolchain"
  | "worker-crash"
  | "failed-test";

export interface DemoTask {
  name: string;
  dependsOn: string[];
  reason: string;
}

export interface DemoBuildResponse {
  baselineBuildId: number;
  incrementalBuildId: number;
  scenario: DemoScenarioId;
  workerCount: number;
  baselineTasks: string[];
  incrementalTasks: DemoTask[];
  unaffectedTasks: string[];
}

export type BuildState = "CREATED" | "PLANNING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELED";

export interface BuildResponse {
  id: number;
  projectId: number;
  planSubmissionId: number;
  revision: string;
  baseRevision: string;
  state: BuildState;
  requestedWorkerCount: number;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export type BuildEventType =
  | "BUILD_CREATED"
  | "BUILD_PLANNING"
  | "BUILD_RUNNING"
  | "BUILD_SUCCEEDED"
  | "BUILD_FAILED"
  | "BUILD_CANCELED"
  | "TASK_RUN_CREATED"
  | "TASK_RUN_READY"
  | "TASK_RUN_LEASED"
  | "TASK_RUN_RUNNING"
  | "TASK_RUN_SUCCEEDED"
  | "TASK_RUN_FAILED"
  | "TASK_RUN_RETRY_WAIT"
  | "TASK_RUN_CACHED"
  | "TASK_RUN_SKIPPED";

export interface BuildEvent {
  sequenceNumber: number;
  eventType: BuildEventType;
  taskRunId: number | null;
  taskName: string | null;
  occurredAt: string;
  payload: Record<string, unknown>;
}

export interface CrashWorkerResponse {
  workerId: number;
}

export interface BenchmarksResponse {
  available: boolean;
  message: string;
}

class DemoApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}

async function parseJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => ({}) as { message?: string });
    throw new DemoApiError(body.message ?? `request failed (${response.status})`, response.status);
  }
  return response.json() as Promise<T>;
}

export async function startDemoBuild(scenario: DemoScenarioId, workerCount: number): Promise<DemoBuildResponse> {
  const response = await fetch("/api/demo/builds", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ scenario, workerCount }),
  });
  return parseJson<DemoBuildResponse>(response);
}

export async function crashWorker(buildId: number): Promise<CrashWorkerResponse> {
  const response = await fetch(`/api/demo/builds/${buildId}/crash-worker`, { method: "POST" });
  return parseJson<CrashWorkerResponse>(response);
}

export async function getBuild(buildId: number): Promise<BuildResponse> {
  const response = await fetch(`/api/builds/${buildId}`);
  return parseJson<BuildResponse>(response);
}

export async function getLatestBenchmarks(): Promise<BenchmarksResponse> {
  const response = await fetch("/api/benchmarks/latest");
  return parseJson<BenchmarksResponse>(response);
}

/** Live per-task/build events for one build. Returns an unsubscribe function. */
export function subscribeToBuildEvents(buildId: number, onEvent: (event: BuildEvent) => void): () => void {
  const source = new EventSource(`/api/builds/${buildId}/events`);
  source.addEventListener("build-event", (message) => {
    onEvent(JSON.parse((message as MessageEvent).data) as BuildEvent);
  });
  return () => source.close();
}

export { DemoApiError };
