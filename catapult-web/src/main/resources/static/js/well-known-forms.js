/**
 * The well-known dialog's forms, without the DOM (admin-well-known.js renders them): a descriptor
 * per known path (dedicated) or built from a flat SchemaStore schema (generated), reading raw text
 * into form values and writing them back. What the form doesn't show is kept: JSON values are merged
 * into the parsed original (unknown keys and array entries stay), security.txt's comments and unknown
 * lines follow the known fields in their original order.
 *
 * Apple app-site details are order-sensitive (first match wins): each pure detail shown in the form
 * carries `_origin`, its slot in the original array, and keeps that slot when written back (deleting
 * one never moves another; reordering is not supported). UI code must keep `_origin` on the group items
 * it reads back; items without it are appended.
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
    const isObject = value => value !== null && typeof value === "object" && !Array.isArray(value);
    const isStrings = value => Array.isArray(value) && value.every(x => typeof x === "string");
    const sameKeys = (o, keys) => Object.keys(o).length === keys.length && keys.every(k => k in o);

    function readJson(text, fallback) {
        if (text.trim() === "") return clone(fallback);
        try {
            return JSON.parse(text);
        } catch (e) {
            throw new Error(e.message);
        }
    }

    /**
     * Writes the form's values back into an original array: the entries the form fully represents ("pure")
     * are replaced in order by the next value, the others stay untouched in place, surplus values are
     * appended and pure entries beyond the values are dropped (deleted by the user).
     */
    function mergeInPlace(original, isPure, values, build) {
        const out = [];
        let i = 0;
        for (const entry of original) {
            if (!isPure(entry)) out.push(entry);
            else if (i < values.length) out.push(build(values[i++], entry));
        }
        for (; i < values.length; i++) out.push(build(values[i], undefined));
        return out;
    }

    const isMsEntry = a => isObject(a) && sameKeys(a, ["applicationId"]) && typeof a.applicationId === "string" && a.applicationId !== "";

    const isAndroid = s => isObject(s) && sameKeys(s, ["relation", "target"])
        && Array.isArray(s.relation) && s.relation.length === 1 && s.relation[0] === HANDLE_ALL_URLS
        && isObject(s.target) && sameKeys(s.target, ["namespace", "package_name", "sha256_cert_fingerprints"])
        && s.target.namespace === "android_app" && typeof s.target.package_name === "string"
        && s.target.package_name !== "" && isStrings(s.target.sha256_cert_fingerprints)
        && s.target.sha256_cert_fingerprints.every(f => f !== "");

    const isPath = c => isObject(c) && sameKeys(c, ["/"]) && typeof c["/"] === "string" && c["/"] !== "";

    const isDetail = d => isObject(d) && Object.keys(d).every(k => k === "appIDs" || k === "components")
        && isStrings(d.appIDs) && d.appIDs.length > 0 && d.appIDs.every(x => x !== "")
        && (d.components === undefined || (Array.isArray(d.components) && d.components.every(isPath)));

    const DEDICATED = {
        "security.txt": {
            id: "security.txt",
            format: "securitytxt",
            fields: SECURITY_FIELDS,
            parse(text) {
                if (text.includes("-----BEGIN PGP SIGNED MESSAGE-----")) {
                    throw new Error("signed security.txt: edit it as raw text");
                }
                const values = {};
                const rest = [];
                SECURITY_FIELDS.forEach(f => { values[f.key] = f.type === "list" ? [] : ""; });
                const seen = new Set();
                for (const line of text.split(/\r?\n/)) {
                    const match = /^([A-Za-z-]+):\s*(.*)$/.exec(line);
                    const field = match && SECURITY_FIELDS.find(f => f.key.toLowerCase() === match[1].toLowerCase());
                    if (!field || match[2].trim() === "" || (field.type !== "list" && seen.has(field.key))) {
                        if (line.trim() !== "") rest.push(line);
                        continue;
                    }
                    if (field.type === "list") values[field.key].push(match[2]);
                    else {
                        values[field.key] = match[2];
                        seen.add(field.key);
                    }
                }
                return { values, original: { lines: rest, crlf: text.includes("\r\n") } };
            },
            serialize({ values, original }) {
                const lines = [];
                for (const f of SECURITY_FIELDS) {
                    const v = values[f.key];
                    if (f.type === "list") (v || []).filter(x => x !== "").forEach(x => lines.push(`${f.key}: ${x}`));
                    else if (v) lines.push(`${f.key}: ${v}`);
                }
                return [...lines, ...original.lines, ""].join(original.crlf ? "\r\n" : "\n");
            }
        },
        "microsoft-identity-association.json": {
            id: "microsoft-identity-association.json",
            format: "json",
            fields: [{ key: "applicationIds", label: "applicationId", type: "list", required: true }],
            parse(text) {
                const doc = readJson(text, { associatedApplications: [] });
                if (!isObject(doc)) throw new Error("object expected");
                const apps = doc.associatedApplications ?? [];
                if (!Array.isArray(apps)) throw new Error("associatedApplications: array expected");
                return { values: { applicationIds: apps.filter(isMsEntry).map(a => a.applicationId) }, original: doc };
            },
            serialize({ values, original }) {
                const doc = clone(original);
                doc.associatedApplications = mergeInPlace(doc.associatedApplications ?? [], isMsEntry,
                    values.applicationIds.filter(x => x !== ""), id => ({ applicationId: id }));
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
                const apps = doc.filter(isAndroid).map(s => ({
                    package_name: s.target.package_name,
                    sha256_cert_fingerprints: [...s.target.sha256_cert_fingerprints]
                }));
                return { values: { apps }, original: doc };
            },
            serialize({ values, original }) {
                const apps = values.apps.filter(a => a.package_name !== "");
                return JSON.stringify(mergeInPlace(original, isAndroid, apps, a => ({
                    relation: [HANDLE_ALL_URLS],
                    target: {
                        namespace: "android_app", package_name: a.package_name,
                        sha256_cert_fingerprints: a.sha256_cert_fingerprints.filter(x => x !== "")
                    }
                })), null, 2);
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
                if (doc.applinks !== undefined && !isObject(doc.applinks)) throw new Error("applinks: object expected");
                const rawDetails = doc.applinks?.details ?? [];
                if (!Array.isArray(rawDetails)) throw new Error("applinks.details: array expected");
                if (doc.webcredentials !== undefined && !isObject(doc.webcredentials)) {
                    throw new Error("webcredentials: object expected");
                }
                if (doc.webcredentials?.apps !== undefined && !isStrings(doc.webcredentials.apps)) {
                    throw new Error("webcredentials.apps: array expected");
                }
                // _origin = the detail's slot in the original array; the UI must keep it on the items it reads back
                const details = [];
                rawDetails.forEach((d, i) => {
                    if (isDetail(d)) details.push({ appIDs: [...d.appIDs], paths: (d.components ?? []).map(c => c["/"]), _origin: i });
                });
                return { values: { details, webcredentials: [...(doc.webcredentials?.apps ?? [])] }, original: doc };
            },
            serialize({ values, original }) {
                const doc = clone(original);
                const rows = values.details.map(d => ({
                    appIDs: d.appIDs.filter(x => x !== ""), paths: d.paths.filter(x => x !== ""), _origin: d._origin
                })).filter(d => d.appIDs.length || d.paths.length);
                const build = row => {
                    const out = { appIDs: row.appIDs };
                    if (row.paths.length) out.components = row.paths.map(p => ({ "/": p }));
                    return out;
                };
                const claims = new Map();
                const added = [];
                for (const row of rows) {
                    if (Number.isInteger(row._origin) && !claims.has(row._origin)) claims.set(row._origin, row);
                    else added.push(row);
                }
                const details = [];
                (doc.applinks?.details ?? []).forEach((d, i) => {
                    if (!isDetail(d)) details.push(d);
                    else if (claims.has(i)) details.push(build(claims.get(i)));
                });
                added.forEach(row => details.push(build(row)));
                if (details.length || doc.applinks) doc.applinks = { ...(doc.applinks ?? {}), details };
                const creds = values.webcredentials.filter(x => x !== "");
                if (creds.length) doc.webcredentials = { ...(doc.webcredentials ?? {}), apps: creds };
                else if (doc.webcredentials?.apps?.length) {
                    delete doc.webcredentials.apps;
                    if (!Object.keys(doc.webcredentials).length) delete doc.webcredentials;
                }
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
            case "array": {
                const item = schema.items?.type;
                return ["string", "number", "integer"].includes(item)
                    ? { ...base, type: "list", itemType: item === "string" ? "string" : "number" } : null;
            }
            default: return null;
        }
    }

    /** The human message when a present value doesn't fit its field, or null. */
    function misfit(f, v) {
        const expected = {
            text: "string", number: "number", boolean: "boolean", list: "array",
            enum: `one of ${(f.options || []).join(", ")}`
        }[f.type];
        const ok = f.type === "text" ? typeof v === "string"
            : f.type === "number" ? typeof v === "number"
            : f.type === "boolean" ? typeof v === "boolean"
            : f.type === "enum" ? f.options.includes(v)
            : Array.isArray(v) && v.every(x => typeof x === (f.itemType === "number" ? "number" : "string"));
        return ok ? null : `${f.key}: ${expected} expected`;
    }

    const RESERVED = new Set(["__proto__", "constructor", "prototype"]);

    function fromSchema(schema) {
        if (!isObject(schema) || schema.type !== "object" || !isObject(schema.properties)) return null;
        const required = new Set(schema.required ?? []);
        const fields = [];
        for (const [key, property] of Object.entries(schema.properties)) {
            if (RESERVED.has(key)) continue; // never a form field: kept as an unknown key
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
                    if (v !== undefined) {
                        const problem = misfit(f, v);
                        if (problem) throw new Error(problem);
                    }
                    values[f.key] = f.type === "list" ? (v === undefined ? [] : v.map(String))
                        : f.type === "boolean" ? v === true
                        : v === undefined ? "" : v;
                }
                return { values, original: doc, initial: clone(values) };
            },
            serialize({ values, original, initial }) {
                const doc = clone(original);
                for (const f of fields) {
                    const v = values[f.key];
                    if (initial && JSON.stringify(v) === JSON.stringify(initial[f.key])) continue;
                    if (f.type === "list") {
                        const items = v.filter(x => (f.itemType === "number" ? String(x).trim() : x) !== "")
                            .map(x => (f.itemType === "number" && String(x).trim() !== "" && !Number.isNaN(Number(x)) ? Number(x) : x));
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

    function parse(descriptor, text) {
        try {
            return descriptor.parse(text ?? "");
        } catch (e) {
            if (e instanceof TypeError) throw new Error(`unreadable content: ${e.message}`);
            throw e;
        }
    }
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
