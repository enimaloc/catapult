/*
 * The pure half of the Twitchat catalog (catapult-common's twitchat/catalog.json): every "write"
 * action of either protocol under one protocol-neutral key, its form fields deduced from its
 * typing (the beta's TwitchatEventMap, else the stable PUBLIC_API.md's "JSON param"), then the
 * hand-written overrides of twitchat-catalog-overrides.json. Fetching and writing stay in
 * generate-twitchat-protocol.mjs.
 */

const SCALARS = { string: "text", number: "number", boolean: "switch" };
const OPENERS = "{[(<";
const CLOSERS = "}])>";

/** The members of the first object type literal in `source`, null without one. */
export function objectMembers(source) {
    if (source == null) return null;
    const start = source.indexOf("{");
    if (start === -1) return null;
    const members = [];
    let depth = 0;
    let text = "";
    let doc = [];
    const flush = () => {
        const match = text.trim().match(/^([A-Za-z_$][\w$]*)(\?)?\s*:\s*([\s\S]+)$/);
        if (match) {
            members.push({ name: match[1], optional: Boolean(match[2]), type: match[3].trim(), doc: doc.filter(Boolean) });
            doc = [];
        }
        text = "";
    };
    for (let i = start + 1; i < source.length; i++) {
        const c = source[i];
        // Skip comments at any depth, collecting doc only at depth 0
        if (c === "/" && source[i + 1] === "/") {
            const end = source.indexOf("\n", i);
            const stop = end === -1 ? source.length : end;
            if (depth === 0) {
                doc.push(source.slice(i + 2, stop).trim());
                i = stop - 1; // -1 so the loop's i++ lands on the newline
            } else {
                i = stop; // Skip to newline but don't process it (will continue)
            }
            continue;
        }
        if (c === "/" && source[i + 1] === "*") {
            const end = source.indexOf("*/", i + 2);
            const stop = end === -1 ? source.length : end;
            if (depth === 0) {
                doc.push(...source.slice(i + 2, stop).split("\n").map((line) => line.replace(/^\s*\*+\s?/, "").trim()));
            }
            i = stop + 1;
            continue;
        }
        if (OPENERS.includes(c)) depth++;
        if (CLOSERS.includes(c)) {
            if (depth === 0) break;
            depth--;
        }
        if (depth === 0 && c === ";") {
            flush();
            continue;
        }
        if (depth === 0 && c === ",") {
            flush();
            continue;
        }
        if (depth === 0 && c === "\n") {
            // Check if the line continues (ends with | or next line starts with |)
            const trimmedText = text.trim();
            const nextNonSpace = source.slice(i + 1).search(/\S/);
            const nextChar = nextNonSpace === -1 ? "" : source[i + 1 + nextNonSpace];
            if (!trimmedText.endsWith("|") && nextChar !== "|") {
                flush();
            }
            continue;
        }
        text += c;
    }
    flush();
    return members;
}

/** The form field editing one member: a scalar input, a select of string literals, else raw JSON. */
export function fieldOf(member) {
    const type = member.type.replace(/(\s*\|\s*(undefined|null))+\s*$/, "").trim();
    const field = {
        path: member.name, type: "json", label: member.doc[0] || member.name, labelKey: null,
        required: !member.optional, options: null, source: null, defaultValue: null, fields: null,
    };
    const literals = [...type.matchAll(/"([^"]*)"|'([^']*)'/g)].map((m) => m[1] ?? m[2]);
    if (SCALARS[type]) {
        field.type = SCALARS[type];
    } else if (literals.length && type.replace(/"[^"]*"|'[^']*'|\|/g, "").trim() === "") {
        field.type = "select";
        field.options = literals.map((value) => ({ value, label: value, labelKey: null }));
    }
    return field;
}

/** The fields of a typing: [] for an action without data, null when it can't be read. */
export function fieldsOf(typeSource) {
    if (typeSource == null) return null;
    if (/^\s*(-none-|undefined|void)\s*$/.test(typeSource)) return [];
    const members = objectMembers(typeSource);
    return members == null ? null : members.map(fieldOf);
}

/** Each key of the beta's TwitchatEventMap with the type after its colon, references resolved. */
export function betaTypes(eventsSource) {
    const types = new Map();
    const block = eventsSource.match(/export type TwitchatEventMap\s*=\s*\{\n([\s\S]*?)\n\};/);
    if (!block) return types;
    const body = block[1];
    for (const match of body.matchAll(/^\t([A-Z][A-Z0-9_]+)\??:/gm)) {
        const from = match.index + match[0].length;
        let depth = 0;
        let end = from;
        for (; end < body.length; end++) {
            // Skip comments in depth tracking
            if (body[end] === "/" && body[end + 1] === "/") {
                const lineEnd = body.indexOf("\n", end);
                end = lineEnd === -1 ? body.length : lineEnd;
                continue;
            }
            if (body[end] === "/" && body[end + 1] === "*") {
                const blockEnd = body.indexOf("*/", end + 2);
                end = blockEnd === -1 ? body.length : blockEnd + 1;
                continue;
            }
            const c = body[end];
            if ("{[(".includes(c)) depth++;
            else if ("}])".includes(c)) depth--;
            else if (c === ";" && depth === 0) break;
        }
        types.set(match[1], body.slice(from, end).trim());
    }
    for (const [name, type] of types) {
        const reference = type.match(/^TwitchatEventMap\["([A-Z0-9_]+)"\]$/);
        if (reference) types.set(name, types.get(reference[1]) ?? null);
    }
    return types;
}

/** PUBLIC_API.md's "### JSON param" block of each "## **NAME**" section (prose lines may precede its fence). */
export function stableParams(markdown) {
    const params = new Map();
    const sections = markdown.split(/^## \*\*([A-Z_]+)\*\*\s*$/m);
    for (let i = 1; i < sections.length; i += 2) {
        const block = sections[i + 1].match(/^### JSON param[^\n]*\n(?:(?!```|#)[^\n]*\n)*```[a-z]*\n([\s\S]*?)```/m);
        if (block) params.set(sections[i], block[1].replace(/\t/g, "    "));
    }
    return params;
}

/** The catalog key of each beta action: its stable twin's name when it has one, else its name without SET_. */
export function pairBeta(stableActions, betaActions, pairs = {}) {
    const stable = new Set(stableActions);
    const keys = new Map();
    for (const name of betaActions) {
        const stripped = name.replace(/^SET_/, "");
        const twin = pairs[name] ?? (stable.has(name) ? name : stable.has(stripped) ? stripped : null);
        keys.set(name, twin ?? stripped);
    }
    return keys;
}

/** Whether an action changes something, rather than asking for data ("get" actions). */
const isWrite = (name, replies) => !name.startsWith("GET_") && !/_GET(_ALL)?$/.test(name) && !replies[name];

const byPriorityThenKey = (a, b) =>
    (a.priority ?? Number.MAX_SAFE_INTEGER) - (b.priority ?? Number.MAX_SAFE_INTEGER) || a.key.localeCompare(b.key);

/** The whole catalog, as written to catalog.json. */
export function buildCatalog({ stable, beta, stableParams: params, betaTypes: types, overrides }) {
    const actions = new Map();
    const typings = new Map();
    const entry = (key) => {
        if (!actions.has(key)) {
            actions.set(key, { key, names: {}, description: "", priority: null, form: "raw", fields: [] });
            typings.set(key, {});
        }
        return actions.get(key);
    };
    for (const name of stable.actions.filter((n) => isWrite(n, stable.replies))) {
        const action = entry(name);
        action.names.stable = name;
        action.description = stable.docs.get(name)?.description ?? "";
        typings.get(name).stable = fieldsOf(params.get(name) ?? null);
    }
    const keys = pairBeta(stable.actions, beta.actions, overrides.pairs);
    for (const name of beta.actions.filter((n) => isWrite(n, beta.replies))) {
        const key = keys.get(name);
        const action = entry(key);
        action.names.beta = name;
        action.description = beta.docs.get(name)?.description || action.description;
        typings.get(key).beta = fieldsOf(types.get(name) ?? null);
    }
    for (const action of actions.values()) {
        const { beta: betaFields, stable: stableFields } = typings.get(action.key);
        const fields = betaFields ?? stableFields;
        if (fields) Object.assign(action, { form: "generated", fields });
        const override = overrides.actions[action.key];
        if (override) Object.assign(action, { form: "override" }, override);
    }
    return { version: 1, actions: [...actions.values()].sort(byPriorityThenKey) };
}
