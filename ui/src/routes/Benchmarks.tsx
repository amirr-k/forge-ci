import { useEffect, useState } from "react";
import { BenchmarksResponse, getLatestBenchmarks } from "../api";

export default function Benchmarks() {
  const [data, setData] = useState<BenchmarksResponse | null>(null);

  useEffect(() => {
    getLatestBenchmarks().then(setData).catch(() => undefined);
  }, []);

  return (
    <div>
      <h1 className="headline" style={{ fontSize: "1.75rem" }}>
        Benchmarks
      </h1>
      <p className="subhead">Reproducible benchmark results, captured on fixed hardware.</p>
      <div className="card">
        {data && !data.available ? (
          <p>{data.message}</p>
        ) : data ? (
          <pre>{JSON.stringify(data, null, 2)}</pre>
        ) : (
          <p>Loading…</p>
        )}
      </div>
    </div>
  );
}
