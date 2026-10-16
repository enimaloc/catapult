/**
 * The well-known dialog's forms, without the DOM (admin-well-known.js renders them): a descriptor
 * per known path (dedicated) or built from a flat SchemaStore schema (generated), reading raw text
 * into form values and writing them back. What the form doesn't show is kept: JSON values are merged
 * into the parsed original (unknown keys and array entries stay), security.txt's comments and unknown
 * lines follow the known fields in their original order.
 */
window.CatapultWellKnownForms = (function () {
    const SECURITY_FIELDS = [
        { key: "Contact", type: "list", required: true },
        { key: "Expires", type: "datetime", required: true },
        { key: "Encryption", type: "list" },
        { key: "Acknowledgments", type: "list" },
        { key: "Preferred-Languages", type: "text", help: "fr, en" },
        { key: "Canonical", type: "list" },
        { key: "Policy", type: "list" },
        { key: "Hiring", type: "list" }
    ].map(f => ({ ...f, label: f.key }));

    const HANDLE_ALL_URLS = "delegate_permission/common.handle_all_urls";

    const clone = value => (value === undefined ? undefined : JSON.parse(JSON.stringify(value)));

    function readJson(text, fallback) {
        if (text.trim() === "") return clone(fallback);
        try {
            return JSON.parse(text);
        } catch (e) {
            throw new Error(e.message);
        }
    }

    const isObject = value => value !== null && typeof value === "object" && !Array.isArray(value);

    const DEDICATED = {
        "security.txt": {
            id: "security.txt",
            format: "securitytxt",
            fields: SECURITY_FIELDS,
            parse(text) {
                const values = {};
                const rest = [];
                SECURITY_FIELDS.forEach(f => { values[f.key] = f.type === "list" ? [] : ""; });
                for (const line of text.split(/\r?\n/)) {
                    const match = /^([A-Za-z-]+):\s*(.*)$/.exec(line);
                    const field = match && SECURITY_FIELDS.find(f => f.key.toLowerCase() === match[1].toLowerCase());
                    if (!field) {
                        if (line.trim() !== "") rest.push(line);
                        continue;
                    }
                    if (field.type === "list") values[field.key].push(match[2]);
                    else values[field.key] = match[2];
                }
                return { values, original: rest };
            },
            serialize({ values, original }) {
                const lines = [];
                for (const f of SECURITY_FIELDS) {
                    const v = values[f.key];
                    if (f.type === "list") (v || []).filter(x => x !== "").forEach(x => lines.push(`${f.key}: ${x}`));
                    else if (v) lines.push(`${f.key}: ${v}`);
                }
                return [...lines, ...original, ""].join("\n");
            }
        },
        "microsoft-identity-association.json": {
            id: "microsoft-identity-association.json",
            format: "json",
            fields: [{ key: "applicationIds", label: "applicationId", type: "list", required: true }],
            parse(text) {
                const doc = readJson(text, { associatedApplications: [] });
                if (!isObject(doc)) throw new Error("object expected");
                const apps = Array.isArray(doc.associatedApplications) ? doc.associatedApplications : [];
                return { values: { applicationIds: apps.map(a => a?.applicationId ?? "") }, original: doc };
            },
            serialize({ values, original }) {
                const doc = clone(original);
                const before = Array.isArray(doc.associatedApplications) ? doc.associatedApplications : [];
                doc.associatedApplications = values.applicationIds.filter(x => x !== "")
                    .map((id, i) => ({ ...(isObject(before[i]) ? before[i] : {}), applicationId: id }));
                return JSON.stringify(doc, null, 2);
            }
        },
        "assetlinks.json": {
            id: "assetlinks.json",
            format: "json",
            fields: [{
                key: "apps", label: "Android", type: "group", fields: [
                    { key: "package_name", label: "package_name", type: "text", required: true },
                    { key: "sha256_cert_fingerprints", label: "sha256_cert_fingerprints", type: "list", required: true }
                ]
            }],
            parse(text) {
                const doc = readJson(text, []);
                if (!Array.isArray(doc)) throw new Error("array expected");
                const apps = doc.filter(s => s?.target?.namespace === "android_app").map(s => ({
                    package_name: s.target.package_name ?? "",
                    sha256_cert_fingerprints: [...(s.target.sha256_cert_fingerprints ?? [])]
                }));
                return { values: { apps }, original: doc };
            },
            serialize({ values, original }) {
                const androids = original.filter(s => s?.target?.namespace === "android_app");
                const others = original.filter(s => s?.target?.namespace !== "android_app");
                const apps = values.apps.filter(a => a.package_name !== "").map((a, i) => {
                    const base = clone(androids[i]) ?? { relation: [HANDLE_ALL_URLS], target: { namespace: "android_app" } };
                    base.target = { ...base.target, package_name: a.package_name,
                        sha256_cert_fingerprints: a.sha256_cert_fingerprints.filter(x => x !== "") };
                    return base;
                });
                return JSON.stringify([...others, ...apps], null, 2);
            }
        },
        "apple-app-site-association": {
            id: "apple-app-site-association",
            format: "json",
            fields: [
                {
                    key: "details", label: "applinks", type: "group", fields: [
                        { key: "appIDs", label: "appIDs", type: "list", required: true },
                        { key: "paths", label: "paths", type: "list" }
                    ]
                },
                { key: "webcredentials", label: "webcredentials", type: "list" }
            ],
            parse(text) {
                const doc = readJson(text, {});
                if (!isObject(doc)) throw new Error("object expected");
                const details = (doc.applinks?.details ?? []).map(d => ({
                    appIDs: [...(d.appIDs ?? (d.appID ? [d.appID] : []))],
                    paths: (d.components ?? []).filter(c => typeof c?.["/"] === "string").map(c => c["/"])
                }));
                return { values: { details, webcredentials: [...(doc.webcredentials?.apps ?? [])] }, original: doc };
            },
            serialize({ values, original }) {
                const doc = clone(original);
                const before = doc.applinks?.details ?? [];
                const details = values.details.filter(d => d.appIDs.some(x => x !== "")).map((d, i) => {
                    const base = clone(before[i]) ?? {};
                    const paths = d.paths.filter(x => x !== "");
                    // existing path components keep their other keys, in place; new ones are appended
                    const components = [];
                    let p = 0;
                    for (const c of base.components ?? []) {
                        if (typeof c?.["/"] === "string") {
                            if (p < paths.length) components.push({ ...c, "/": paths[p++] });
                        } else components.push(c);
                    }
                    for (; p < paths.length; p++) components.push({ "/": paths[p] });
                    const out = { ...base, appIDs: d.appIDs.filter(x => x !== "") };
                    delete out.appID;
                    if (components.length) out.components = components; else delete out.components;
                    return out;
                });
                if (details.length || doc.applinks) doc.applinks = { ...(doc.applinks ?? {}), details };
                const creds = values.webcredentials.filter(x => x !== "");
                if (creds.length) doc.webcredentials = { ...(doc.webcredentials ?? {}), apps: creds };
                else if (doc.webcredentials) delete doc.webcredentials.apps;
                return JSON.stringify(doc, null, 2);
            }
        }
    };

    function forPath(location, path) {
        if (location !== "WELL_KNOWN") return null;
        return DEDICATED[path] ?? null;
    }

    function fieldOf(key, schema, required) {
        const base = { key, label: schema.title || key, help: schema.description, required };
        if (Array.isArray(schema.enum) && schema.enum.every(v => typeof v === "string")) {
            return { ...base, type: "enum", options: schema.enum };
        }
        switch (schema.type) {
            case "string": return { ...base, type: "text" };
            case "number": case "integer": return { ...base, type: "number" };
            case "boolean": return { ...base, type: "boolean" };
            case "array":
                return ["string", "number", "integer"].includes(schema.items?.type) ? { ...base, type: "list" } : null;
            default: return null;
        }
    }

    function fromSchema(schema) {
        if (!isObject(schema) || schema.type !== "object" || !isObject(schema.properties)) return null;
        const required = new Set(schema.required ?? []);
        const fields = [];
        for (const [key, property] of Object.entries(schema.properties)) {
            const field = isObject(property) ? fieldOf(key, property, required.has(key)) : null;
            if (!field) return null;
            fields.push(field);
        }
        if (!fields.length) return null;
        return {
            id: "schema",
            format: "json",
            fields,
            parse(text) {
                const doc = readJson(text, {});
                if (!isObject(doc)) throw new Error("object expected");
                const values = {};
                for (const f of fields) {
                    const v = doc[f.key];
                    values[f.key] = f.type === "list" ? (Array.isArray(v) ? v.map(String) : [])
                        : f.type === "boolean" ? v === true
                        : v === undefined ? "" : v;
                }
                return { values, original: doc };
            },
            serialize({ values, original }) {
                const doc = clone(original);
                for (const f of fields) {
                    const v = values[f.key];
                    if (f.type === "list") {
                        const items = v.filter(x => x !== "");
                        if (items.length) doc[f.key] = items; else delete doc[f.key];
                    } else if (f.type === "number") {
                        if (v === "" || v === null || Number.isNaN(Number(v))) delete doc[f.key]; else doc[f.key] = Number(v);
                    } else if (f.type === "boolean") {
                        doc[f.key] = v === true;
                    } else if (v === "" || v === undefined) {
                        delete doc[f.key];
                    } else {
                        doc[f.key] = v;
                    }
                }
                return JSON.stringify(doc, null, 2);
            }
        };
    }

    const parse = (descriptor, text) => descriptor.parse(text ?? "");
    const serialize = (descriptor, model) => descriptor.serialize(model);

    const TYPE_OF = { string: "string", number: "number", integer: "number", boolean: "boolean", array: "array", object: "object" };

    function checkSchema(schema, text) {
        let doc;
        try {
            doc = JSON.parse(text);
        } catch {
            return [];
        }
        if (!isObject(schema) || !isObject(doc)) return [];
        const problems = [];
        for (const key of schema.required ?? []) {
            if (!(key in doc)) problems.push(`${key}: required`);
        }
        for (const [key, property] of Object.entries(schema.properties ?? {})) {
            if (!(key in doc) || !isObject(property) || !TYPE_OF[property.type]) continue;
            const value = doc[key];
            const actual = Array.isArray(value) ? "array" : value === null ? "null" : typeof value;
            if (actual !== TYPE_OF[property.type]) problems.push(`${key}: ${TYPE_OF[property.type]} expected`);
        }
        return problems;
    }

    function expiresFar(model, now = new Date()) {
        const expires = Date.parse(model.values.Expires ?? "");
        if (Number.isNaN(expires)) return false;
        return expires - now.getTime() > 366 * 24 * 3600 * 1000;
    }

    return { forPath, fromSchema, parse, serialize, checkSchema, expiresFar };
})();
