/*
 * The pure half of the Twitchat catalog generator: typings to form fields, stable/beta pairing,
 * and overrides, on frozen excerpts of Twitchat's PUBLIC_API.md and TwitchatEvent.ts.
 */
import { describe, expect, it } from "vitest";
import {
    betaTypes, buildCatalog, fieldOf, fieldsOf, objectMembers, pairBeta, stableParams,
} from "../../../scripts/twitchat-catalog.mjs";

const STABLE_DOC = `
## **SET_EMERGENCY_MODE**
Starts or stops the emergency mode.
### JSON param *(optional)*
\`\`\`typescript
{
\tenabled:boolean
}
\`\`\`
## **SHOUTOUT**
Sends a shoutout to the latest raider
### JSON param *(optional)*
\`\`\`typescript
-none-
\`\`\`
## **CUSTOM_CHAT_MESSAGE**
Send chat notifications.
\`\`\`
ad add alert
\`\`\`
### JSON param *(all props are optional)*
\`\`\`typescript
{
\t//Message to display
\tmessage?: string;
\t//Message style
\tstyle?: "message"|"highlight"|"error";
\tuser?: {
\t\tname: string;
\t};
}
\`\`\`
`;

const BETA_SOURCE = `export type TwitchatEventMap = {
\t/**
\t * Execute a specific trigger manually
\t */
\tSET_EXECUTE_TRIGGER: {
\t\t/**
\t\t * Trigger ID to execute
\t\t */
\t\tid: string;
\t};
\t/**
\t * Request list of all triggers
\t * @answer ON_TRIGGER_LIST
\t */
\tGET_TRIGGER_LIST: undefined;
\tSET_SEND_CUSTOM_CHAT_MESSAGE: {
\t\tmessage?: string;
\t};
\tCUSTOM_CHAT_MESSAGE: TwitchatEventMap["SET_SEND_CUSTOM_CHAT_MESSAGE"];
\tSET_QUIZ_REVEAL: undefined;
};`;

describe("objectMembers", () => {
    it("reads each member with its optionality, type and doc comment", () => {
        expect(objectMembers("{\n  /** Trigger ID */\n  id: string;\n  // Force it\n  pause?: boolean;\n}")).toEqual([
            { name: "id", optional: false, type: "string", doc: ["Trigger ID"] },
            { name: "pause", optional: true, type: "boolean", doc: ["Force it"] },
        ]);
    });

    it("keeps a nested object or array type whole", () => {
        const members = objectMembers("{\n user?: {\n  name: string;\n };\n actions?: {\n label:string;\n }[];\n}");
        expect(members.map((m) => [m.name, m.type.replace(/\s+/g, " ")])).toEqual([
            ["user", "{ name: string; }"],
            ["actions", "{ label:string; }[]"],
        ]);
    });

    it("reads members ended by a newline only", () => {
        expect(objectMembers("{\n\tenabled:boolean\n}")).toEqual([
            { name: "enabled", optional: false, type: "boolean", doc: [] },
        ]);
    });

    it("takes the object of a union with undefined", () => {
        expect(objectMembers("| {\n enabled: boolean;\n } | undefined").map((m) => m.name)).toEqual(["enabled"]);
    });

    it("is null without an object type", () => {
        expect(objectMembers("string")).toBeNull();
        expect(objectMembers(null)).toBeNull();
    });

    it("handles trailing line comment without terminator (regression: issue 1)", () => {
        const members = objectMembers("{\n a: string // first\n b: number\n}");
        expect(members.length).toBe(2);
        expect(members[0].name).toBe("a");
        expect(members[0].type).toBe("string");
        expect(members[1].name).toBe("b");
        expect(members[1].type).toBe("number");
    });

    it("handles multi-line union types (regression: issue 3)", () => {
        const members = objectMembers('{\n a: "x"\n | "y";\n}');
        expect(members.length).toBe(1);
        expect(members[0].name).toBe("a");
        // The type should contain both literals
        expect(members[0].type).toContain('"x"');
        expect(members[0].type).toContain('"y"');
    });

    it("ignores comments with unbalanced brackets inside nested types (regression: issue 2 part 2)", () => {
        const members = objectMembers("{\n a: {\n // note (\n x: string;\n };\n b: number;\n}");
        expect(members.length).toBe(2);
        expect(members[0].name).toBe("a");
        expect(members[0].type).toContain("x: string;");
        expect(members[1].name).toBe("b");
        expect(members[1].type).toBe("number");
    });
});

describe("fieldOf", () => {
    const member = (type, optional = false) => ({ name: "x", optional, type, doc: ["The X"] });

    it("maps scalars to text, number and switch", () => {
        expect(fieldOf(member("string")).type).toBe("text");
        expect(fieldOf(member("number")).type).toBe("number");
        expect(fieldOf(member("boolean")).type).toBe("switch");
    });

    it("maps a union of string literals to a select of them", () => {
        const field = fieldOf(member('"message"|"highlight" | \'error\''));
        expect(field.type).toBe("select");
        expect(field.options).toEqual([
            { value: "message", label: "message", labelKey: null },
            { value: "highlight", label: "highlight", labelKey: null },
            { value: "error", label: "error", labelKey: null },
        ]);
    });

    it("maps nested, array and named types to json", () => {
        expect(fieldOf(member("{ name: string; }")).type).toBe("json");
        expect(fieldOf(member("string[]")).type).toBe("json");
        expect(fieldOf(member("TriggerActionCountDataAction")).type).toBe("json");
    });

    it("ignores a trailing undefined/null and reads requiredness from optionality", () => {
        const field = fieldOf(member("string | undefined", true));
        expect(field).toEqual({
            path: "x", type: "text", label: "The X", labelKey: null, required: false,
            options: null, source: null, defaultValue: null, fields: null,
        });
    });

    it("labels an undocumented member by its name", () => {
        expect(fieldOf({ name: "colIndex", optional: false, type: "number", doc: [] }).label).toBe("colIndex");
    });
});

describe("fieldsOf", () => {
    it("is empty for an action without data", () => {
        expect(fieldsOf("-none-")).toEqual([]);
        expect(fieldsOf("undefined")).toEqual([]);
    });

    it("is null for an unknown typing", () => {
        expect(fieldsOf(null)).toBeNull();
        expect(fieldsOf("SomeAlias")).toBeNull();
    });
});

describe("betaTypes", () => {
    it("reads each key's type, resolving references to other keys", () => {
        const types = betaTypes(BETA_SOURCE);
        expect(types.get("SET_EXECUTE_TRIGGER")).toContain("id: string;");
        expect(types.get("GET_TRIGGER_LIST")).toBe("undefined");
        expect(types.get("CUSTOM_CHAT_MESSAGE")).toBe(types.get("SET_SEND_CUSTOM_CHAT_MESSAGE"));
    });

    it("skips comments with unbalanced brackets in depth tracking (regression: issue 2)", () => {
        const source = `export type TwitchatEventMap = {
\tSET_A: {
\t\t/** foo ( unbalanced */
\t\tid: string;
\t};
\tSET_B: {
\t\tvalue: number;
\t};
};`;
        const types = betaTypes(source);
        // SET_A's type must NOT contain "SET_B"
        expect(types.get("SET_A")).not.toContain("SET_B");
        expect(types.get("SET_A")).toContain("id: string;");
        expect(types.get("SET_B")).toContain("value: number;");
    });
});

describe("stableParams", () => {
    it("reads the JSON param block of each section, not its other code blocks", () => {
        const params = stableParams(STABLE_DOC);
        expect(params.get("SET_EMERGENCY_MODE")).toContain("enabled:boolean");
        expect(params.get("SHOUTOUT").trim()).toBe("-none-");
        expect(params.get("CUSTOM_CHAT_MESSAGE")).toContain("message?: string;");
    });

    it("skips prose lines between the header and the fence, without reaching the next header", () => {
        const doc = "## **PROSE**\n\n### JSON param *(optional)*\nFirst line.\nSecond line.\n```typescript\n{\n\tid: string;\n}\n```\n"
            + "## **NOFENCE**\n\n### JSON param\nSome prose.\n### Other\n```ts\nnope\n```\n";
        const params = stableParams(doc);
        expect(params.get("PROSE")).toContain("id: string;");
        expect(params.has("NOFENCE")).toBe(false);
    });
});

describe("pairBeta", () => {
    it("pairs a beta action with its stable twin, by name or without SET_", () => {
        const keys = pairBeta(["EXECUTE_TRIGGER", "CUSTOM_CHAT_MESSAGE"],
            ["SET_EXECUTE_TRIGGER", "CUSTOM_CHAT_MESSAGE", "SET_QUIZ_REVEAL", "SET_CHAT_FEED_PAUSE_STATE"],
            { SET_CHAT_FEED_PAUSE_STATE: "CHAT_FEED_PAUSE" });
        expect(Object.fromEntries(keys)).toEqual({
            SET_EXECUTE_TRIGGER: "EXECUTE_TRIGGER",
            CUSTOM_CHAT_MESSAGE: "CUSTOM_CHAT_MESSAGE",
            SET_QUIZ_REVEAL: "QUIZ_REVEAL",
            SET_CHAT_FEED_PAUSE_STATE: "CHAT_FEED_PAUSE",
        });
    });
});

describe("buildCatalog", () => {
    const stable = {
        actions: ["EXECUTE_TRIGGER", "TRIGGERS_GET_ALL", "SET_EMERGENCY_MODE", "SHOUTOUT", "CUSTOM_CHAT_MESSAGE", "GET_COLS_COUNT"],
        replies: { TRIGGERS_GET_ALL: ["TRIGGER_LIST"], GET_COLS_COUNT: ["SET_COLS_COUNT"] },
        docs: new Map([["SHOUTOUT", { description: "Sends a shoutout" }]]),
    };
    const beta = {
        actions: ["SET_EXECUTE_TRIGGER", "GET_TRIGGER_LIST", "SET_SEND_CUSTOM_CHAT_MESSAGE", "CUSTOM_CHAT_MESSAGE", "SET_QUIZ_REVEAL"],
        replies: { GET_TRIGGER_LIST: ["ON_TRIGGER_LIST"] },
        docs: new Map([["SET_EXECUTE_TRIGGER", { description: "Execute a specific trigger manually" }]]),
    };
    const build = (overrides = { pairs: {}, actions: {} }) => buildCatalog({
        stable, beta, stableParams: stableParams(STABLE_DOC), betaTypes: betaTypes(BETA_SOURCE), overrides,
    });
    const byKey = (catalog) => Object.fromEntries(catalog.actions.map((a) => [a.key, a]));

    it("keeps write actions only, under one key per stable/beta pair", () => {
        const actions = byKey(build());
        expect(Object.keys(actions).sort()).toEqual(
            ["CUSTOM_CHAT_MESSAGE", "EXECUTE_TRIGGER", "QUIZ_REVEAL", "SEND_CUSTOM_CHAT_MESSAGE", "SET_EMERGENCY_MODE", "SHOUTOUT"]);
        expect(actions.EXECUTE_TRIGGER.names).toEqual({ stable: "EXECUTE_TRIGGER", beta: "SET_EXECUTE_TRIGGER" });
        expect(actions.QUIZ_REVEAL.names).toEqual({ beta: "SET_QUIZ_REVEAL" });
    });

    it("prefers the beta typing, falls back to the stable one, else raw", () => {
        const actions = byKey(build());
        expect(actions.EXECUTE_TRIGGER).toMatchObject({ form: "generated", description: "Execute a specific trigger manually" });
        expect(actions.EXECUTE_TRIGGER.fields.map((f) => [f.path, f.type, f.label])).toEqual([["id", "text", "Trigger ID to execute"]]);
        expect(actions.SET_EMERGENCY_MODE.fields.map((f) => [f.path, f.type])).toEqual([["enabled", "switch"]]);
        expect(actions.SHOUTOUT).toMatchObject({ form: "generated", fields: [], description: "Sends a shoutout" });
        expect(actions.QUIZ_REVEAL).toMatchObject({ form: "generated", fields: [] });
    });

    it("marks an action without any usable typing as raw", () => {
        const catalog = buildCatalog({
            stable: { actions: ["MYSTERY"], replies: {}, docs: new Map() },
            beta: { actions: [], replies: {}, docs: new Map() },
            stableParams: new Map(), betaTypes: new Map(), overrides: { pairs: {}, actions: {} },
        });
        expect(catalog.actions[0]).toMatchObject({ key: "MYSTERY", form: "raw", fields: [] });
    });

    it("applies overrides and sorts by priority, then key", () => {
        const catalog = build({
            pairs: {},
            actions: { SHOUTOUT: { priority: 1, fields: [{ path: "x", type: "text" }] } },
        });
        expect(catalog.actions[0]).toMatchObject({ key: "SHOUTOUT", priority: 1, form: "override" });
        expect(catalog.actions[0].fields).toEqual([{ path: "x", type: "text" }]);
        expect(catalog.actions.slice(1).map((a) => a.key)).toEqual(
            ["CUSTOM_CHAT_MESSAGE", "EXECUTE_TRIGGER", "QUIZ_REVEAL", "SEND_CUSTOM_CHAT_MESSAGE", "SET_EMERGENCY_MODE"]);
    });
});
