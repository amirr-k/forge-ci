import { test, expect } from "@playwright/test";
import { createServer, type Server } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

// Serves ui/dist under /forge-ci/ — the exact prefix GitHub Pages uses. A demo built for that
// prefix but routed at "/" renders an empty <main>, which is invisible to a curl of index.html,
// so this has to be a real browser check against the real base path.
const BASE = "/forge-ci/";
const dist = join(fileURLToPath(new URL("../dist", import.meta.url)));

const TYPES: Record<string, string> = {
    ".html": "text/html",
    ".js": "text/javascript",
    ".css": "text/css",
    ".json": "application/json",
    ".svg": "image/svg+xml",
};

let server: Server;
let origin: string;

test.beforeAll(async () => {
    server = createServer(async (req, res) => {
        const url = (req.url ?? "/").split("?")[0];
        const rel = url.startsWith(BASE) ? url.slice(BASE.length) : url.replace(/^\//, "");
        const target = normalize(join(dist, rel === "" ? "index.html" : rel));
        if (!target.startsWith(dist)) {
            res.writeHead(403).end();
            return;
        }
        try {
            const body = await readFile(target);
            res.writeHead(200, { "content-type": TYPES[extname(target)] ?? "application/octet-stream" });
            res.end(body);
        } catch {
            // SPA fallback, the same way Pages serves an unknown path
            res.writeHead(200, { "content-type": "text/html" });
            res.end(await readFile(join(dist, "index.html")));
        }
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    origin = `http://127.0.0.1:${typeof address === "object" && address ? address.port : 0}`;
});

test.afterAll(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()));
});

test("the static demo renders its showcase at the Pages base path", async ({ page }) => {
    const failed: string[] = [];
    page.on("requestfailed", (r) => failed.push(r.url()));
    page.on("pageerror", (e) => failed.push(`pageerror: ${e.message}`));

    await page.goto(`${origin}${BASE}`);

    // the actual regression: the shell used to render while <main> stayed empty
    await expect(page.getByRole("heading", { name: /what actually needs rebuilding/i })).toBeVisible();
    await expect(page.getByRole("button", { name: "Run comparison" })).toBeVisible();

    expect(failed, `failed requests: ${failed.join(", ")}`).toHaveLength(0);
});

test("running the comparison reports measured task counts", async ({ page }) => {
    await page.goto(`${origin}${BASE}`);
    await page.getByRole("button", { name: "Run comparison" }).click();

    await expect(page.getByRole("heading", { name: "Traditional CI" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "ForgeCI" })).toBeVisible();
    await expect(page.getByText(/ran \d+, reused \d+/)).toBeVisible();
});

// Webfonts are the one deliberate third-party request: they are cosmetic, cached, and the page
// renders fully without them. Everything else must stay local — no control plane, no cloud.
const ALLOWED_EXTERNAL = /^https:\/\/fonts\.(googleapis|gstatic)\.com\//;

test("the demo contacts no backend, AWS, or OCI endpoint", async ({ page }) => {
    const offending: string[] = [];
    page.on("request", (r) => {
        const url = r.url();
        if (url.includes("/api/")) offending.push(url);
        if (/amazonaws\.com|oraclecloud\.com/.test(url)) offending.push(url);
        if (!url.startsWith(origin) && !url.startsWith("data:") && !ALLOWED_EXTERNAL.test(url)) {
            offending.push(url);
        }
    });

    await page.goto(`${origin}${BASE}`);
    await page.getByRole("button", { name: "Run comparison" }).click();
    await page.waitForTimeout(1500);

    expect(offending, `unexpected outbound requests: ${offending.join(", ")}`).toHaveLength(0);
});

test("the demo still renders when every external request is blocked", async ({ page }) => {
    await page.route(/^https?:\/\/(?!127\.0\.0\.1)/, (route) => route.abort());

    await page.goto(`${origin}${BASE}`);
    await expect(page.getByRole("heading", { name: /what actually needs rebuilding/i })).toBeVisible();
    await page.getByRole("button", { name: "Run comparison" }).click();
    await expect(page.getByText(/ran \d+, reused \d+/)).toBeVisible();
});
