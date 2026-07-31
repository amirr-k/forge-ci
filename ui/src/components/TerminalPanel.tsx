import { TaskLine } from "../buildProgress";

export default function TerminalPanel({ title, lines }: { title: string; lines: TaskLine[] }) {
  return (
    <div className="terminal" role="log" aria-label={title}>
      <div className="terminal-title">{title}</div>
      <div className="terminal-body">
        {lines.map((line) => (
          <div key={line.name} className={`terminal-line ${line.status}`}>
            {"> "}
            {line.detail}
          </div>
        ))}
      </div>
    </div>
  );
}
