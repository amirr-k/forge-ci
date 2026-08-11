import { useEffect, useState } from "react";
import { BenchmarksResponse, getLatestBenchmarks } from "../api";

export default function Benchmarks() {
  const [data, setData] = useState<BenchmarksResponse | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    getLatestBenchmarks()
      .then(setData)
      .catch(() => setFailed(true));
  }, []);

  const scenarios = data?.scenarios ?? [];

  return (
    <div>
      <h1 className="headline" style={{ fontSize: "1.75rem" }}>
        Benchmarks
      </h1>
      <p className="subhead">
        The committed report from <code>benchmarks/results/latest.json</code>, generated from every
        retained trial rather than typed in by hand.
      </p>
      <div className="card">
        {failed || (data && !data.available) ? (
          <p>{data?.message ?? "benchmark results are unavailable"}</p>
        ) : !data ? (
          <p>Loading…</p>
        ) : (
          <>
            <p>
              Run <code>{data.benchmarkRunId}</code> · commit <code>{data.commit}</code>
            </p>
            <table>
              <thead>
                <tr>
                  <th>Scenario</th>
                  <th>Median ms</th>
                  <th>p95 ms</th>
                  <th>Trials</th>
                </tr>
              </thead>
              <tbody>
                {scenarios.map((scenario) => (
                  <tr key={scenario.id}>
                    <td>
                      <code>{scenario.id}</code>
                    </td>
                    <td>{Math.round(scenario.stats.median_ms)}</td>
                    <td>{Math.round(scenario.stats.p95_ms)}</td>
                    <td>{scenario.stats.trials}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </div>
    </div>
  );
}
