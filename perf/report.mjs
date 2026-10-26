#!/usr/bin/env node
// The report of a timing run (perf/run.sh): reads the files of a run directory and writes report.md and
// report.json next to them, compared with an earlier run's report.json.
//
// Usage: node perf/report.mjs <run dir> [<previous run dir>]
//   Without a previous run directory, the latest sibling directory (by name, so by timestamp) that comes
//   before <run dir> and holds a report.json is used, if any.
//
// Reads, each one optional:
//   load.json         {streamers, viewers, duration, k6Image}, written by run.sh before the load run
//   k6-summary.json   k6's --summary-export: thresholds, and per-group submetrics ({group:...})
//   docker-stats.csv  time,container,cpu,memory lines (docker stats every 5 s)
//   jvm-memory.csv    time,app,heapUsed,nonHeapUsed,heapMax lines in bytes (actuator metrics every 5 s)
//   pages.json        the page timings: [{name, metric, samples, p50, p95, budget, note?}]
//   outbound.txt      log lines with a connection error towards a host outside the stack

import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** Headroom kept on the measured peaks when sizing a machine: CPU under 70 %, memory under 75 %. */
export const CPU_TARGET = 0.7;
export const MEMORY_TARGET = 0.75;
/**
 * The first minute of a load run is left out of the CPU the machine is sized on: the JVMs compile the hot
 * paths then, and every streamer signs in at once. Its peak still shows in the report.
 */
export const WARMUP_SECONDS = 60;

const MEMORY_UNITS = {
    b: 1 / (1024 * 1024),
    kib: 1 / 1024,
    kb: 1000 / (1024 * 1024),
    mib: 1,
    mb: 1e6 / (1024 * 1024),
    gib: 1024,
    gb: 1e9 / (1024 * 1024),
    tib: 1024 * 1024,
    tb: 1e12 / (1024 * 1024),
};

/** A docker stats MemUsage ("512.3MiB / 31.2GiB") or a plain size ("1.5GiB"), in MiB; null if unreadable. */
export function parseMemory(text) {
    const match = /^\s*([0-9.]+)\s*([a-z]+)/i.exec(String(text).split("/")[0]);
    if (!match) return null;
    const factor = MEMORY_UNITS[match[2].toLowerCase()];
    return factor === undefined ? null : Number(match[1]) * factor;
}

/** A docker stats CPUPerc ("12.34%"), as a number of percent (100 = one core); null if unreadable. */
export function parsePercent(text) {
    const value = Number.parseFloat(String(text).replace("%", ""));
    return Number.isFinite(value) ? value : null;
}

/** "catapult-verify-perf-api-1" -> "api". */
export function shortName(container) {
    return container.replace(/^catapult-verify-perf[-_]/, "").replace(/[-_]\d+$/, "");
}

const round = (value, digits = 1) => (value === null || value === undefined ? null
    : Math.round(value * 10 ** digits) / 10 ** digits);
const average = (values) => (values.length ? values.reduce((a, b) => a + b, 0) / values.length : null);
const max = (values) => (values.length ? Math.max(...values) : null);
/** Nearest-rank percentile. */
const percentile = (values, p) => {
    if (!values.length) return null;
    const sorted = [...values].sort((a, b) => a - b);
    return sorted[Math.max(0, Math.ceil((p / 100) * sorted.length) - 1)];
};

/**
 * docker-stats.csv: per container, the average and peak CPU (percent of one core) and memory (MiB), and
 * the whole stack's peak, the peak of the per-sample sums (the sum of each container's peak when the lines
 * carry no time). Lines are "time,container,cpu,memory" (time in seconds) or "container,cpu,memory"; a
 * header is skipped. cpuSustained is the p95 of the stack's per-sample CPU after the warm-up (of every
 * sample when nothing follows it, or without times).
 */
export function containerStats(csv) {
    const byContainer = new Map();
    const byTime = new Map();
    let timed = true;
    for (const line of csv.split("\n")) {
        const fields = line.trim().split(",");
        if (fields.length < 3) continue;
        const [time, container, cpuText, memoryText] = fields.length >= 4 ? fields : [null, ...fields];
        const cpu = parsePercent(cpuText);
        const memory = parseMemory(memoryText);
        if (cpu === null || memory === null) continue; // the header, or a container that stopped
        const name = shortName(container);
        if (!byContainer.has(name)) byContainer.set(name, { cpu: [], memory: [] });
        byContainer.get(name).cpu.push(cpu);
        byContainer.get(name).memory.push(memory);
        if (time === null) {
            timed = false;
        } else {
            const total = byTime.get(time) ?? { cpu: 0, memory: 0 };
            total.cpu += cpu;
            total.memory += memory;
            byTime.set(time, total);
        }
    }
    const containers = [...byContainer.entries()]
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, { cpu, memory }]) => ({
            name,
            samples: cpu.length,
            cpuAvg: round(average(cpu)),
            cpuPeak: round(max(cpu)),
            memAvgMiB: round(average(memory)),
            memPeakMiB: round(max(memory)),
        }));
    if (!containers.length) return { containers, total: null };
    const totals = timed ? [...byTime.values()] : null;
    let sustained = null;
    if (totals) {
        const times = [...byTime.keys()].map(Number);
        const warm = Math.min(...times) + WARMUP_SECONDS;
        const after = [...byTime.entries()].filter(([time]) => Number(time) >= warm).map(([, t]) => t.cpu);
        sustained = percentile(after.length ? after : totals.map((t) => t.cpu), 95);
    }
    const cpuPeak = totals ? max(totals.map((t) => t.cpu)) : containers.reduce((s, c) => s + c.cpuPeak, 0);
    const total = {
        cpuAvg: round(containers.reduce((sum, c) => sum + c.cpuAvg, 0)),
        cpuPeak: round(cpuPeak),
        cpuSustained: round(sustained ?? cpuPeak),
        memAvgMiB: round(containers.reduce((sum, c) => sum + c.memAvgMiB, 0)),
        memPeakMiB: round(totals ? max(totals.map((t) => t.memory))
            : containers.reduce((s, c) => s + c.memPeakMiB, 0)),
    };
    return { containers, total };
}

const MIB = 1024 * 1024;

/**
 * jvm-memory.csv: per app, the peaks of its used heap, used non-heap and their sum (MiB), and its heap's
 * maximum; and the peak of the apps' per-sample used sum. A JVM's container memory (RSS) is the heap it has
 * grown to and keeps, whatever it uses: what it uses is what a machine needs.
 */
export function jvmStats(csv) {
    const byApp = new Map();
    const byTime = new Map();
    for (const line of csv.split("\n")) {
        const [time, app, ...numbers] = line.trim().split(",");
        const [heap, nonHeap, heapMax] = numbers.map(Number);
        if (!app || !Number.isFinite(heap) || !Number.isFinite(nonHeap)) continue; // the header
        if (!byApp.has(app)) byApp.set(app, []);
        byApp.get(app).push({ heap: heap / MIB, nonHeap: nonHeap / MIB, heapMax: (heapMax || 0) / MIB });
        byTime.set(time, (byTime.get(time) ?? 0) + (heap + nonHeap) / MIB);
    }
    const apps = [...byApp.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([name, samples]) => ({
        name,
        samples: samples.length,
        heapPeakMiB: round(max(samples.map((s) => s.heap))),
        nonHeapPeakMiB: round(max(samples.map((s) => s.nonHeap))),
        usedPeakMiB: round(max(samples.map((s) => s.heap + s.nonHeap))),
        heapMaxMiB: round(max(samples.map((s) => s.heapMax))),
    }));
    return { apps, usedPeakMiB: apps.length ? round(max([...byTime.values()])) : null };
}

/**
 * The machine that runs the stack with the headroom above, on its sustained CPU and on its memory: the JVMs'
 * peak used memory (heap and non-heap) plus the other containers' peak RSS when the JVMs were sampled, the
 * stack's peak RSS otherwise. vCPUs and GiB, whole.
 */
export function sizing(total, jvm = null, containers = []) {
    if (!total) return null;
    const jvmNames = new Set((jvm?.apps ?? []).map((app) => app.name));
    const memoryMiB = jvm?.usedPeakMiB == null ? total.memPeakMiB : jvm.usedPeakMiB
        + containers.filter((c) => !jvmNames.has(c.name)).reduce((sum, c) => sum + c.memPeakMiB, 0);
    return {
        vcpus: Math.max(1, Math.ceil(total.cpuSustained / 100 / CPU_TARGET)),
        ramGiB: Math.max(1, Math.ceil(memoryMiB / 1024 / MEMORY_TARGET)),
        memoryMiB: round(memoryMiB),
        memoryBasis: jvm?.usedPeakMiB == null ? "rss" : "jvm",
    };
}

/** Every threshold of the k6 summary: {metric, expression, ok}. */
export function thresholds(summary) {
    const result = [];
    for (const [metric, values] of Object.entries(summary?.metrics ?? {})) {
        for (const [expression, failed] of Object.entries(values.thresholds ?? {})) {
            // The submetrics given an always-true threshold only so that k6 exports them
            if (/\{group:/.test(metric)) continue;
            result.push({ metric, expression, ok: failed === false });
        }
    }
    return result.sort((a, b) => a.metric.localeCompare(b.metric));
}

/** The k6 summary's request groups ({group:...} submetrics): requests, p50, p95 (ms), error rate. */
export function requestGroups(summary) {
    const metrics = summary?.metrics ?? {};
    const groups = [];
    for (const [metric, values] of Object.entries(metrics)) {
        const match = /^http_req_duration\{group:([^}]+)\}$/.exec(metric);
        if (!match) continue;
        const failed = metrics[`http_req_failed{group:${match[1]}}`];
        const total = failed ? failed.passes + failed.fails : null;
        groups.push({
            name: match[1],
            requests: values.count ?? total,
            p50: round(values.med),
            p95: round(values["p(95)"]),
            // k6 counts a failed request as a "pass" of http_req_failed (the rate's value is true)
            errors: failed ? failed.passes : null,
            errorRate: failed && total ? round((failed.passes / total) * 100, 2) : 0,
        });
    }
    return groups.sort((a, b) => a.name.localeCompare(b.name));
}

/** The pages' timings, without their samples. */
export function pageTimings(pages) {
    return (pages ?? []).map(({ name, metric, p50, p95, budget, note }) => ({
        name, metric, p50, p95, budget, ok: budget === undefined || p95 <= budget, ...(note ? { note } : {}),
    }));
}

/** Everything a report says, from the files of `runDir`. */
export function buildReport(runDir) {
    const read = (file) => (existsSync(join(runDir, file)) ? readFileSync(join(runDir, file), "utf8") : null);
    const json = (file) => {
        const text = read(file);
        return text === null ? null : JSON.parse(text);
    };
    const summary = json("k6-summary.json");
    const stats = read("docker-stats.csv");
    const jvmCsv = read("jvm-memory.csv");
    const jvm = jvmCsv === null ? null : jvmStats(jvmCsv);
    const outbound = read("outbound.txt");
    const { containers, total } = stats === null ? { containers: [], total: null } : containerStats(stats);
    const outboundLines = outbound === null ? null : outbound.split("\n").filter((line) => line.trim());
    return {
        run: basename(resolve(runDir)),
        load: json("load.json"),
        thresholds: thresholds(summary),
        requests: summary ? {
            total: summary.metrics?.http_reqs?.count ?? null,
            perSecond: round(summary.metrics?.http_reqs?.rate ?? null),
            unexpectedStreamCloses: summary.metrics?.ws_unexpected_close?.count ?? null,
            groups: requestGroups(summary),
        } : null,
        pages: pageTimings(json("pages.json")),
        containers,
        total,
        jvm: jvm?.apps.length ? jvm : null,
        sizing: sizing(total, jvm?.apps.length ? jvm : null, containers),
        outbound: outboundLines === null ? null : { lines: outboundLines.length, first: outboundLines.slice(0, 20) },
    };
}

/**
 * The run to compare `runDir` with, among its siblings before it (by name) with a report.json: the latest
 * under the same load (`load`, as in load.json), else the latest with a load run, else the latest; null if
 * none.
 */
export function findPrevious(runDir, load = null) {
    const parent = dirname(resolve(runDir));
    const self = basename(resolve(runDir));
    const earlier = readdirSync(parent, { withFileTypes: true })
        .filter((entry) => entry.isDirectory() && entry.name < self
            && existsSync(join(parent, entry.name, "report.json")))
        .map((entry) => entry.name)
        .sort()
        .reverse()
        .map((name) => ({ dir: join(parent, name), report: readPreviousReport(join(parent, name)) }));
    const shape = JSON.stringify(loadShape(load));
    const found = earlier.find((run) => JSON.stringify(loadShape(run.report.load)) === shape)
        ?? earlier.find((run) => run.report.requests)
        ?? earlier[0];
    return found ? found.dir : null;
}

/** `dir`'s report.json; an error that says so when there is none. */
export function readPreviousReport(dir) {
    const file = join(dir, "report.json");
    if (!existsSync(file)) throw new Error(`${dir} has no report.json to compare with`);
    return JSON.parse(readFileSync(file, "utf8"));
}

function delta(section, name, field, before, after) {
    if (typeof before !== "number" || typeof after !== "number") return null;
    return {
        section, name, field, before, after,
        delta: round(after - before, field === "errorRate" ? 2 : 1),
        pct: before === 0 ? null : round(((after - before) / before) * 100),
    };
}

/** The differences between two reports, figure by figure (absolute and in %), on what both measured. */
export function compare(current, previous) {
    if (!previous) return null;
    const deltas = [];
    const pairs = (section, list, key, fields) => {
        const before = new Map((list(previous) ?? []).map((item) => [key(item), item]));
        for (const item of list(current) ?? []) {
            const old = before.get(key(item));
            if (!old) continue;
            for (const field of fields) {
                const d = delta(section, key(item), field, old[field], item[field]);
                if (d) deltas.push(d);
            }
        }
    };
    pairs("requests", (r) => r.requests?.groups, (g) => g.name, ["p50", "p95", "errorRate"]);
    pairs("pages", (r) => r.pages, (p) => `${p.name} (${p.metric})`, ["p50", "p95"]);
    pairs("containers", (r) => r.containers, (c) => c.name, ["cpuAvg", "cpuPeak", "memAvgMiB", "memPeakMiB"]);
    if (current.total && previous.total) {
        for (const field of ["cpuAvg", "cpuPeak", "cpuSustained", "memAvgMiB", "memPeakMiB"]) {
            const d = delta("containers", "total", field, previous.total[field], current.total[field]);
            if (d) deltas.push(d);
        }
    }
    pairs("jvm", (r) => r.jvm?.apps, (a) => a.name, ["heapPeakMiB", "nonHeapPeakMiB", "usedPeakMiB"]);
    const sameLoad = JSON.stringify(loadShape(current.load)) === JSON.stringify(loadShape(previous.load));
    return { run: previous.run, load: previous.load ?? null, sameLoad, deltas };
}

const loadShape = (load) => (load ? { streamers: load.streamers, viewers: load.viewers, duration: load.duration }
    : null);

const FIELD_LABELS = {
    p50: "p50 (ms)", p95: "p95 (ms)", errorRate: "errors (%)", cpuAvg: "avg CPU (%)", cpuPeak: "peak CPU (%)",
    cpuSustained: "sustained CPU (%)",
    memAvgMiB: "avg RSS (MiB)", memPeakMiB: "peak RSS (MiB)", heapPeakMiB: "peak used heap (MiB)",
    nonHeapPeakMiB: "peak used non-heap (MiB)", usedPeakMiB: "peak used heap + non-heap (MiB)",
};

const show = (value, suffix = "") => (value === null || value === undefined ? "–" : `${value}${suffix}`);
const signed = (value, suffix = "") => (value === null ? "–" : `${value > 0 ? "+" : ""}${value}${suffix}`);
const table = (header, rows) => [
    `| ${header.join(" | ")} |`,
    `|${header.map(() => "---").join("|")}|`,
    ...rows.map((row) => `| ${row.join(" | ")} |`),
].join("\n");

const describeLoad = (load) => `${load.streamers} streamers, ${load.viewers} viewers, ${load.duration}`;

/** report.md's text. */
export function renderMarkdown(report) {
    const out = [`# Timing run ${report.run}`, ""];
    if (report.load) {
        out.push(`Load: ${describeLoad(report.load)} (k6 \`${report.load.k6Image}\`).`, "");
    }

    out.push("## Thresholds", "");
    if (report.thresholds.length) {
        out.push(table(["Metric", "Threshold", "Result"],
            report.thresholds.map((t) => [`\`${t.metric}\``, `\`${t.expression}\``, t.ok ? "pass" : "**FAIL**"])),
            "",
            "`ws_unexpected_close` counts the live streams (SSE, catapult has no WebSocket) closed or refused "
            + "before the end of the run. The latency threshold is on `kind:request`, every request but those "
            + "streams.");
    } else {
        out.push("No load run.");
    }
    out.push("");

    if (report.requests) {
        out.push("## Requests", "",
            `${report.requests.total} requests (${report.requests.perSecond}/s); `
            + `unexpected live stream closes: ${report.requests.unexpectedStreamCloses}.`, "",
            table(["Group", "Requests", "p50 (ms)", "p95 (ms)", "Errors"],
                report.requests.groups.map((g) => [g.name, show(g.requests), show(g.p50), show(g.p95),
                    `${show(g.errors)} (${show(g.errorRate, " %")})`])),
            "",
            "`stream` is the live streams (SSE): their duration is how long each stayed open, not a latency.",
            "");
    }

    if (report.pages.length) {
        out.push("## Pages", "",
            table(["Page", "Metric", "p50 (ms)", "p95 (ms)", "Budget (ms)", "Result"],
                report.pages.map((p) => [p.name, p.metric, show(p.p50), show(p.p95), show(p.budget),
                    (p.ok ? "pass" : "**FAIL**") + (p.note ? ` (${p.note})` : "")])),
            "");
    }

    if (report.containers.length) {
        out.push("## CPU and memory", "",
            "CPU in % of one core; RSS is the container's memory (docker stats, every 5 s during the load run), "
            + "for a JVM the heap it has grown to rather than what it uses.", "",
            table(["Container", "Samples", "Avg CPU", "Peak CPU", "Avg RSS", "Peak RSS"], [
                ...report.containers.map((c) => [c.name, c.samples, show(c.cpuAvg, " %"), show(c.cpuPeak, " %"),
                    show(c.memAvgMiB, " MiB"), show(c.memPeakMiB, " MiB")]),
                ["**stack**", "", show(report.total.cpuAvg, " %"), show(report.total.cpuPeak, " %"),
                    show(report.total.memAvgMiB, " MiB"), show(report.total.memPeakMiB, " MiB")],
            ]),
            "");
        if (report.jvm) {
            out.push("## JVM memory", "",
                "Used memory from each app's actuator (`jvm_memory_used_bytes`, `jvm_memory_max_bytes`), "
                + "every 5 s.", "",
                table(["App", "Samples", "Peak used heap", "Peak used non-heap", "Peak used, both", "Max heap"], [
                    ...report.jvm.apps.map((a) => [a.name, a.samples, show(a.heapPeakMiB, " MiB"),
                        show(a.nonHeapPeakMiB, " MiB"), show(a.usedPeakMiB, " MiB"), show(a.heapMaxMiB, " MiB")]),
                    ["**both**", "", "", "", show(report.jvm.usedPeakMiB, " MiB"), ""],
                ]),
                "");
        }
        const memory = report.sizing.memoryBasis === "jvm"
            ? `${report.sizing.memoryMiB} MiB of memory at most (the JVMs' peak used heap and non-heap, `
                + "plus the other containers' peak RSS)"
            : `${report.sizing.memoryMiB} MiB of memory at most (peak RSS: the JVMs were not sampled, and their `
                + "RSS is the heap they grew to, not what they need)";
        out.push("## Machine for this load", "",
            `${report.load ? `For ${describeLoad(report.load)}: the` : "The"} whole stack used `
            + `${report.total.cpuSustained} % of a core (p95 of the samples after the first `
            + `${WARMUP_SECONDS} s; ${report.total.cpuPeak} % at its peak, warm-up included) and ${memory}. `
            + `Keeping CPU under ${CPU_TARGET * 100} % and memory under ${MEMORY_TARGET * 100} % takes `
            + `**${report.sizing.vcpus} vCPU and ${report.sizing.ramGiB} GiB of RAM** (the system's own needs `
            + "aside), each JVM's heap capped (`-Xmx`) under what it leaves. "
            + "k6 ran on the same host, so it took CPU from the stack.",
            "");
    }

    out.push("## Outbound traffic", "");
    if (report.outbound === null) {
        out.push("Not scanned.");
    } else if (report.outbound.lines === 0) {
        out.push("None: no connection error towards a host outside the stack.");
    } else {
        out.push(`${report.outbound.lines} line(s) with a connection error towards a host outside the stack `
            + "(outbound.txt), first ones:", "", "```", ...report.outbound.first, "```");
    }
    out.push("");

    out.push("## Compared with the previous run", "");
    if (!report.previous) {
        out.push("No earlier report: nothing to compare with.");
    } else {
        out.push(`Run ${report.previous.run}${report.previous.load ? ` (${describeLoad(report.previous.load)})` : ""}.`);
        if (!report.previous.sameLoad) out.push("", "**Not the same load: the figures do not compare directly.**");
        out.push("");
        if (report.previous.deltas.length) {
            out.push(table(["Section", "Name", "Figure", "Before", "After", "Δ", "Δ %"],
                report.previous.deltas.map((d) => [d.section, d.name, FIELD_LABELS[d.field] ?? d.field,
                    d.before, d.after, signed(d.delta), signed(d.pct, " %")])));
        } else {
            out.push("Nothing measured in both runs.");
        }
    }
    out.push("");
    return out.join("\n");
}

/**
 * Writes report.json and report.md in `runDir`, compared with `previousDir`'s report (found by findPrevious
 * when undefined, none when null); returns the report.
 */
export function writeReport(runDir, previousDir) {
    const report = buildReport(runDir);
    const dir = previousDir === undefined ? findPrevious(runDir, report.load) : previousDir;
    const previous = dir ? readPreviousReport(dir) : null;
    report.previous = compare(report, previous);
    writeFileSync(join(runDir, "report.json"), `${JSON.stringify(report, null, 2)}\n`);
    writeFileSync(join(runDir, "report.md"), renderMarkdown(report));
    return report;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const [runDir, previousDir] = process.argv.slice(2);
    if (!runDir) {
        console.error("Usage: node perf/report.mjs <run dir> [<previous run dir>]");
        process.exit(2);
    }
    let report;
    try {
        report = writeReport(runDir, previousDir);
    } catch (e) {
        console.error(`report.mjs: ${e.message}`);
        process.exit(1);
    }
    const failed = report.thresholds.filter((t) => !t.ok);
    console.log(`Wrote ${join(runDir, "report.md")}${report.previous ? ` (compared with ${report.previous.run})` : ""}`);
    if (failed.length) console.log(`Thresholds failed: ${failed.map((t) => `${t.metric} ${t.expression}`).join(", ")}`);
}
