export default function Architecture() {
  return (
    <div>
      <h1 className="headline" style={{ fontSize: "1.75rem" }}>
        How it works
      </h1>
      <p className="subhead">
        ForgeCI reads a repository's task dependency graph, works out which tasks a change affects,
        reuses cached outputs for everything else, and runs independent tasks concurrently across
        build machines.
      </p>
      <div className="card">
        <ul>
          <li>
            <strong>Control plane</strong> — a MySQL-backed service that owns accepted build and task
            state, schedules ready tasks, and tracks build machine leases.
          </li>
          <li>
            <strong>Build machines</strong> — worker processes that claim a task, run it in an isolated
            container, and report the result. A build machine failing mid-task never blocks the
            build: its lease expires and another machine picks the task up.
          </li>
          <li>
            <strong>Cache</strong> — a task's cache key is derived from its declared inputs, its
            dependencies' outputs, and the toolchain running it. A matching key means the output can
            be reused instead of rebuilt.
          </li>
          <li>
            <strong>Artifact storage</strong> — S3-compatible object storage is authoritative for
            build output bytes.
          </li>
          <li>
            <strong>Event delivery</strong> — build and task events are delivered at-least-once, never
            exactly-once; every consumer is written to be idempotent under redelivery.
          </li>
        </ul>
      </div>
    </div>
  );
}
