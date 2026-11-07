import type { Page, Request } from "@playwright/test";

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
 *
 * Within a window, a scenario waits for what it set off to be over before it goes on (waitForQuiet): no
 * mutation (the clocks' aside) and no request in flight (the live streams aside) for a quiet period. A live
 * event's arrival is seen by waitForLiveEvent, on a page whose EventSources trackLiveEvents wrapped.
 */

export interface MutationCounts {
  nodes: number;
  attributes: number;
  text: number;
  /** With DOM_MUTATIONS_TRACE set: one line per childList record (and per attribute), to find a difference */
  trace?: string[];
}

/** Whether the counts come with their trace (DOM_MUTATIONS_TRACE, any value). */
const TRACE = !!process.env.DOM_MUTATIONS_TRACE;

/** How long startCounting waits for a clock's tick (the clocks tick every second). */
const TICK_WAIT_MS = 1500;
/** Where a window ends, after a tick: half way between two ticks, clear of their jitter. */
const TICK_OFFSET_MS = 500;

/** The quiet period of waitForQuiet, and how long it waits for one at most. */
export const QUIET_MS = 750;
export const QUIET_CAP_MS = 15_000;

interface Counter {
  trace?: string[];
  observer: MutationObserver;
  handle: (records: MutationRecord[]) => void;
  counts: MutationCounts;
  start: number;
  /** performance.now() of the last mutation outside the clocks */
  last: number;
}

/** The page's requests in flight (the live streams aside) and when the last one started or ended. */
interface Network {
  inFlight: number;
  last: number;
  off(): void;
}

const networks = new WeakMap<Page, Network>();

function watchNetwork(page: Page) {
  networks.get(page)?.off();
  const network: Network = { inFlight: 0, last: Date.now(), off: () => {} };
  // A live stream (EventSource) stays open: it is never "in flight" for quiescence.
  const counted = (request: Request) => request.resourceType() !== "eventsource";
  const started = (request: Request) => {
    if (!counted(request)) return;
    network.inFlight++;
    network.last = Date.now();
  };
  const ended = (request: Request) => {
    if (!counted(request)) return;
    network.inFlight = Math.max(0, network.inFlight - 1);
    network.last = Date.now();
  };
  page.on("request", started);
  page.on("requestfinished", ended);
  page.on("requestfailed", ended);
  network.off = () => {
    page.off("request", started);
    page.off("requestfinished", ended);
    page.off("requestfailed", ended);
  };
  networks.set(page, network);
}

/** Starts counting the page's mutations, from just after a clock's tick when the page has clocks. */
export async function startCounting(page: Page): Promise<void> {
  watchNetwork(page);
  await page.evaluate(
    ({ tickWaitMs, trace }) =>
      new Promise<void>((resolve) => {
        const w = window as unknown as { __domMutations?: Counter };
        w.__domMutations?.observer.disconnect();
        const counts = { nodes: 0, attributes: 0, text: 0 };
        const lines: string[] | undefined = trace ? [] : undefined;
        const name = (node: Node) => node instanceof Element
          ? node.tagName.toLowerCase() + (node.id ? `#${node.id}` : "") + (node.classList[0] ? `.${node.classList[0]}` : "")
          : node.nodeName;
        const added = new WeakSet<Node>();
        const removed = new WeakSet<Node>();
        const tally = (node: Node, seen: WeakSet<Node>) => {
          let n = 0;
          const walker = document.createTreeWalker(node, NodeFilter.SHOW_ALL);
          for (let current: Node | null = node; current; current = walker.nextNode()) {
            if (seen.has(current)) continue;
            seen.add(current);
            n++;
          }
          counts.nodes += n;
          return n;
        };
        const clock = (record: MutationRecord) =>
          !!(record.target as Element).closest?.("[data-live-time]")
          || !!record.target.parentElement?.closest("[data-live-time]");
        const handle = (records: MutationRecord[]) => {
          for (const record of records) {
            if (w.__domMutations && !clock(record)) w.__domMutations.last = performance.now();
            if (record.type === "childList") {
              let plus = 0;
              let minus = 0;
              record.addedNodes.forEach((node) => { plus += tally(node, added); });
              record.removedNodes.forEach((node) => { minus += tally(node, removed); });
              lines?.push(`${Math.round(performance.now())} ${name(record.target)} +${plus} -${minus}`);
            } else if (record.type === "attributes") {
              counts.attributes++;
              lines?.push(`${Math.round(performance.now())} ${name(record.target)} @${record.attributeName}`);
            } else {
              counts.text++;
            }
          }
        };
        const observer = new MutationObserver(handle);
        const begin = () => {
          const now = performance.now();
          w.__domMutations = { observer, handle, counts, start: now, last: now, trace: lines };
          observer.observe(document.body, { childList: true, subtree: true, attributes: true, characterData: true });
          resolve();
        };
        if (!document.querySelector("[data-live-time]")) {
          begin();
          return;
        }
        // A tick rewrites every clock's text: the first record on one of them is a tick.
        const tick = new MutationObserver((records) => {
          if (!records.some(clock)) return;
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
    { tickWaitMs: TICK_WAIT_MS, trace: TRACE },
  );
}

/**
 * Stops counting and answers the counts. With `windowMs`, waits first for the end of a window of that
 * length since startCounting (plus TICK_OFFSET_MS), and fails when the scenario already ran past it.
 */
export async function stopCounting(page: Page, windowMs?: number): Promise<MutationCounts> {
  networks.get(page)?.off();
  networks.delete(page);
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
          resolve({ ...counter.counts, ...(counter.trace ? { trace: counter.trace } : {}) });
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

/**
 * Waits until the page has had no mutation (outside the clocks) and no request in flight for `quietMs`;
 * fails when that takes over `capMs`.
 */
export async function waitForQuiet(page: Page, quietMs = QUIET_MS, capMs = QUIET_CAP_MS): Promise<void> {
  const network = networks.get(page);
  if (!network) throw new Error("waitForQuiet without startCounting");
  const deadline = Date.now() + capMs;
  for (;;) {
    const sinceMutation = await page.evaluate(() => {
      const counter = (window as unknown as { __domMutations?: Counter }).__domMutations;
      if (!counter) throw new Error("waitForQuiet without startCounting");
      counter.handle(counter.observer.takeRecords());
      return performance.now() - counter.last;
    });
    const sinceNetwork = network.inFlight > 0 ? 0 : Date.now() - network.last;
    if (sinceMutation >= quietMs && sinceNetwork >= quietMs) return;
    if (Date.now() > deadline) {
      throw new Error(`the page did not settle within ${capMs} ms (${network.inFlight} request(s) in flight, `
        + `last mutation ${Math.round(sinceMutation)} ms ago)`);
    }
    await page.waitForTimeout(Math.max(50, Math.min(quietMs - sinceMutation, quietMs - sinceNetwork, 250)));
  }
}

/**
 * Wraps the page's EventSources (before it loads: call before page.goto) so that every live event they
 * hand to a listener is counted, for waitForLiveEvent.
 */
export async function trackLiveEvents(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const w = window as unknown as { __liveEvents: number };
    w.__liveEvents = 0;
    const add = EventSource.prototype.addEventListener;
    EventSource.prototype.addEventListener = function (this: EventSource, type: string, listener: unknown,
                                                       options?: boolean | AddEventListenerOptions) {
      if (typeof listener !== "function" || type === "open" || type === "error") {
        return add.call(this, type, listener as EventListener, options);
      }
      return add.call(this, type, function (this: EventSource, event: Event) {
        w.__liveEvents++;
        return (listener as EventListener).call(this, event);
      }, options);
    } as typeof EventSource.prototype.addEventListener;
  });
}

/** How many live events the page's listeners got so far (trackLiveEvents). */
export async function liveEvents(page: Page): Promise<number> {
  return page.evaluate(() => (window as unknown as { __liveEvents?: number }).__liveEvents ?? -1);
}

/** Waits until the page's listeners got more than `seen` live events; fails after `capMs`. */
export async function waitForLiveEvent(page: Page, seen: number, capMs = QUIET_CAP_MS): Promise<void> {
  if (seen < 0) throw new Error("waitForLiveEvent on a page without trackLiveEvents");
  await page.waitForFunction((seen) => (window as unknown as { __liveEvents: number }).__liveEvents > seen, seen,
    { timeout: capMs });
}
