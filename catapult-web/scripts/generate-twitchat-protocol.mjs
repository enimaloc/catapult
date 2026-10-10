#!/usr/bin/env node
/*
 * Generates Twitchat's public API protocols for static/js/twitchat.js, which Twitchat can't be
 * asked for at runtime (unlike OBS and its GetVersion):
 *   - static/js/twitchat-protocol.js: per protocol, the action and event names, and which event
 *     answers which action, served with the page;
 *   - src/types/twitchat.d.ts: the IDE typings of window.CatapultTwitchat, never served.
 *
 * Two protocols, as Twitchat's beta renamed its whole API (CHAT_FEED_PAUSE ->
 * SET_CHAT_FEED_PAUSE_STATE, TRIGGER_LIST -> ON_TRIGGER_LIST…) on the same wire format:
 *   - "stable", from the main branch: names in TwitchatActionTypeList/TwitchatEventTypeList,
 *     descriptions from PUBLIC_API.md, answers from STABLE_REPLIES below;
 *   - "beta", from the beta branch: names, descriptions and answers (@answer) all from the
 *     TwitchatEventMap's JSDoc, the ON_* keys being the events and the others the actions.
 * Both come from src_front/events/TwitchatEvent.ts, whichever format the ref has.
 *
 *   npm run generate:twitchat-protocol                                  # main and beta
 *   npm run generate:twitchat-protocol -- --stable v15.0 --beta beta    # other tags or branches
 */
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const JS_OUTPUT = resolve(ROOT, "src/main/resources/static/js/twitchat-protocol.js");
const TYPES_OUTPUT = resolve(ROOT, "src/types/twitchat.d.ts");

const { values: args } = parseArgs({
    options: { stable: { type: "string", default: "main" }, beta: { type: "string", default: "beta" } },
});
const source = (ref, path) => `https://raw.githubusercontent.com/Durss/Twitchat/${ref}/${path}`;
const EVENTS_PATH = "src_front/events/TwitchatEvent.ts";
const DOCS_PATH = "PUBLIC_API.md";

/**
 * The event the stable Twitchat answers each "get" action with, read from the handlers in its
 * source (PublicAPI listeners in src_front/store and src_front/components/overlays): not
 * derivable from the names (GET_COLS_COUNT -> SET_COLS_COUNT, COUNTER_GET -> COUNTER_UPDATE…).
 * The *_OVERLAY_PRESENCE ones are answered by the overlay itself, so only while it's open.
 */
const STABLE_REPLIES = {
    GET_CURRENT_TRACK: ["CURRENT_TRACK"],
    GET_COLS_COUNT: ["SET_COLS_COUNT"],
    COUNTER_GET: ["COUNTER_UPDATE"],
    COUNTER_GET_ALL: ["COUNTER_LIST"],
    TRIGGERS_GET_ALL: ["TRIGGER_LIST"],
    // One event per timer, a TIMER_START or a COUNTDOWN_START depending on its kind.
    GET_CURRENT_TIMERS: ["TIMER_START", "COUNTDOWN_START"],
    GET_DONATION_GOALS_OVERLAY_PARAMS: ["DONATION_GOALS_OVERLAY_PARAMS"],
    GET_PREDICTIONS_OVERLAY_PARAMETERS: ["PREDICTIONS_OVERLAY_PARAMETERS"],
    GET_POLLS_OVERLAY_PARAMETERS: ["POLLS_OVERLAY_PARAMETERS"],
    GET_CHAT_POLL_OVERLAY_PARAMETERS: ["CHAT_POLL_OVERLAY_PARAMETERS"],
    GET_ANIMATED_TEXT_CONFIGS: ["ANIMATED_TEXT_CONFIGS"],
    GET_BINGO_GRID_PARAMETERS: ["BINGO_GRID_PARAMETERS"],
    GET_AD_BREAK_OVERLAY_PARAMETERS: ["AD_BREAK_OVERLAY_PARAMETERS"],
    GET_BITS_WALL_OVERLAY_PARAMETERS: ["BITSWALL_OVERLAY_PARAMETERS"],
    GET_SUMMARY_DATA: ["SUMMARY_DATA"],
    GET_DISTORT_OVERLAY_PARAMETERS: ["DISTORT_OVERLAY_PARAMETERS"],
    QNA_SESSION_GET_ALL: ["QNA_SESSION_LIST"],
    GET_LABEL_OVERLAY_PLACEHOLDERS: ["LABEL_OVERLAY_PLACEHOLDERS"],
    GET_LABEL_OVERLAY_PARAMS: ["LABEL_OVERLAY_PARAMS"],
    GET_CUSTOM_TRAIN_STATE: ["CUSTOM_TRAIN_STATE"],
    GET_WHEEL_OVERLAY_PRESENCE: ["WHEEL_OVERLAY_PRESENCE"],
    GET_BITSWALL_OVERLAY_PRESENCE: ["BITSWALL_OVERLAY_PRESENCE"],
    GET_CREDITS_OVERLAY_PRESENCE: ["CREDITS_OVERLAY_PRESENCE"],
    GET_TIMER_OVERLAY_PRESENCE: ["TIMER_OVERLAY_PRESENCE"],
    GET_CHAT_HIGHLIGHT_OVERLAY_PRESENCE: ["CHAT_HIGHLIGHT_OVERLAY_PRESENCE"],
    GET_AD_BREAK_OVERLAY_PRESENCE: ["AD_BREAK_OVERLAY_PRESENCE"],
    GET_PREDICTIONS_OVERLAY_PRESENCE: ["PREDICTIONS_OVERLAY_PRESENCE"],
    GET_POLLS_OVERLAY_PRESENCE: ["POLLS_OVERLAY_PRESENCE"],
    GET_CHAT_POLL_OVERLAY_PRESENCE: ["CHAT_POLL_OVERLAY_PRESENCE"],
};

// Not Twitchat's: the obs-websocket event carrying its messages.
const NOT_TWITCHAT_EVENTS = new Set(["CustomEvent"]);

async function fetchText(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`${url}: ${response.status} ${response.statusText}`);
    return response.text();
}

/** The string literals of `export const <name> = [...] as const`, null without one. */
function constList(source, name) {
    const match = source.match(new RegExp(`export const ${name}\\s*=\\s*\\[([\\s\\S]*?)\\]\\s*as const`));
    return match ? [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]) : null;
}

/** PUBLIC_API.md's "## **NAME**" sections: their description and data example. */
function documentation(markdown) {
    const docs = new Map();
    const sections = markdown.split(/^## \*\*([A-Z_]+)\*\*\s*$/m);
    for (let i = 1; i < sections.length; i += 2) {
        const body = sections[i + 1].split(/^#{1,2} /m)[0];
        const code = body.match(/```(?:typescript|ts)?\n([\s\S]*?)```/);
        const description = body.slice(0, code ? code.index : undefined)
            .replace(/<br\s*\/?>/g, "").replace(/\\$/gm, "").trim();
        docs.set(sections[i], { description, example: code ? code[1].replace(/\t/g, "    ").trimEnd() : null });
    }
    return docs;
}

/** The stable format: the name lists, STABLE_REPLIES and PUBLIC_API.md. */
async function stableProtocol(ref, eventsSource) {
    const actions = constList(eventsSource, "TwitchatActionTypeList");
    const events = constList(eventsSource, "TwitchatEventTypeList");
    if (!actions || !events) throw new Error(`${ref}: no TwitchatActionTypeList/TwitchatEventTypeList`);
    const twitchatEvents = events.filter((event) => !NOT_TWITCHAT_EVENTS.has(event));
    const replies = {};
    for (const [action, answers] of Object.entries(STABLE_REPLIES)) {
        const unknown = [action, ...answers].filter((name) => !actions.includes(name) && !twitchatEvents.includes(name));
        if (unknown.length) {
            console.warn(`${ref}: reply ${action} -> ${answers} skipped, unknown to this Twitchat: ${unknown.join(", ")}`);
            continue;
        }
        replies[action] = answers;
    }
    return { actions, events: twitchatEvents, replies, docs: documentation(await fetchText(source(ref, DOCS_PATH))) };
}

/**
 * The beta format: the keys of `export type TwitchatEventMap = { ... }` (one tab deep, the
 * deeper ones being their data's fields), each with the JSDoc right above it.
 */
function betaProtocol(ref, eventsSource) {
    const block = eventsSource.match(/export type TwitchatEventMap\s*=\s*\{\n([\s\S]*?)\n\};/);
    if (!block) throw new Error(`${ref}: no TwitchatEventMap`);
    const actions = [];
    const events = [];
    const replies = {};
    const docs = new Map();
    for (const [, comment, name] of block[1].matchAll(/(?:\/\*\*((?:(?!\*\/)[\s\S])*?)\*\/\s*)?^\t([A-Z][A-Z0-9_]+)\??:/gm)) {
        (name.startsWith("ON_") ? events : actions).push(name);
        const lines = (comment ?? "").split("\n").map((line) => line.replace(/^\s*\*\s?/, "").trimEnd());
        const answers = lines.flatMap((line) => line.match(/^@answer\s+(.+)/)?.[1].split(/[\s,|]+/) ?? []);
        if (answers.length) replies[name] = answers;
        docs.set(name, { description: lines.filter((line) => !line.startsWith("@")).join("\n").trim(), example: null });
    }
    for (const [action, answers] of Object.entries(replies)) {
        const unknown = answers.filter((name) => !actions.includes(name) && !events.includes(name));
        if (unknown.length) throw new Error(`${ref}: ${action} answered by unknown ${unknown.join(", ")}`);
    }
    return { actions, events, replies, docs };
}

async function protocol(ref) {
    const eventsSource = await fetchText(source(ref, EVENTS_PATH));
    return constList(eventsSource, "TwitchatActionTypeList") ? stableProtocol(ref, eventsSource) : betaProtocol(ref, eventsSource);
}

const shortcutName = (type) => type.toLowerCase().replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());

function jsdoc(indent, lines) {
    const text = lines.filter((line) => line != null && line !== "").join("\n\n").replaceAll("*/", "*\\/").trim();
    if (!text) return "";
    return `${indent}/**\n${text.split("\n").map((line) => `${indent} *${line ? ` ${line}` : ""}`).join("\n")}\n${indent} */\n`;
}

function docLines(doc, dataLabel) {
    if (!doc) return [];
    return [doc.description, doc.example ? `${dataLabel}:\n\`\`\`ts\n${doc.example}\n\`\`\`` : null];
}

const union = (names) => names.map((name) => `\n    | "${name}"`).join("");

function generateJs(protocols) {
    const list = (names) => `[\n${names.map((name) => `            "${name}",`).join("\n")}\n        ]`;
    const entries = Object.entries(protocols).map(([name, { ref, actions, events, replies }]) => {
        const replyEntries = Object.entries(replies)
            .map(([action, answers]) => `            ${action}: ${JSON.stringify(answers)},`).join("\n");
        return `    /** Twitchat's ${ref} branch. */
    ${name}: Object.freeze({
        /** Every action Twitchat accepts. */
        actions: Object.freeze(${list(actions)}),
        /** Every event Twitchat sends. */
        events: Object.freeze(${list(events)}),
        /** The event(s) answering each "get" action, the first one received being the answer. */
        replies: Object.freeze({
${replyEntries}
        }),
    }),`;
    }).join("\n");
    return `// Generated by scripts/generate-twitchat-protocol.mjs from ${Object.values(protocols)
        .map(({ ref }) => source(ref, EVENTS_PATH)).join(" and ")}
// Don't edit by hand: rerun \`npm run generate:twitchat-protocol\` instead.
window.CatapultTwitchatProtocol = Object.freeze({
${entries}
});
`;
}

/** Every name of the protocols, in order, each with the protocols knowing it. */
function merged(protocols, kind) {
    const names = new Map();
    for (const [protocolName, protocol] of Object.entries(protocols)) {
        for (const name of protocol[kind]) {
            if (!names.has(name)) names.set(name, []);
            names.get(name).push(protocolName);
        }
    }
    return names;
}

function generateTypes(protocols) {
    const protocolNames = Object.keys(protocols);
    const allActions = merged(protocols, "actions");
    const allEvents = merged(protocols, "events");
    const docOf = (name, known) => protocols[known.at(-1)].docs.get(name) ?? protocols[known[0]].docs.get(name);
    const only = (known) => (known.length < protocolNames.length ? `Only in the ${known.join(", ")} Twitchat.` : null);

    const shortcuts = [...allActions].map(([action, known]) => {
        const answers = protocols[known[0]].replies[action];
        const returns = answers
            ? `Resolves with the data of Twitchat's answer, ${answers.join(" or ")}; rejects without one in time.`
            : "Resolves once sent: Twitchat doesn't answer it.";
        return jsdoc("    ", [...docLines(docOf(action, known), "Data"), only(known), `Sends ${action}. ${returns}`])
            + `    ${shortcutName(action)}(data?: Record<string, any>, options?: TwitchatRequestOptions): `
            + `Promise<${answers ? "Record<string, any>" : "void"}>;`;
    }).join("\n");

    const eventDocs = [...allEvents].map(([event, known]) => jsdoc("    ", [...docLines(docOf(event, known), "Data"), only(known)])
        + `    ${event}: Record<string, any>;`).join("\n");

    return `// Generated by scripts/generate-twitchat-protocol.mjs from ${Object.values(protocols)
        .map(({ ref }) => source(ref, EVENTS_PATH)).join(" and ")}
// and ${source(protocols.stable.ref, DOCS_PATH)}
// Don't edit by hand: rerun \`npm run generate:twitchat-protocol\` instead.
// IDE typings of static/js/twitchat.js; never served.

/** A version of Twitchat's public API: ${protocolNames.join(" or ")}. */
type TwitchatProtocolName = ${protocolNames.map((name) => `"${name}"`).join(" | ")};

/** Every action a Twitchat accepts, whichever its protocol. */
type TwitchatActionType = ${union([...allActions.keys()])};

/** Every event a Twitchat sends, whichever its protocol. */
type TwitchatEventType = ${union([...allEvents.keys()])};

/** Every event a Twitchat sends, documented when Twitchat does. */
interface TwitchatEventMap {
${eventDocs}
}

/** One protocol of twitchat-protocol.js. */
interface TwitchatProtocol {
    readonly actions: readonly TwitchatActionType[];
    readonly events: readonly TwitchatEventType[];
    readonly replies: Readonly<Partial<Record<TwitchatActionType, readonly (TwitchatEventType | TwitchatActionType)[]>>>;
}

/** A Twitchat message as carried by OBS's CustomEvent. */
interface TwitchatEnvelope {
    origin: "twitchat";
    /** Unique per message: lets every client drop the echoes of its own. */
    id?: string;
    type: TwitchatEventType | TwitchatActionType;
    data?: Record<string, any>;
}

interface TwitchatRequestOptions {
    /** How long to wait for the answer of a "get" action, in ms (3000 by default). */
    timeout?: number;
}

/** One shortcut per action, see CatapultTwitchat.actions. */
interface TwitchatActions {
${shortcuts}
}

declare class TwitchatError extends Error {
    name: "TwitchatError";
    /** "UNKNOWN_ACTION", "NOT_CONNECTED" or "TIMEOUT". */
    code: "UNKNOWN_ACTION" | "NOT_CONNECTED" | "TIMEOUT";
    action: string | null;
    /** The closest action this Twitchat knows, when this one was unknown. */
    suggestion: string | null;
}

/** catapult-common's TwitchatAction. */
interface TwitchatNotificationAction {
    label: string;
    actionType: string;
    url?: string | null;
    message?: string | null;
    theme?: string | null;
}

/** catapult-common's TwitchatNotification. */
interface TwitchatNotification {
    /** Shared by every client relaying it, so only one of them does. */
    id: string;
    message: string;
    style?: string | null;
    icon?: string | null;
    authorName?: string | null;
    actions?: TwitchatNotificationAction[] | null;
}

interface CatapultTwitchatApi {
    /** Every protocol, by name. */
    readonly PROTOCOLS: Readonly<Record<TwitchatProtocolName, TwitchatProtocol>>;
    /** The protocol of the Twitchat on the other end of OBS, null until detected. */
    readonly protocol: TwitchatProtocolName | null;
    /** Every action this Twitchat accepts (stable's until detected). */
    readonly ACTIONS: readonly TwitchatActionType[];
    /** Every event this Twitchat sends (stable's until detected). */
    readonly EVENTS: readonly TwitchatEventType[];
    /** The event(s) answering each "get" action of this Twitchat. */
    readonly REPLIES: TwitchatProtocol["replies"];
    readonly TwitchatError: typeof TwitchatError;

    /** One shortcut per action: actions.chatFeedPause() is send("CHAT_FEED_PAUSE"). */
    readonly actions: TwitchatActions;

    /**
     * When true, every message sent to Twitchat (↑) and received through OBS (↓) is logged to
     * the console. Remembered across reloads.
     */
    debug: boolean;

    /** Whether Twitchat can be reached, i.e. the page is connected to OBS. */
    isConnected(): boolean;
    /** Whether a Twitchat answers on the other end of OBS. */
    isTwitchatConnected(): boolean;
    /** Asks Twitchat which protocol it speaks; resolves with it, null without an answer. */
    detectProtocol(options?: TwitchatRequestOptions): Promise<TwitchatProtocolName | null>;
    /** Forces the protocol, detection aside. */
    useProtocol(name: TwitchatProtocolName): void;
    /** Applies the branch picked in the integration settings: "auto" detects it, a branch forces it. */
    configure(branch: TwitchatProtocolName | "auto"): void;
    /** Sends an action; for a "get" one, resolves with the data of Twitchat's answer. */
    send(action: TwitchatActionType, data?: Record<string, any>, options?: TwitchatRequestOptions): Promise<Record<string, any> | void>;
    /** Subscribes to an event (or action sent by another client), "*" for all of them; returns the unsubscribe function. */
    on<T extends TwitchatEventType>(type: T, handler: (data: TwitchatEventMap[T], envelope: TwitchatEnvelope) => void): () => void;
    on(type: TwitchatActionType | "*", handler: (data: Record<string, any>, envelope: TwitchatEnvelope) => void): () => void;
    off(type: TwitchatEventType | TwitchatActionType | "*", handler: (...args: any[]) => void): void;
    /** Resolves with the data of the next such event. */
    once<T extends TwitchatEventType>(type: T): Promise<TwitchatEventMap[T]>;
    /** Sends a Catapult notification to Twitchat's chat, once across every connected client. */
    relay(notification: TwitchatNotification): Promise<boolean>;
}

/** Fired on document. */
interface DocumentEventMap {
    /** A notification for twitchat.js to relay. */
    "catapult:twitchat:notify": CustomEvent<TwitchatNotification>;
}

declare var CatapultTwitchat: CatapultTwitchatApi;
`;
}

const protocols = {};
for (const [name, ref] of [["stable", args.stable], ["beta", args.beta]]) {
    protocols[name] = { ref, ...await protocol(ref) };
}

await mkdir(dirname(TYPES_OUTPUT), { recursive: true });
await writeFile(JS_OUTPUT, generateJs(protocols));
await writeFile(TYPES_OUTPUT, generateTypes(protocols));
for (const [name, { ref, actions, events, replies, docs }] of Object.entries(protocols)) {
    console.log(`${name} (${ref}): ${actions.length} actions (${Object.keys(replies).length} answered), ${events.length} events, `
        + `${[...docs.keys()].filter((doc) => actions.includes(doc) || events.includes(doc)).length} documented`);
}
