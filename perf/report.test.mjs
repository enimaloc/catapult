// node --test perf/report.test.mjs
import assert from "node:assert/strict";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { after, describe, test } from "node:test";
import { fileURLToPath } from "node:url";
import {
    compare, containerStats, domMutations, findPrevious, jvmOverhead, jvmStats, parseMemory, parsePercent, readPreviousReport, renderMarkdown,
    requestGroups, sizing, thresholds, writeReport,
} from "./report.mjs";

const FIXTURES = join(dirname(fileURLToPath(import.meta.url)), "fixtures", "reports");
const PREVIOUS = "20260101-000000";
const CURRENT = "20260102-000000";
const LATER = "20260103-000000";

const scratch = [];
/** A copy of the fixture runs `names` in a new temporary directory: the reports are written there. */
function runs(...names) {
    const dir = mkdtempSync(join(tmpdir(), "perf-report-"));
    scratch.push(dir);
    for (const name of names) cpSync(join(FIXTURES, name), join(dir, name), { recursive: true });
    return dir;
}
after(() => scratch.forEach((dir) => rmSync(dir, { recursive: true, force: true })));

describe("parseMemory", () => {
    test("reads docker stats' MemUsage in MiB, whatever the unit", () => {
        assert.equal(parseMemory("512.5MiB / 31.2GiB"), 512.5);
        assert.equal(parseMemory("1.5GiB / 31.2GiB"), 1536);
        assert.equal(parseMemory("2048KiB / 1GiB"), 2);
        assert.equal(parseMemory("0B / 0B"), 0);
    });

    test("is null when unreadable", () => {
        assert.equal(parseMemory("-- / --"), null);
        assert.equal(parseMemory("12XB"), null);
    });
});

test("parsePercent reads CPUPerc, over 100 % for more than a core", () => {
    assert.equal(parsePercent("12.34%"), 12.34);
    assert.equal(parsePercent("250.00%"), 250);
    assert.equal(parsePercent("--"), null);
});

describe("containerStats", () => {
    const csv = readFileSync(join(FIXTURES, CURRENT, "docker-stats.csv"), "utf8");

    test("averages and peaks each container's CPU and memory, skipping unreadable samples", () => {
        const { containers } = containerStats(csv);
        assert.deepEqual(containers, [
            { name: "api", samples: 3, cpuAvg: 30, cpuPeak: 50, memAvgMiB: 1024, memPeakMiB: 1536 },
            { name: "web", samples: 2, cpuAvg: 25, cpuPeak: 30, memAvgMiB: 896, memPeakMiB: 1024 },
        ]);
    });

    test("the stack's peak is the peak of the per-sample sums", () => {
        assert.deepEqual(containerStats(csv).total,
            { cpuAvg: 55, cpuPeak: 70, cpuSustained: 70, memAvgMiB: 1920, memPeakMiB: 2304 });
    });

    test("the sustained CPU leaves the first minute out", () => {
        const lines = ["0,api-1,900%,1GiB / 8GiB"];
        for (let t = 60; t < 160; t += 5) lines.push(`${t},api-1,${t === 100 ? 300 : 100}%,1GiB / 8GiB`);
        const { total } = containerStats(lines.join("\n"));
        assert.equal(total.cpuPeak, 900);
        assert.equal(total.cpuSustained, 100);
    });

    test("without a time column, the stack's peak is the sum of the peaks", () => {
        const { total } = containerStats("a-1,10%,100MiB / 1GiB\nb-1,20%,200MiB / 1GiB\na-1,30%,50MiB / 1GiB\n");
        assert.equal(total.cpuPeak, 50);
        assert.equal(total.memPeakMiB, 300);
    });

    test("nothing measured, no total", () => {
        assert.deepEqual(containerStats("time,container,cpu,memory\n"), { containers: [], total: null });
    });
});

test("jvmStats gives each app's peak used heap and non-heap, and the apps' peak used sum", () => {
    const csv = readFileSync(join(FIXTURES, CURRENT, "jvm-memory.csv"), "utf8");
    assert.deepEqual(jvmStats(csv), {
        apps: [
            { name: "api", samples: 2, heapPeakMiB: 300, nonHeapPeakMiB: 100, usedPeakMiB: 400, heapMaxMiB: 1024 },
            { name: "web", samples: 2, heapPeakMiB: 100, nonHeapPeakMiB: 60, usedPeakMiB: 150, heapMaxMiB: 1024 },
        ],
        usedPeakMiB: 510,
    });
    assert.deepEqual(jvmStats("time,app,heapUsed,nonHeapUsed,heapMax\n"), { apps: [], usedPeakMiB: null });
});

test("jvmOverhead is the median of RSS minus used memory, on samples paired by time", () => {
    const stats = ["time,container,cpu,memory", "100,catapult-verify-perf-api-1,1%,500MiB / 1GiB",
        "105,catapult-verify-perf-api-1,1%,600MiB / 1GiB", "110,catapult-verify-perf-api-1,1%,700MiB / 1GiB",
        "100,catapult-verify-perf-db-1,1%,50MiB / 1GiB"].join("\n");
    const mib = 1024 * 1024;
    const jvm = ["time,app,heapUsed,nonHeapUsed,heapMax", `101,api,${300 * mib},${100 * mib},0`,
        `106,api,${350 * mib},${100 * mib},0`, `111,api,${400 * mib},${100 * mib},0`,
        `200,api,${100 * mib},0,0`].join("\n"); // the last one has no docker stats sample near it
    assert.deepEqual(jvmOverhead(stats, jvm), { api: 150 }); // 100, 150, 200
});

describe("sizing", () => {
    test("keeps the sustained CPU under 70 % and the peak RSS under 75 % without JVM figures", () => {
        assert.deepEqual(sizing({ cpuSustained: 70, memPeakMiB: 2304 }),
            { vcpus: 1, ramGiB: 3, memoryMiB: 2304, memoryBasis: "rss" });
        assert.deepEqual(sizing({ cpuSustained: 250, cpuPeak: 900, memPeakMiB: 4000 }),
            { vcpus: 4, ramGiB: 6, memoryMiB: 4000, memoryBasis: "rss" });
        assert.equal(sizing(null), null);
    });

    const containers = [{ name: "api", memPeakMiB: 600 }, { name: "db", memPeakMiB: 100 }];

    test("a JVM counts its peak used memory plus its native overhead when that is over its RSS", () => {
        const jvm = { apps: [{ name: "api", usedPeakMiB: 700 }], usedPeakMiB: 700 };
        const result = sizing({ cpuSustained: 70, memPeakMiB: 5000 }, jvm, containers, { api: 180 });
        assert.equal(result.memoryMiB, 980); // api max(600, 700 + 180), db 100
        assert.equal(result.ramGiB, 2);
        assert.equal(result.memoryBasis, "jvm");
        assert.deepEqual(result.terms[0], { name: "api", rssPeakMiB: 600, usedPeakMiB: 700, overheadMiB: 180,
            overheadMeasured: true, memoryMiB: 880 });
        assert.deepEqual(result.terms[1], { name: "db", rssPeakMiB: 100, memoryMiB: 100 });
    });

    test("a JVM that uses little counts its RSS", () => {
        const jvm = { apps: [{ name: "api", usedPeakMiB: 150 }], usedPeakMiB: 150 };
        const result = sizing({ cpuSustained: 70, memPeakMiB: 5000 }, jvm, containers, { api: 180 });
        assert.equal(result.terms[0].memoryMiB, 600); // max(600, 150 + 180)
        assert.equal(result.memoryMiB, 700);
        assert.equal(result.ramGiB, 1);
    });

    test("an overhead not measured is taken as 200 MiB", () => {
        const jvm = { apps: [{ name: "api", usedPeakMiB: 500 }], usedPeakMiB: 500 };
        const [api] = sizing({ cpuSustained: 70, memPeakMiB: 5000 }, jvm, containers).terms;
        assert.equal(api.memoryMiB, 700);
        assert.equal(api.overheadMeasured, false);
    });
});

describe("k6 summary", () => {
    const summary = JSON.parse(readFileSync(join(FIXTURES, CURRENT, "k6-summary.json"), "utf8"));

    test("thresholds are read, a true value being a failure; the group submetrics are left out", () => {
        assert.deepEqual(thresholds(summary), [
            { metric: "http_req_duration{kind:request}", expression: "p(95)<500", ok: true },
            { metric: "http_req_failed", expression: "rate<0.01", ok: true },
            { metric: "ws_unexpected_close", expression: "count==0", ok: false },
        ]);
    });

    test("each group has its requests, p50, p95 and errors", () => {
        assert.deepEqual(requestGroups(summary), [
            { name: "channel", requests: 40, p50: 80, p95: 300, errors: 0, errorRate: 0 },
            { name: "widget", requests: 800, p50: 12.3, p95: 30.1, errors: 2, errorRate: 0.25 },
        ]);
    });
});

test("domMutations judges the nodes on the budget, and fails a scenario without one", () => {
    assert.deepEqual(domMutations([
        { scenario: "a", nodes: 10, attributes: 50, text: 5, budget: 10 },
        { scenario: "b", nodes: 11, attributes: 0, text: 0, budget: 10 },
        { scenario: "c", nodes: 1, attributes: 0, text: 0, budget: null },
    ]).map(({ scenario, budget, ok }) => ({ scenario, budget, ok })), [
        { scenario: "a", budget: 10, ok: true },
        { scenario: "b", budget: 10, ok: false },
        { scenario: "c", budget: null, ok: false },
    ]);
    assert.deepEqual(domMutations(null), []);
});

describe("findPrevious", () => {
    test("is the latest earlier run with a report", () => {
        const dir = runs(PREVIOUS, CURRENT, LATER);
        assert.equal(findPrevious(join(dir, CURRENT)), join(dir, PREVIOUS));
    });

    test("is null without an earlier run", () => {
        const dir = runs(CURRENT, LATER);
        assert.equal(findPrevious(join(dir, CURRENT)), null);
    });

    test("prefers the same load, then a run with a load run, then the latest", () => {
        const dir = runs(PREVIOUS, CURRENT);
        const earlier = (name, report) => {
            mkdirSync(join(dir, name));
            writeFileSync(join(dir, name, "report.json"), JSON.stringify({ run: name, ...report }));
        };
        earlier("20260101-100000", { load: { streamers: 100, viewers: 1000, duration: "30m" }, requests: {} });
        earlier("20260101-200000", { load: null, requests: null });
        const current = join(dir, CURRENT);
        assert.equal(findPrevious(current, { streamers: 10, viewers: 100, duration: "2m", k6Image: "x" }),
            join(dir, PREVIOUS));
        assert.equal(findPrevious(current, { streamers: 5, viewers: 5, duration: "1m" }), join(dir, "20260101-100000"));
        assert.equal(findPrevious(current, null), join(dir, "20260101-200000"));
    });

    test("a previous run without a report.json says so", () => {
        const dir = runs(CURRENT);
        assert.throws(() => readPreviousReport(join(dir, "nowhere")), /nowhere has no report\.json to compare with/);
        assert.throws(() => writeReport(join(dir, CURRENT), join(dir, "nowhere")), /has no report\.json/);
    });
});

describe("compare", () => {
    test("gives the absolute and relative difference of what both runs measured", () => {
        const dir = runs(PREVIOUS, CURRENT);
        const report = writeReport(join(dir, CURRENT));
        const find = (section, name, field) => report.previous.deltas.find(
            (d) => d.section === section && d.name === name && d.field === field);
        assert.equal(report.previous.run, PREVIOUS);
        assert.equal(report.previous.sameLoad, true);
        assert.deepEqual(find("requests", "widget", "p95"),
            { section: "requests", name: "widget", field: "p95", before: 25, after: 30.1, delta: 5.1, pct: 20.4 });
        assert.deepEqual(find("requests", "widget", "errorRate"),
            { section: "requests", name: "widget", field: "errorRate", before: 0, after: 0.25, delta: 0.25, pct: null });
        assert.equal(find("pages", "home (ttfb)", "p95").pct, -12.5);
        assert.equal(find("containers", "api", "cpuAvg").pct, 50);
        assert.equal(find("containers", "total", "cpuPeak").delta, 10);
        assert.deepEqual(find("dom", "dock-events", "nodes"),
            { section: "dom", name: "dock-events", field: "nodes", before: 9282, after: 9300, delta: 18, pct: 0.2 });
        assert.equal(find("dom", "dock-events", "text").pct, null);
        assert.equal(find("dom", "idle-60s", "nodes"), undefined);
        // Measured in one run only
        assert.equal(find("requests", "channel", "p95"), undefined);
        assert.equal(find("containers", "web", "cpuAvg"), undefined);
    });

    test("says when the load was not the same", () => {
        const result = compare({ load: { streamers: 100, viewers: 1000, duration: "30m" } },
            { run: "x", load: { streamers: 10, viewers: 100, duration: "2m" } });
        assert.equal(result.sameLoad, false);
        assert.match(renderMarkdown({
            run: "y", thresholds: [], pages: [], containers: [], outbound: null, previous: result,
        }), /Not the same load/);
    });

    test("there is nothing to compare without a previous report", () => {
        assert.equal(compare({}, null), null);
    });
});

describe("writeReport", () => {
    test("writes report.md and report.json, with the outbound traffic and the machine size", () => {
        const dir = runs(PREVIOUS, CURRENT);
        writeReport(join(dir, CURRENT));
        const markdown = readFileSync(join(dir, CURRENT, "report.md"), "utf8");
        const json = JSON.parse(readFileSync(join(dir, CURRENT, "report.json"), "utf8"));
        assert.match(markdown, /\| `ws_unexpected_close` \| `count==0` \| \*\*FAIL\*\* \|/);
        assert.match(markdown, /\| widget \| 800 \| 12\.3 \| 30\.1 \| 2 \(0\.25 %\) \|/);
        assert.match(markdown, /\| channel \| lcp \| 3000 \| 3000 \| 2500 \| \*\*FAIL\*\* \(slow\) \|/);
        // api: overhead median of 512 - 300 and 1536 - 400, max(1536, 400 + 212); web: max(1024, 150 + 658)
        assert.match(markdown, /\*\*1 vCPU and 4 GiB of RAM\*\*/);
        assert.match(markdown, /2560 MiB of memory \(in MiB: api 1536 = max\(peak RSS 1536, peak used 400 \+ native 212\); /);
        assert.match(markdown, /web 1024 = max\(peak RSS 1024, peak used 150 \+ native 658\)/);
        assert.match(markdown, /\| api \| 2 \| 300 MiB \| 100 MiB \| 400 MiB \| 1024 MiB \|/);
        assert.match(markdown, /`ws_unexpected_close` counts the live streams \(SSE/);
        assert.match(markdown, /## Outbound traffic\n\n1 line\(s\)[^\n]*\n\n```\napi-1 {2}\| java\.net\.UnknownHostException/);
        assert.match(markdown, /Run 20260101-000000 \(10 streamers, 100 viewers, 2m\)/);
        assert.match(markdown, /\| containers \| api \| avg CPU \(%\) \| 20 \| 30 \| \+10 \| \+50 % \|/);
        assert.match(markdown, /## DOM mutations\n/);
        assert.match(markdown, /\| idle-60s \| 600 \| 0 \| 0 \| 600 \| – \| pass \|/);
        assert.match(markdown, /\| dock-events \| 9300 \| 1300 \| 2 \| 9282 \| \+18 \(\+0\.2 %\) \| \*\*FAIL\*\* \|/);
        assert.match(markdown, /\| dom \| dock-events \| nodes added and removed \| 9282 \| 9300 \| \+18 \| \+0\.2 % \|/);
        assert.equal(json.sizing.memoryBasis, "jvm");
        assert.equal(json.requests.unexpectedStreamCloses, 2);
    });

    test("without a previous report, says there is nothing to compare with", () => {
        const dir = runs(CURRENT);
        const report = writeReport(join(dir, CURRENT));
        assert.equal(report.previous, null);
        assert.match(readFileSync(join(dir, CURRENT, "report.md"), "utf8"),
            /## Compared with the previous run\n\nNo earlier report: nothing to compare with\./);
    });
});
