import { expect, test } from "@playwright/test";
import { startCounting, stopCounting, waitForQuiet } from "./perf/dom-mutations";

/**
 * The DOM mutation counter of the perf project (perf/dom-mutations.ts), on a page of its own: needs no
 * stack.
 */

test.beforeEach(async ({ page }) => {
  await page.setContent("<!doctype html><body><main id='root'></main></body>");
});

test("a subtree added counts each of its nodes once, however many records name them", async ({ page }) => {
  await startCounting(page);
  await page.evaluate(() => {
    const root = document.getElementById("root")!;
    root.innerHTML = "<section><p>one</p><p>two</p></section>"; // section, 2 p, 2 texts
    // In the same batch: a node of that subtree added again elsewhere in it, counted already
    const section = root.querySelector("section")!;
    section.appendChild(section.firstElementChild!);
  });
  // The move also removes it from where it was (p and its text), but adds nothing not counted already
  expect(await stopCounting(page)).toEqual({ nodes: 5 + 2, attributes: 0, text: 0 });
});

test("a node added then removed counts once in each direction", async ({ page }) => {
  await startCounting(page);
  await page.evaluate(() => {
    const span = document.createElement("span");
    span.textContent = "x"; // detached: not observed
    document.getElementById("root")!.appendChild(span);
    span.remove();
  });
  expect(await stopCounting(page)).toEqual({ nodes: 4, attributes: 0, text: 0 }); // span + its text, twice
});

test("attribute and text changes are counted apart from nodes", async ({ page }) => {
  await page.evaluate(() => { document.getElementById("root")!.textContent = "a"; });
  await startCounting(page);
  await page.evaluate(() => {
    const root = document.getElementById("root")!;
    root.setAttribute("data-x", "1");
    root.hidden = true;
    (root.firstChild as Text).data = "b";
  });
  expect(await stopCounting(page)).toEqual({ nodes: 0, attributes: 2, text: 1 });
});

test("waitForQuiet waits until mutations stop, and fails past its cap", async ({ page }) => {
  await startCounting(page);
  const started = Date.now();
  await page.evaluate(() => {
    let left = 5;
    const tick = () => {
      document.getElementById("root")!.append("x");
      if (--left > 0) setTimeout(tick, 200);
    };
    tick();
  });
  await waitForQuiet(page, 500, 5000);
  expect(Date.now() - started).toBeGreaterThanOrEqual(800 + 500);
  expect((await stopCounting(page)).nodes).toBe(5);

  await startCounting(page);
  await page.evaluate(() => setInterval(() => document.getElementById("root")!.append("y"), 100));
  await expect(waitForQuiet(page, 500, 1000)).rejects.toThrow(/did not settle within 1000 ms/);
  await stopCounting(page);
});

test("a window overrun fails", async ({ page }) => {
  await startCounting(page);
  await page.waitForTimeout(300);
  await expect(stopCounting(page, 100)).rejects.toThrow(/ran past its 100 ms window/);
});
