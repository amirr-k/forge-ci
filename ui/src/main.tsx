import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import "./styles.css";

const container = document.getElementById("root");
if (!container) {
  throw new Error("no #root element to mount into — index.html and main.tsx have diverged");
}

// GitHub Pages serves the site from /<repo>/, so the router has to strip that prefix before
// matching — without it every route falls through and the app renders an empty <main>.
const basename = import.meta.env.BASE_URL;

ReactDOM.createRoot(container).render(
  <React.StrictMode>
    <BrowserRouter basename={basename}>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
);
