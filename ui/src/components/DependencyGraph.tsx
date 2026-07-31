import { DemoTask } from "../api";

function moduleOf(taskName: string): string {
  return taskName.split(":")[0];
}

export default function DependencyGraph({
  baselineTasks,
  incrementalTasks,
}: {
  baselineTasks: string[];
  incrementalTasks: DemoTask[];
}) {
  const changedModules = new Set(
    incrementalTasks.filter((t) => t.reason === "source changed").map((t) => moduleOf(t.name)),
  );
  const affectedModules = new Set(incrementalTasks.map((t) => moduleOf(t.name)));
  const modules = Array.from(new Set(baselineTasks.map(moduleOf))).sort();

  return (
    <div className="graph" aria-label="module dependency graph">
      {modules.map((module) => (
        <span
          key={module}
          className={`graph-node${changedModules.has(module) ? " is-changed" : affectedModules.has(module) ? " is-affected" : ""}`}
        >
          {module}
        </span>
      ))}
    </div>
  );
}
