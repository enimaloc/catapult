import type { Page } from "@playwright/test";

/**
 * DOM mutation counts of a page, for the DOM mutation budgets (dom-mutations.spec.ts): a MutationObserver
 * on document.body (childList, subtree, attributes, characterData) between startCounting and stopCounting.
 *
 * - nodes: the nodes added and removed, each with its descendants (a subtree swapped by innerHTML counts
 *   every node it held and every node it brings, not only its top-level children), each node counted once
 *   per direction however many records name it;
 * - attributes: the attribute records;
 * - text: the characterData records (a text node's data changed in place).
 *
 * The 1 s clocks ([data-live-time], time.js) rewrite their text on every tick: startCounting waits for one
 * of their ticks before it starts, and stopCounting(page, windowMs) stops half a second after the end of a
 * window of that length, so that a window holds the same number of ticks on every run.
 */

export interface MutationCounts {
  nodes: number;
  attributes: number;
  text: number;
}

/** How long startCounting waits for a clock's tick (the clocks tick every second). */
const TICK_WAIT_MS = 1500;
/** Where a window ends, after a tick: half way between two ticks, clear of their jitter. */
const TICK_OFFSET_MS = 500;

interface Counter {
  observer: MutationObserver;
  handle: (records: MutationRecord[]) => void;
  counts: MutationCounts;
  start: number;
}

/** Starts counting the page's mutations, from just after a clock's tick when the page has clocks. */
export async function startCounting(page: Page): Promise<void> {
  await page.evaluate(
    ({ tickWaitMs }) =>
      new Promise<void>((resolve) => {
        const w = window as unknown as { __domMutations?: Counter };
        w.__domMutations?.observer.disconnect();
        const counts = { nodes: 0, attributes: 0, text: 0 };
        const added = new WeakSet<Node>();
        const removed = new WeakSet<Node>();
        const tally = (node: Node, seen: WeakSet<Node>) => {
          const walker = document.createTreeWalker(node, NodeFilter.SHOW_ALL);
          for (let current: Node | null = node; current; current = walker.nextNode()) {
            if (seen.has(current)) continue;
            seen.add(current);
            counts.nodes++;
          }
        };
        const handle = (records: MutationRecord[]) => {
          for (const record of records) {
            if (record.type === "childList") {
              record.addedNodes.forEach((node) => tally(node, added));
              record.removedNodes.forEach((node) => tally(node, removed));
            } else if (record.type === "attributes") {
              counts.attributes++;
            } else {
              counts.text++;
            }
          }
        };
        const observer = new MutationObserver(handle);
        const begin = () => {
          w.__domMutations = { observer, handle, counts, start: performance.now() };
          observer.observe(document.body, { childList: true, subtree: true, attributes: true, characterData: true });
          resolve();
        };
        if (!document.querySelector("[data-live-time]")) {
          begin();
          return;
        }
        // A tick rewrites every clock's text: the first record on one of them is a tick.
        const tick = new MutationObserver((records) => {
          if (!records.some((r) => (r.target as Element).closest?.("[data-live-time]"))) return;
          tick.disconnect();
          clearTimeout(timeout);
          begin();
        });
        const timeout = setTimeout(() => {
          tick.disconnect();
          begin();
        }, tickWaitMs);
        tick.observe(document.body, { childList: true, subtree: true, characterData: true });
      }),
    { tickWaitMs: TICK_WAIT_MS },
  );
}

/**
 * Stops counting and answers the counts. With `windowMs`, waits first for the end of a window of that
 * length since startCounting (plus TICK_OFFSET_MS), and fails when the scenario already ran past it.
 */
export async function stopCounting(page: Page, windowMs?: number): Promise<MutationCounts> {
  return page.evaluate(
    ({ windowMs, offsetMs }) =>
      new Promise<MutationCounts>((resolve, reject) => {
        const w = window as unknown as { __domMutations?: Counter };
        const counter = w.__domMutations;
        if (!counter) {
          reject(new Error("stopCounting without startCounting (or the page was reloaded meanwhile)"));
          return;
        }
        const stop = () => {
          counter.handle(counter.observer.takeRecords()); // the records not delivered yet
          counter.observer.disconnect();
          delete w.__domMutations;
          resolve({ ...counter.counts });
        };
        if (windowMs === undefined) {
          stop();
          return;
        }
        const left = counter.start + windowMs + offsetMs - performance.now();
        if (left < offsetMs) {
          counter.observer.disconnect();
          reject(new Error(`the scenario ran past its ${windowMs} ms window (${Math.round(offsetMs - left)} ms over)`));
          return;
        }
        setTimeout(stop, left);
      }),
    { windowMs, offsetMs: TICK_OFFSET_MS },
  );
}
