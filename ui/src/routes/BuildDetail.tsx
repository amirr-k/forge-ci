import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { BuildEvent, BuildResponse, getBuild, subscribeToBuildEvents } from "../api";

export default function BuildDetail() {
  const { id } = useParams<{ id: string }>();
  const buildId = Number(id);
  const [build, setBuild] = useState<BuildResponse | null>(null);
  const [events, setEvents] = useState<BuildEvent[]>([]);

  useEffect(() => {
    if (!Number.isFinite(buildId)) {
      return;
    }
    getBuild(buildId).then(setBuild).catch(() => undefined);
    const poll = window.setInterval(() => {
      getBuild(buildId).then(setBuild).catch(() => undefined);
    }, 1000);
    const unsubscribe = subscribeToBuildEvents(buildId, (event) => {
      setEvents((current) => [...current, event]);
    });
    return () => {
      window.clearInterval(poll);
      unsubscribe();
    };
  }, [buildId]);

  return (
    <div>
      <h1 className="headline" style={{ fontSize: "1.75rem" }}>
        Build {buildId}
      </h1>
      {build && (
        <p className="subhead">
          State: {build.state} · Requested build machines: {build.requestedWorkerCount}
        </p>
      )}
      <details open>
        <summary style={{ cursor: "pointer", fontWeight: 600, marginBottom: "0.75rem" }}>Inspect Execution</summary>
        <div className="card">
          <table style={{ width: "100%", borderCollapse: "collapse", fontFamily: "var(--font-mono)", fontSize: "0.8rem" }}>
            <thead>
              <tr style={{ textAlign: "left" }}>
                <th>#</th>
                <th>Event</th>
                <th>Task</th>
                <th>Occurred at</th>
              </tr>
            </thead>
            <tbody>
              {events.map((event) => (
                <tr key={event.sequenceNumber}>
                  <td>{event.sequenceNumber}</td>
                  <td>{event.eventType}</td>
                  <td>{event.taskName ?? "—"}</td>
                  <td>{new Date(event.occurredAt).toLocaleTimeString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}
