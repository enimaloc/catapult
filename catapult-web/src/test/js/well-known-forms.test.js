/*
 * The well-known forms: each dedicated descriptor reads its raw text into form values and writes them
 * back without losing what the form doesn't show; flat SchemaStore schemas give generated forms.
 */
import { beforeEach, describe, expect, it } from "vitest";
import { load } from "./helpers.js";

let F;
beforeEach(async () => {
    await load("well-known-forms");
    F = window.CatapultWellKnownForms;
});

const roundTrip = (descriptor, text) => F.serialize(descriptor, F.parse(descriptor, text));

describe("security.txt", () => {
    const TEXT = [
        "# Our policy",
        "Contact: mailto:security@example.org",
        "Contact: https://example.org/security",
        "Expires: 2027-01-01T00:00:00.000Z",
        "Preferred-Languages: fr, en",
        "X-Custom: kept",
        ""
    ].join("\n");

    it("is matched only under /.well-known/", () => {
        expect(F.forPath("WELL_KNOWN", "security.txt").id).toBe("security.txt");
        expect(F.forPath("ROOT", "security.txt")).toBeNull();
        expect(F.forPath("WELL_KNOWN", "other.txt")).toBeNull();
    });

    it("reads known fields and keeps comments and unknown lines after them", () => {
        const d = F.forPath("WELL_KNOWN", "security.txt");
        const model = F.parse(d, TEXT);
        expect(model.values.Contact).toEqual(["mailto:security@example.org", "https://example.org/security"]);
        expect(model.values.Expires).toBe("2027-01-01T00:00:00.000Z");
        expect(model.values["Preferred-Languages"]).toBe("fr, en");
        model.values.Contact.push("mailto:new@example.org");
        expect(F.serialize(d, model)).toBe([
            "Contact: mailto:security@example.org",
            "Contact: https://example.org/security",
            "Contact: mailto:new@example.org",
            "Expires: 2027-01-01T00:00:00.000Z",
            "Preferred-Languages: fr, en",
            "# Our policy",
            "X-Custom: kept",
            ""
        ].join("\n"));
    });

    it("warns when Expires is more than a year away", () => {
        const d = F.forPath("WELL_KNOWN", "security.txt");
        const now = new Date("2026-10-16T00:00:00Z");
        expect(F.expiresFar(F.parse(d, "Expires: 2027-01-01T00:00:00.000Z\n"), now)).toBe(false);
        expect(F.expiresFar(F.parse(d, "Expires: 2028-01-01T00:00:00.000Z\n"), now)).toBe(true);
    });
});

describe("JSON descriptors", () => {
    it("microsoft-identity-association.json keeps unknown keys", () => {
        const d = F.forPath("WELL_KNOWN", "microsoft-identity-association.json");
        const text = JSON.stringify({ associatedApplications: [{ applicationId: "a" }, { applicationId: "z", note: "x" }], extra: 1 });
        const model = F.parse(d, text);
        expect(model.values.applicationIds).toEqual(["a"]);
        model.values.applicationIds = ["a", "b"];
        expect(JSON.parse(F.serialize(d, model))).toEqual({
            associatedApplications: [{ applicationId: "a" }, { applicationId: "z", note: "x" }, { applicationId: "b" }], extra: 1
        });
    });

    it("assetlinks.json edits android apps and keeps other statements", () => {
        const d = F.forPath("WELL_KNOWN", "assetlinks.json");
        const web = { relation: ["delegate_permission/common.get_login_creds"], target: { namespace: "web", site: "https://x" } };
        const android = {
            relation: ["delegate_permission/common.handle_all_urls"],
            target: { namespace: "android_app", package_name: "org.app", sha256_cert_fingerprints: ["AA:BB"] }
        };
        const model = F.parse(d, JSON.stringify([web, android]));
        expect(model.values.apps).toEqual([{ package_name: "org.app", sha256_cert_fingerprints: ["AA:BB"] }]);
        model.values.apps.push({ package_name: "org.two", sha256_cert_fingerprints: ["CC:DD"] });
        expect(JSON.parse(F.serialize(d, model))).toEqual([web, android, {
            relation: ["delegate_permission/common.handle_all_urls"],
            target: { namespace: "android_app", package_name: "org.two", sha256_cert_fingerprints: ["CC:DD"] }
        }]);
    });

    it("apple-app-site-association edits applinks details and webcredentials", () => {
        const d = F.forPath("WELL_KNOWN", "apple-app-site-association");
        const mixed = { appIDs: ["T.mixed"], components: [{ "/": "/m/*" }, { "#": "x" }] };
        const text = JSON.stringify({
            applinks: { details: [mixed, { appIDs: ["T.app"], components: [{ "/": "/a/*" }] }] },
            appclips: { apps: ["T.clip"] }
        });
        const model = F.parse(d, text);
        expect(model.values.details).toEqual([{ appIDs: ["T.app"], paths: ["/a/*"], _origin: 1 }]);
        model.values.webcredentials = ["T.app"];
        model.values.details[0].paths.push("/b/*");
        expect(JSON.parse(F.serialize(d, model))).toEqual({
            applinks: { details: [mixed, { appIDs: ["T.app"], components: [{ "/": "/a/*" }, { "/": "/b/*" }] }] },
            appclips: { apps: ["T.clip"] },
            webcredentials: { apps: ["T.app"] }
        });
    });

    it("refuses unreadable JSON with a message", () => {
        const d = F.forPath("WELL_KNOWN", "assetlinks.json");
        expect(() => F.parse(d, "{oops")).toThrow();
        expect(() => F.parse(d, "{}")).toThrow(); // assetlinks is an array
    });

    it("starts from an empty document when the raw text is empty", () => {
        const d = F.forPath("WELL_KNOWN", "microsoft-identity-association.json");
        const model = F.parse(d, "");
        model.values.applicationIds = ["id-1"];
        expect(JSON.parse(F.serialize(d, model))).toEqual({ associatedApplications: [{ applicationId: "id-1" }] });
    });
});

describe("generated from a schema", () => {
    const FLAT = {
        type: "object",
        required: ["gpc"],
        properties: {
            gpc: { type: "boolean", title: "GPC" },
            lastUpdate: { type: "string", description: "ISO date" },
            level: { enum: ["a", "b"] },
            tags: { type: "array", items: { type: "string" } }
        }
    };

    it("builds fields from a flat schema and keeps unknown keys", () => {
        const d = F.fromSchema(FLAT);
        expect(d.fields.map(f => [f.key, f.type, !!f.required])).toEqual([
            ["gpc", "boolean", true], ["lastUpdate", "text", false], ["level", "enum", false], ["tags", "list", false]
        ]);
        const model = F.parse(d, JSON.stringify({ gpc: false, other: 1 }));
        model.values.gpc = true;
        expect(JSON.parse(F.serialize(d, model))).toEqual({ gpc: true, other: 1 });
    });

    it("leaves __proto__, constructor and prototype keys out of the form, keeping them in the content", () => {
        const d = F.fromSchema({ type: "object", properties: JSON.parse(
            '{"__proto__": {"type": "string"}, "constructor": {"type": "string"}, "prototype": {"type": "string"}, "a": {"type": "string"}}') });
        expect(d.fields.map(f => f.key)).toEqual(["a"]);
        const model = F.parse(d, '{"__proto__": "p", "constructor": "c", "a": "x"}');
        model.values.a = "y";
        const doc = JSON.parse(F.serialize(d, model));
        expect(Object.getOwnPropertyDescriptor(doc, "__proto__").value).toBe("p");
        expect(Object.getOwnPropertyDescriptor(doc, "constructor").value).toBe("c");
        expect(doc.a).toBe("y");
        expect(F.fromSchema({ type: "object", properties: JSON.parse('{"__proto__": {"type": "string"}}') })).toBeNull();
    });

    it("has no form for a nested schema", () => {
        expect(F.fromSchema({ type: "object", properties: { a: { type: "object" } } })).toBeNull();
        expect(F.fromSchema({ type: "array" })).toBeNull();
    });

    it("lists schema problems: missing required and wrong primitive types", () => {
        expect(F.checkSchema(FLAT, JSON.stringify({ gpc: true }))).toEqual([]);
        expect(F.checkSchema(FLAT, JSON.stringify({ lastUpdate: 3 }))).toEqual(["gpc: required", "lastUpdate: string expected"]);
        expect(F.checkSchema(FLAT, "not json")).toEqual([]);
    });
});

describe("never loses content", () => {
    const ms = () => F.forPath("WELL_KNOWN", "microsoft-identity-association.json");
    const sec = () => F.forPath("WELL_KNOWN", "security.txt");
    const aasa = () => F.forPath("WELL_KNOWN", "apple-app-site-association");

    it("deleting the first of two MS entries with extra keys keeps both extra entries", () => {
        const doc = { associatedApplications: [{ applicationId: "a", n: 1 }, { applicationId: "b", n: 2 }, { applicationId: "c" }, { applicationId: "d" }] };
        const model = F.parse(ms(), JSON.stringify(doc));
        expect(model.values.applicationIds).toEqual(["c", "d"]);
        model.values.applicationIds = ["d"];
        expect(JSON.parse(F.serialize(ms(), model)).associatedApplications)
            .toEqual([{ applicationId: "a", n: 1 }, { applicationId: "b", n: 2 }, { applicationId: "d" }]);
    });

    it("keeps unrecognised assetlinks statements in order and does not move android ones", () => {
        const d = F.forPath("WELL_KNOWN", "assetlinks.json");
        const android = pkg => ({ relation: ["delegate_permission/common.handle_all_urls"],
            target: { namespace: "android_app", package_name: pkg, sha256_cert_fingerprints: ["AA"] } });
        const odd = { relation: ["other"], target: { namespace: "android_app", package_name: "x", sha256_cert_fingerprints: ["AA"] } };
        const web = { relation: ["r"], target: { namespace: "web", site: "https://x" } };
        const model = F.parse(d, JSON.stringify([android("one"), odd, web, android("two")]));
        expect(model.values.apps.map(a => a.package_name)).toEqual(["one", "two"]);
        model.values.apps[1].package_name = "three";
        expect(JSON.parse(F.serialize(d, model))).toEqual([android("one"), odd, web, android("three")]);
    });

    it("refuses a PGP-signed security.txt", () => {
        expect(() => F.parse(sec(), "-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\nContact: a\n")).toThrow("signed security.txt");
    });

    it("keeps a repeated Expires and an empty Contact line", () => {
        const model = F.parse(sec(), "Expires: 2027-01-01T00:00:00Z\nExpires: 2030-01-01T00:00:00Z\nContact:\nContact: mailto:a\n");
        expect(model.values.Expires).toBe("2027-01-01T00:00:00Z");
        expect(model.values.Contact).toEqual(["mailto:a"]);
        expect(F.serialize(sec(), model)).toBe("Contact: mailto:a\nExpires: 2027-01-01T00:00:00Z\nExpires: 2030-01-01T00:00:00Z\nContact:\n");
    });

    it("keeps CRLF line endings", () => {
        const model = F.parse(sec(), "Contact: mailto:a\r\nExpires: 2027-01-01T00:00:00Z\r\n");
        expect(F.serialize(sec(), model)).toBe("Contact: mailto:a\r\nExpires: 2027-01-01T00:00:00Z\r\n");
    });

    it("a detail with a comment component is kept byte-identical when the pure one is deleted", () => {
        const first = { appIDs: ["T"], components: [{ "/": "/a" }, { "#": "c1", comment: "x" }, { "?": "q" }] };
        const doc = { applinks: { details: [first, { appIDs: ["U"], components: [{ "/": "/b" }] }] } };
        const model = F.parse(aasa(), JSON.stringify(doc));
        expect(model.values.details).toEqual([{ appIDs: ["U"], paths: ["/b"], _origin: 1 }]);
        model.values.details = [];
        expect(JSON.parse(F.serialize(aasa(), model)).applinks.details).toEqual([first]);
    });

    it("keeps legacy AASA details untouched and removes emptied webcredentials", () => {
        const doc = { applinks: { details: [{ appID: "T.old", paths: ["/x"] }] }, webcredentials: { apps: ["T.a"] } };
        const model = F.parse(aasa(), JSON.stringify(doc));
        expect(model.values.details).toEqual([]);
        model.values.webcredentials = [];
        expect(JSON.parse(F.serialize(aasa(), model))).toEqual({ applinks: { details: [{ appID: "T.old", paths: ["/x"] }] } });
    });

    it("never spreads a string into characters: wrong shapes are refused or kept aside", () => {
        expect(() => F.parse(ms(), JSON.stringify({ associatedApplications: "abc" }))).toThrow("associatedApplications: array expected");
        expect(() => F.parse(aasa(), JSON.stringify({ applinks: { details: "abc" } }))).toThrow("applinks.details: array expected");
        expect(() => F.parse(aasa(), JSON.stringify({ webcredentials: { apps: "abc" } }))).toThrow("webcredentials.apps: array expected");
        const model = F.parse(aasa(), JSON.stringify({ applinks: { details: [{ appIDs: "T" }] } }));
        expect(model.values.details).toEqual([]);
    });

    it("never leaks a TypeError", () => {
        const d = F.forPath("WELL_KNOWN", "assetlinks.json");
        expect(() => F.parse(d, "[null, 3, {\"target\": 4}]")).not.toThrow();
        expect(() => F.parse(aasa(), "null")).toThrow(/object expected/);
    });

    describe("generated forms", () => {
        const SCHEMA = { type: "object", properties: {
            gpc: { type: "boolean" }, name: { type: "string" }, n: { type: "number" },
            level: { enum: ["a", "b"] }, ids: { type: "array", items: { type: "number" } }
        } };

        it("writes nothing for untouched values", () => {
            const d = F.fromSchema(SCHEMA);
            const text = JSON.stringify({ name: "x", ids: [1, 2], level: "a" });
            const model = F.parse(d, text);
            expect(JSON.parse(F.serialize(d, model))).toEqual({ name: "x", ids: [1, 2], level: "a" });
        });

        it("refuses values of the wrong type", () => {
            const d = F.fromSchema(SCHEMA);
            expect(() => F.parse(d, '{"name": 3}')).toThrow("name: string expected");
            expect(() => F.parse(d, '{"gpc": "yes"}')).toThrow("gpc: boolean expected");
            expect(() => F.parse(d, '{"n": "1"}')).toThrow("n: number expected");
            expect(() => F.parse(d, '{"ids": "1"}')).toThrow("ids: array expected");
            expect(() => F.parse(d, '{"ids": ["1"]}')).toThrow("ids: array expected");
            expect(() => F.parse(d, '{"level": "z"}')).toThrow("level: one of a, b expected");
        });

        it("round-trips number lists as numbers, keeping non-numeric items as strings", () => {
            const d = F.fromSchema(SCHEMA);
            const model = F.parse(d, '{"ids": [1, 2]}');
            expect(model.values.ids).toEqual(["1", "2"]);
            model.values.ids = ["1", "2", "3.5", "x"];
            expect(JSON.parse(F.serialize(d, model)).ids).toEqual([1, 2, 3.5, "x"]);
        });
    });

    it("keeps apple details in their slots when one is deleted or edited", () => {
        const A = { appIDs: ["A"], components: [{ "/": "/a" }] };
        const mixed = { appIDs: ["M"], components: [{ "#": "c" }] };
        const B = { appIDs: ["B"], components: [{ "/": "/b" }] };
        const text = JSON.stringify({ applinks: { details: [A, mixed, B] } });
        let model = F.parse(aasa(), text);
        model.values.details = model.values.details.filter(d => d.appIDs[0] !== "A");
        expect(JSON.parse(F.serialize(aasa(), model)).applinks.details).toEqual([mixed, B]);
        model = F.parse(aasa(), text);
        model.values.details[1].paths = ["/b2"];
        model.values.details.push({ appIDs: ["N"], paths: [] });
        expect(JSON.parse(F.serialize(aasa(), model)).applinks.details)
            .toEqual([A, mixed, { appIDs: ["B"], components: [{ "/": "/b2" }] }, { appIDs: ["N"] }]);
    });

    it("keeps degenerate entries untouched on a no-op round trip", () => {
        const cases = [
            [ms(), { associatedApplications: [{ applicationId: "" }, { applicationId: "x" }] }],
            [F.forPath("WELL_KNOWN", "assetlinks.json"), [
                { relation: ["delegate_permission/common.handle_all_urls"], target: { namespace: "android_app", package_name: "", sha256_cert_fingerprints: ["A"] } },
                { relation: ["delegate_permission/common.handle_all_urls"], target: { namespace: "android_app", package_name: "p", sha256_cert_fingerprints: ["A", ""] } }]],
            [aasa(), { applinks: { details: [{ appIDs: [] }, { appIDs: ["a", ""] }, { appIDs: ["a"], components: [{ "/": "" }] }] }, webcredentials: { apps: [] } }]
        ];
        for (const [d, doc] of cases) {
            expect(JSON.parse(F.serialize(d, F.parse(d, JSON.stringify(doc))))).toEqual(doc);
        }
    });

    it("refuses a webcredentials that is not an object", () => {
        expect(() => F.parse(aasa(), JSON.stringify({ webcredentials: "abc" }))).toThrow("webcredentials: object expected");
    });

    it("drops whitespace-only items of a number list", () => {
        const d = F.fromSchema({ type: "object", properties: { ids: { type: "array", items: { type: "number" } } } });
        const model = F.parse(d, '{"ids": [1]}');
        model.values.ids = ["1", "  ", "2"];
        expect(JSON.parse(F.serialize(d, model)).ids).toEqual([1, 2]);
    });
});
