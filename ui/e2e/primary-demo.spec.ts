import { test, expect } from "@playwright/test";

test("load root, click Begin, watch the comparison complete, and see a populated result card", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { name: /build only what changed/i })).toBeVisible();

  await page.getByRole("button", { name: "Begin" }).click();

  await expect(page.getByRole("log", { name: "Traditional build" })).toBeVisible();
  await expect(page.getByRole("log", { name: "ForgeCI build" })).toBeVisible();

  // the comparison is a real running build, not an animation — give it real time to finish
  await expect(page.locator(".result-headline")).toBeVisible({ timeout: 90_000 });

  const stats = page.locator(".result-grid .result-stat-value");
  await expect(stats).toHaveCount(6);
  for (const value of await stats.allTextContents()) {
    expect(value.trim().length).toBeGreaterThan(0);
  }

  // never a raw UUID anywhere on the primary screen
  await expect(page.locator("body")).not.toContainText(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
});
