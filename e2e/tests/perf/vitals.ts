import type { Page } from "@playwright/test";

/** Web Vitals read from a headless Chromium page, for the timing run under the perf profile. */

export interface NavigationTiming {
  ttfb: number;
  lcp: number;
}

export interface TabTiming {
  switchMs: number;
  inp: number;
}

/** Time to first byte: the navigation entry's responseStart. */
export async function measureNavigation(page: Page, url: string): Promise<NavigationTiming> {
  await page.goto(url, { waitUntil: "load" });
  return page.evaluate(
    () =>
      new Promise<{ ttfb: number; lcp: number }>((resolve) => {
        const entries: PerformanceEntry[] = [];
        // buffered: the entries from before the observer exists; the last one is the largest paint
        new PerformanceObserver((list) => entries.push(...list.getEntries())).observe({
          type: "largest-contentful-paint",
          buffered: true,
        });
        const [navigation] = performance.getEntriesByType("navigation") as PerformanceNavigationTiming[];
        setTimeout(() => {
          const last = entries[entries.length - 1];
          resolve({ ttfb: navigation.responseStart, lcp: last ? last.startTime : 0 });
        }, 250);
      }),
  );
}

/**
 * Clicks a tab with a real mouse press and returns how long the switch takes: from the press to the
 * panel being visible and to the end of the requests it triggered (switchMs), and the longest
 * Event Timing duration of that press and click (inp, 0 when the browser reported none: under 16 ms).
 */
export async function measureTab(
  page: Page,
  tabSelector: string,
  panelSelector: string,
): Promise<TabTiming> {
  let inFlight = 0;
  const started = () => inFlight++;
  const ended = () => inFlight--;
  page.on("request", started);
  page.on("requestfinished", ended);
  page.on("requestfailed", ended);
  try {
    await page.evaluate(() => {
      const w = window as unknown as { __perf: { t0: number; events: PerformanceEntry[] } };
      w.__perf = { t0: 0, events: [] };
      document.addEventListener("pointerdown", () => (w.__perf.t0 = performance.now()), {
        capture: true,
        once: true,
      });
      new PerformanceObserver((list) => w.__perf.events.push(...list.getEntries())).observe({
        type: "event",
        durationThreshold: 16,
        buffered: true,
      } as PerformanceObserverInit);
    });
    // The tab strip scrolls sideways and can leave a tab half out of sight: press its visible part.
    const tab = await page.locator(tabSelector).boundingBox();
    const strip = await page.locator(tabSelector).locator("xpath=ancestor::mdui-tabs").boundingBox();
    if (!tab || !strip) throw new Error(`tab not displayed: ${tabSelector}`);
    const left = Math.max(tab.x, strip.x);
    const right = Math.min(tab.x + tab.width, strip.x + strip.width);
    inFlight = 0;
    await page.mouse.click((left + right) / 2, tab.y + tab.height / 2);
    await page.waitForFunction(
      (selector) => {
        const panel = document.querySelector(selector) as HTMLElement | null;
        if (!panel || !panel.checkVisibility()) return false;
        const rect = panel.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0;
      },
      panelSelector,
    );
    const visibleAt = await page.evaluate(() => performance.now());
    // The requests the tab triggers: settled once none is in flight for a quiet moment.
    for (let quiet = 0; quiet < 3; ) {
      await page.waitForTimeout(50);
      quiet = inFlight === 0 ? quiet + 1 : 0;
    }
    return await page.evaluate((visible) => {
      const w = window as unknown as { __perf: { t0: number; events: PerformanceEntry[] } };
      const { t0 } = w.__perf;
      const resources = (performance.getEntriesByType("resource") as PerformanceResourceTiming[]).filter(
        (r) => r.startTime >= t0,
      );
      const end = Math.max(visible, ...resources.map((r) => r.responseEnd));
      const durations = w.__perf.events.filter((e) => e.startTime >= t0 - 1).map((e) => e.duration);
      return { switchMs: end - t0, inp: Math.max(0, ...durations) };
    }, visibleAt);
  } finally {
    page.off("request", started);
    page.off("requestfinished", ended);
    page.off("requestfailed", ended);
  }
}
