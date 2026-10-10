#!/usr/bin/env node
/*
 * Generates src/types/obs.d.ts, the IDE typings of window.CatapultObs (static/js/obs.js): every
 * obs-websocket request and event, with their fields, from obs-websocket's protocol.json. The
 * output is only read by IDEs, never served.
 *
 *   npm run generate:obs-types                 # protocol.json of obs-websocket's master branch
 *   npm run generate:obs-types -- --ref 5.6.3  # of a tag or branch
 *   npm run generate:obs-types -- --from protocol.json
 */
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const OUTPUT = resolve(dirname(fileURLToPath(import.meta.url)), "../src/types/obs.d.ts");

const { values: args } = parseArgs({ options: { ref: { type: "string", default: "master" }, from: { type: "string" } } });
const source = args.from
    ?? `https://raw.githubusercontent.com/obsproject/obs-websocket/${args.ref}/docs/generated/protocol.json`;

async function loadProtocol() {
    if (args.from) return JSON.parse(await readFile(args.from, "utf8"));
    const response = await fetch(source);
    if (!response.ok) throw new Error(`${source}: ${response.status} ${response.statusText}`);
    return response.json();
}

const TYPES = {
    String: "string",
    Number: "number",
    Boolean: "boolean",
    Object: "Record<string, any>",
    Any: "any",
    "Array<String>": "string[]",
    "Array<Number>": "number[]",
    "Array<Boolean>": "boolean[]",
    "Array<Object>": "Array<Record<string, any>>",
};

function tsType(valueType) {
    const type = TYPES[valueType];
    if (!type) throw new Error(`Unknown protocol valueType ${valueType}`);
    return type;
}

/** A JSDoc block at `indent`; null lines are dropped. */
function jsdoc(indent, ...lines) {
    const text = lines.filter((line) => line != null).join("\n").replaceAll("*/", "*\\/").trim();
    if (!text) return "";
    const body = text.split("\n").map((line) => `${indent} *${line ? ` ${line}` : ""}`).join("\n");
    return `${indent}/**\n${body}\n${indent} */\n`;
}

/**
 * An object type literal of protocol fields. Dotted names ("keyModifiers.shift") become nested
 * object types under their parent field.
 */
function objectType(fields, indent, { optional = () => false } = {}) {
    if (fields.length === 0) return "{}";
    const roots = fields.filter((field) => !field.valueName.includes("."));
    const children = (name) => fields
        .filter((field) => field.valueName.startsWith(`${name}.`))
        .map((field) => ({ ...field, valueName: field.valueName.slice(name.length + 1) }));
    for (const field of fields) {
        const parent = field.valueName.split(".")[0];
        if (field.valueName.includes(".") && !roots.some((root) => root.valueName === parent)) {
            roots.push({ valueName: parent, valueType: "Object", valueOptional: true });
        }
    }
    const members = roots.map((field) => {
        const nested = children(field.valueName);
        const type = nested.length ? objectType(nested, `${indent}    `, { optional }) : tsType(field.valueType);
        const restrictions = field.valueRestrictions && field.valueRestrictions !== "None"
            ? `Restrictions: ${field.valueRestrictions}` : null;
        const behavior = field.valueOptionalBehavior && !["Unknown", "None"].includes(field.valueOptionalBehavior)
            ? `When left out: ${field.valueOptionalBehavior}` : null;
        return jsdoc(`${indent}    `, field.valueDescription, restrictions, behavior)
            + `${indent}    ${field.valueName}${optional(field) ? "?" : ""}: ${type};`;
    });
    return `{\n${members.join("\n")}\n${indent}}`;
}

const isOptional = (field) => !!field.valueOptional;

function since(entry) {
    return `@since obs-websocket ${entry.initialVersion}${entry.deprecated ? "\n@deprecated" : ""}`;
}

function generate(protocol) {
    const requests = protocol.requests;
    const events = protocol.events;
    const subscriptions = protocol.enums.find((e) => e.enumType === "EventSubscription").enumIdentifiers;

    const requestMap = requests.map((r) => jsdoc("    ", r.description, since(r))
        + `    ${r.requestType}: {\n`
        + `        request: ${objectType(r.requestFields, "        ", { optional: isOptional })};\n`
        + `        response: ${objectType(r.responseFields, "        ")};\n`
        + "    };").join("\n");

    const shortcuts = requests.map((r) => {
        const name = r.requestType[0].toLowerCase() + r.requestType.slice(1);
        const map = `ObsRequestMap["${r.requestType}"]`;
        const param = r.requestFields.length === 0 ? ""
            : `data${r.requestFields.every(isOptional) ? "?" : ""}: ${map}["request"]`;
        return jsdoc("    ", r.description, since(r)) + `    ${name}(${param}): Promise<${map}["response"]>;`;
    }).join("\n");

    const eventMap = events.map((e) => jsdoc("    ", e.description,
        `Needs the ${e.eventSubscription} event subscription.`, since(e))
        + `    ${e.eventType}: ${objectType(e.dataFields, "    ")};`).join("\n");

    const subscriptionMembers = subscriptions
        .map((s) => `        readonly ${s.enumIdentifier}: number;`).join("\n");

    return `// Generated by scripts/generate-obs-types.mjs from ${source}
// Don't edit by hand: rerun \`npm run generate:obs-types\` instead.
// IDE typings of static/js/obs.js and static/js/obs-session.js; never served.

/** Every obs-websocket request: its data and its response. */
interface ObsRequestMap {
${requestMap}
}

/** Every obs-websocket event's data. */
interface ObsEventMap {
${eventMap}
}

/** One shortcut per request, see CatapultObs.requests. */
interface ObsRequests {
${shortcuts}
}

type ObsRequestType = keyof ObsRequestMap;
type ObsEventType = keyof ObsEventMap;

interface ObsEvent<T extends ObsEventType = ObsEventType> {
    eventType: T;
    eventIntent: number;
    eventData: ObsEventMap[T];
}

interface ObsInfo {
    obsWebSocketVersion?: string;
    rpcVersion?: number;
    authenticated: boolean;
    negotiatedRpcVersion?: number;
    /** The request types the connected OBS knows, when it said so. */
    availableRequests?: string[];
}

interface ObsConnectOptions {
    host: string;
    port: number;
    password?: string | null;
    /** wss:// instead of ws:// (OBS behind a TLS proxy). */
    secure?: boolean;
    /** CatapultObs.EventSubscription bitmask; OBS's default (All) when left out. */
    eventSubscriptions?: number;
}

declare class ObsError extends Error {
    name: "ObsError";
    /** Close code for a closed connection, request status code for a failed request. */
    code: number | null;
    requestType: string | null;
    comment: string | null;
    /** The closest known request type, when this one was unknown. */
    suggestion: string | null;
}

type ObsBatchResult =
    | { requestType: string; ok: true; data: Record<string, any> }
    | { requestType: string; ok: false; error: ObsError };

interface CatapultObsApi {
    readonly OpCode: {
        readonly Hello: 0; readonly Identify: 1; readonly Identified: 2; readonly Reidentify: 3; readonly Event: 5;
        readonly Request: 6; readonly RequestResponse: 7; readonly RequestBatch: 8; readonly RequestBatchResponse: 9;
    };
    readonly EventSubscription: {
${subscriptionMembers}
    };
    readonly RequestBatchExecutionType: {
        readonly None: -1; readonly SerialRealtime: 0; readonly SerialFrame: 1; readonly Parallel: 2;
    };
    readonly CloseCode: {
        readonly UnknownReason: 4000; readonly MessageDecodeError: 4002; readonly MissingDataField: 4003;
        readonly InvalidDataFieldType: 4004; readonly InvalidDataFieldValue: 4005; readonly UnknownOpCode: 4006;
        readonly NotIdentified: 4007; readonly AlreadyIdentified: 4008; readonly AuthenticationFailed: 4009;
        readonly UnsupportedRpcVersion: 4010; readonly SessionInvalidated: 4011; readonly UnsupportedFeature: 4012;
    };
    readonly ObsError: typeof ObsError;

    /** Logs every frame sent (↑) and received (↓) to the console; remembered across reloads. */
    debug: boolean;

    /** One shortcut per request: requests.getSceneList() is call("GetSceneList"). */
    readonly requests: ObsRequests;

    /** Resolves once OBS accepted the Identify; never retries (see onDisconnect). */
    connect(options: ObsConnectOptions): Promise<ObsInfo>;
    /** Overridable hook: a live connection dropped without close(). */
    onDisconnect(error: ObsError): void;
    isConnected(): boolean;
    info(): ObsInfo | null;
    call<T extends ObsRequestType>(requestType: T, requestData?: ObsRequestMap[T]["request"]): Promise<ObsRequestMap[T]["response"]>;
    callBatch(
        requests: Array<{ requestType: ObsRequestType; requestData?: Record<string, any> }>,
        options?: { haltOnFailure?: boolean; executionType?: number },
    ): Promise<ObsBatchResult[]>;
    reidentify(eventSubscriptions?: number): Promise<ObsInfo>;
    /** Subscribes to an event type, or "*" for all of them; returns the unsubscribe function. */
    on<T extends ObsEventType>(eventType: T, handler: (eventData: ObsEventMap[T], event: ObsEvent<T>) => void): () => void;
    on(eventType: "*", handler: (eventData: Record<string, any>, event: ObsEvent) => void): () => void;
    off(eventType: ObsEventType | "*", handler: (...args: any[]) => void): void;
    once<T extends ObsEventType>(eventType: T): Promise<ObsEventMap[T]>;
    /** Closes on purpose: pending requests fail, but onDisconnect doesn't fire. */
    close(): void;
}

interface CatapultObsSessionApi {
    /** Re-reads /me/obs, then connects, reconnects or disconnects to match it. */
    refresh(): Promise<void>;
}

/** Fired on document by obs.js. */
interface DocumentEventMap {
    /** The connection is ready for requests (connect() resolved). */
    "catapult:obs:connected": CustomEvent<{ info: ObsInfo }>;
    /** A ready connection ended, close() included; failed attempts don't fire it. */
    "catapult:obs:disconnected": CustomEvent<{ error: ObsError }>;
}

declare var CatapultObs: CatapultObsApi;
declare var CatapultObsSession: CatapultObsSessionApi;
`;
}

const protocol = await loadProtocol();
await mkdir(dirname(OUTPUT), { recursive: true });
await writeFile(OUTPUT, generate(protocol));
console.log(`${OUTPUT}: ${protocol.requests.length} requests, ${protocol.events.length} events`);
