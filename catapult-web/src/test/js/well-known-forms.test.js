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
        const text = JSON.stringify({ associatedApplications: [{ applicationId: "a", note: "x" }], extra: 1 });
        const model = F.parse(d, text);
        expect(model.values.applicationIds).toEqual(["a"]);
        model.values.applicationIds = ["a", "b"];
        expect(JSON.parse(F.serialize(d, model))).toEqual({
            associatedApplications: [{ applicationId: "a", note: "x" }, { applicationId: "b" }], extra: 1
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
        const text = JSON.stringify({
            applinks: { details: [{ appIDs: ["T.app"], components: [{ "/": "/a/*" }, { "#": "x" }] }] },
            appclips: { apps: ["T.clip"] }
        });
        const model = F.parse(d, text);
        expect(model.values.details).toEqual([{ appIDs: ["T.app"], paths: ["/a/*"] }]);
        model.values.webcredentials = ["T.app"];
        model.values.details[0].paths.push("/b/*");
        expect(JSON.parse(F.serialize(d, model))).toEqual({
            applinks: { details: [{ appIDs: ["T.app"], components: [{ "/": "/a/*" }, { "#": "x" }, { "/": "/b/*" }] }] },
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
