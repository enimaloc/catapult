#!/usr/bin/env node
/*
 * Generates Twitchat's public API protocol for static/js/twitchat.js, which Twitchat can't be
 * asked for at runtime (unlike OBS and its GetVersion):
 *   - static/js/twitchat-protocol.js: the action and event names, and which event answers which
 *     action, served with the page;
 *   - src/types/twitchat.d.ts: the IDE typings of window.CatapultTwitchat, never served.
 * Names come from Twitchat's source (src_front/events/TwitchatEvent.ts), descriptions and data
 * examples from its PUBLIC_API.md, which documents only some of them.
 *
 *   npm run generate:twitchat-protocol                 # Twitchat's main branch
 *   npm run generate:twitchat-protocol -- --ref v15.0  # a tag or branch
 */
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const JS_OUTPUT = resolve(ROOT, "src/main/resources/static/js/twitchat-protocol.js");
const TYPES_OUTPUT = resolve(ROOT, "src/types/twitchat.d.ts");

const { values: args } = parseArgs({ options: { ref: { type: "string", default: "main" } } });
const base = `https://raw.githubusercontent.com/Durss/Twitchat/${args.ref}`;
const EVENTS_SOURCE = `${base}/src_front/events/TwitchatEvent.ts`;
const DOCS_SOURCE = `${base}/PUBLIC_API.md`;

/**
 * The event Twitchat answers each "get" action with, read from the handlers in Twitchat's
 * source (PublicAPI listeners in src_front/store and src_front/components/overlays): not
 * derivable from the names (GET_COLS_COUNT -> SET_COLS_COUNT, COUNTER_GET -> COUNTER_UPDATE…).
 * The *_OVERLAY_PRESENCE ones are answered by the overlay itself, so only while it's open.
 */
const REPLIES = {
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

/** The string literals of `export const <name> = [...] as const`. */
function constList(source, name) {
    const match = source.match(new RegExp(`export const ${name}\\s*=\\s*\\[([\\s\\S]*?)\\]\\s*as const`));
    if (!match) throw new Error(`${name} not found in ${EVENTS_SOURCE}`);
    return [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
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

function generateJs(actions, events, replies) {
    const list = (names) => `[\n${names.map((name) => `        "${name}",`).join("\n")}\n    ]`;
    const replyEntries = Object.entries(replies)
        .map(([action, answers]) => `        ${action}: ${JSON.stringify(answers)},`).join("\n");
    return `// Generated by scripts/generate-twitchat-protocol.mjs from ${EVENTS_SOURCE}
// Don't edit by hand: rerun \`npm run generate:twitchat-protocol\` instead.
window.CatapultTwitchatProtocol = Object.freeze({
    /** Every action Twitchat accepts. */
    actions: Object.freeze(${list(actions)}),
    /** Every event Twitchat sends. */
    events: Object.freeze(${list(events)}),
    /** The event(s) answering each "get" action, the first one received being the answer. */
    replies: Object.freeze({
${replyEntries}
    }),
});
`;
}

function generateTypes(actions, events, replies, docs) {
    const shortcuts = actions.map((action) => {
        const answers = replies[action];
        const returns = answers
            ? `Resolves with the data of Twitchat's answer, ${answers.join(" or ")}; rejects without one in time.`
            : "Resolves once sent: Twitchat doesn't answer it.";
        return jsdoc("    ", [...docLines(docs.get(action), "Data"), `Sends ${action}. ${returns}`])
            + `    ${shortcutName(action)}(data?: Record<string, any>, options?: TwitchatRequestOptions): `
            + `Promise<${answers ? "Record<string, any>" : "void"}>;`;
    }).join("\n");

    const eventDocs = events.map((event) => jsdoc("    ", docLines(docs.get(event), "Data"))
        + `    ${event}: Record<string, any>;`).join("\n");

    return `// Generated by scripts/generate-twitchat-protocol.mjs from ${EVENTS_SOURCE}
// and ${DOCS_SOURCE}
// Don't edit by hand: rerun \`npm run generate:twitchat-protocol\` instead.
// IDE typings of static/js/twitchat.js; never served.

/** Every action Twitchat accepts. */
type TwitchatActionType = ${union(actions)};

/** Every event Twitchat sends. */
type TwitchatEventType = ${union(events)};

/** Every event Twitchat sends, documented when PUBLIC_API.md does. */
interface TwitchatEventMap {
${eventDocs}
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
    /** The closest known action, when this one was unknown. */
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
    /** Every action Twitchat accepts. */
    readonly ACTIONS: readonly TwitchatActionType[];
    /** Every event Twitchat sends. */
    readonly EVENTS: readonly TwitchatEventType[];
    /** The event(s) answering each "get" action. */
    readonly REPLIES: Readonly<Partial<Record<TwitchatActionType, readonly TwitchatEventType[]>>>;
    readonly TwitchatError: typeof TwitchatError;

    /** One shortcut per action: actions.chatFeedPause() is send("CHAT_FEED_PAUSE"). */
    readonly actions: TwitchatActions;

    /** Whether Twitchat can be reached, i.e. the page is connected to OBS. */
    isConnected(): boolean;
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

const [eventsSource, markdown] = await Promise.all([fetchText(EVENTS_SOURCE), fetchText(DOCS_SOURCE)]);
const actions = constList(eventsSource, "TwitchatActionTypeList");
const events = constList(eventsSource, "TwitchatEventTypeList").filter((event) => !NOT_TWITCHAT_EVENTS.has(event));
const docs = documentation(markdown);

const replies = {};
for (const [action, answers] of Object.entries(REPLIES)) {
    const unknown = [action, ...answers].filter((name) => !actions.includes(name) && !events.includes(name));
    if (unknown.length) {
        console.warn(`Reply ${action} -> ${answers} skipped, unknown to this Twitchat: ${unknown.join(", ")}`);
        continue;
    }
    replies[action] = answers;
}

await mkdir(dirname(TYPES_OUTPUT), { recursive: true });
await writeFile(JS_OUTPUT, generateJs(actions, events, replies));
await writeFile(TYPES_OUTPUT, generateTypes(actions, events, replies, docs));
console.log(`${actions.length} actions (${Object.keys(replies).length} answered), ${events.length} events, `
    + `${[...docs.keys()].filter((name) => actions.includes(name) || events.includes(name)).length} documented`);
