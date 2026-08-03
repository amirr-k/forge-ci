import { Link, Route, Routes } from "react-router-dom";
import Home from "./routes/Home";
import BuildDetail from "./routes/BuildDetail";
import Benchmarks from "./routes/Benchmarks";
import Architecture from "./routes/Architecture";
import Showcase from "./routes/Showcase";

// the statically hosted build has no control plane behind it, so the trace-replay showcase — the
// only route that needs no backend — becomes its landing page
const staticDemo = import.meta.env.VITE_STATIC_DEMO === "true";

export default function App() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <Link to="/" className="wordmark">
          ForgeCI
        </Link>
      </header>
      <main className="app-main">
        <Routes>
          <Route path="/" element={staticDemo ? <Showcase /> : <Home />} />
          <Route path="/showcase" element={<Showcase />} />
          {!staticDemo && <Route path="/builds/:id" element={<BuildDetail />} />}
          {!staticDemo && <Route path="/benchmarks" element={<Benchmarks />} />}
          <Route path="/architecture" element={<Architecture />} />
        </Routes>
      </main>
      <footer className="app-footer">
        <Link to="/architecture">How It Works</Link>
        {staticDemo ? (
          // the static build has no control plane to serve /api/benchmarks/latest, so it links to
          // the committed evidence the showcase actually replays instead of a route that would 404
          <a
            href="https://github.com/amirr-k/forge-ci/blob/main/docs/benchmarks.md"
            target="_blank"
            rel="noreferrer"
          >
            Benchmarks
          </a>
        ) : (
          <Link to="/benchmarks">Benchmarks</Link>
        )}
        <a href="https://github.com/amirr-k/forge-ci" target="_blank" rel="noreferrer">
          Source
        </a>
      </footer>
    </div>
  );
}
