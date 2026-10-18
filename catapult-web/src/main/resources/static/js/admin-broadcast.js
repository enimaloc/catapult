/**
 * The admin Broadcast tab. The select shows the form of the broadcast type picked (the others stay
 * hidden); Send checks the fields as catapult-api does, posts the type's JSON to
 * AdminBroadcastController and tells the outcome by its status (202 sent, 400 data-label-invalid,
 * 429 data-label-rate, else data-label-unavailable). "Cancel it" on the announced maintenance
 * selects the cancel form with that maintenance's start filled in. Listeners are delegated to the
 * panel: its content comes and goes.
 */
window.CatapultAdminBroadcast = (function () {
    const fieldOf = (form, name) => form.querySelector(`[name="${name}"]`);
    const textOf = (form, name) => (fieldOf(form, name).value ?? "").trim();

    function mark(field) {
        field.setAttribute("invalid", "");
        field.reportValidity?.();
    }

    /** `datetime-local`'s value (the browser's zone) as an ISO instant, or null when empty / invalid. */
    function isoOf(local) {
        if (local === "") return null;
        const date = new Date(local);
        return Number.isNaN(date.getTime()) ? null : date.toISOString();
    }

    /** An ISO instant as a `datetime-local` value in the browser's zone, seconds included. */
    function localOf(iso) {
        const date = new Date(iso);
        if (Number.isNaN(date.getTime())) return "";
        const pad = n => String(n).padStart(2, "0");
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
            + `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
    }

    function select(root, name) {
        root.querySelectorAll("form[data-broadcast]").forEach(form => { form.hidden = form.dataset.broadcast !== name; });
        const type = root.querySelector('[name="type"]');
        if (type && type.value !== name) type.value = name;
    }

    /** The JSON body of the form, or null when a required field is refused (the field is marked). */
    function bodyOf(form) {
        const name = form.dataset.broadcast;
        const refuse = (...fields) => { fields.forEach(mark); return null; };
        const required = (...names) => names.filter(n => textOf(form, n) === "").map(n => fieldOf(form, n));
        const number = (field, min) => {
            const raw = textOf(form, field);
            const value = Number(raw);
            return raw !== "" && Number.isInteger(value) && value >= min ? value : null;
        };
        if (name === "maintenance.scheduled") {
            const startsAt = isoOf(textOf(form, "startsAt"));
            const durationMinutes = number("durationMinutes", 1);
            const missing = required("message");
            if (startsAt === null) missing.push(fieldOf(form, "startsAt"));
            if (durationMinutes === null) missing.push(fieldOf(form, "durationMinutes"));
            if (missing.length) return refuse(...missing);
            return { name, startsAt, durationMinutes, message: textOf(form, "message") };
        }
        if (name === "maintenance.cancelled") {
            const field = fieldOf(form, "originalStartsAt");
            // the instant the announcement came with, unless the field was edited since
            const exact = form.dataset.originalIso && form.dataset.originalLocal === field.value ? form.dataset.originalIso : null;
            const originalStartsAt = exact ?? isoOf(textOf(form, "originalStartsAt"));
            const missing = required("reason");
            if (originalStartsAt === null) missing.push(field);
            if (missing.length) return refuse(...missing);
            return { name, originalStartsAt, reason: textOf(form, "reason") };
        }
        if (name === "version.deployed") {
            const missing = required("version");
            if (missing.length) return refuse(...missing);
            return { name, version: textOf(form, "version"), promptReload: fieldOf(form, "promptReload").checked === true };
        }
        const ttlSeconds = number("ttlSeconds", 0);
        const missing = required("title", "body");
        if (ttlSeconds === null) missing.push(fieldOf(form, "ttlSeconds"));
        if (missing.length) return refuse(...missing);
        return { name, title: textOf(form, "title"), body: fieldOf(form, "body").value, ttlSeconds };
    }

    function reset(form) {
        form.querySelectorAll("[name]").forEach(field => {
            field.removeAttribute("invalid");
            if (field.matches('[name="promptReload"]')) field.checked = false;
            else field.value = field.matches('[name="ttlSeconds"]') ? "0" : "";
        });
        delete form.dataset.originalIso;
        delete form.dataset.originalLocal;
    }

    const labelOf = status => ({ 400: "labelInvalid", 429: "labelRate" })[status] ?? "labelUnavailable";

    async function send(root, form, button) {
        const body = bodyOf(form);
        if (body === null) return;
        button.disabled = true; // synchronously: a second click finds it disabled
        let status = 0;
        try {
            status = (await CatapultCsrf.postJson("/admin/broadcast", body)).status;
        } catch {
            // not sent: told below
        } finally {
            button.disabled = false;
        }
        if (status === 202) {
            reset(form);
            window.mdui?.snackbar?.({ message: root.dataset.labelSent });
        } else {
            CatapultChannel.showError(root.dataset[labelOf(status)]);
        }
    }

    function cancelCurrent(root, card) {
        const form = root.querySelector('form[data-broadcast="maintenance.cancelled"]');
        const iso = card.dataset.currentStartsAt;
        const field = fieldOf(form, "originalStartsAt");
        field.value = localOf(iso);
        form.dataset.originalIso = iso;
        form.dataset.originalLocal = field.value;
        select(root, "maintenance.cancelled");
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="broadcast"]');
        if (!panel) return;
        const rootOf = () => panel.querySelector(".admin-broadcast");

        panel.addEventListener("click", event => {
            const target = event.target.closest(".admin-broadcast-send, #admin-broadcast-cancel-current");
            const root = rootOf();
            if (!target || target.disabled || !root) return;
            if (target.matches("#admin-broadcast-cancel-current")) {
                cancelCurrent(root, target.closest("[data-current-starts-at]"));
            } else {
                send(root, target.closest("form[data-broadcast]"), target);
            }
        });
        panel.addEventListener("input", event => event.target.removeAttribute?.("invalid"));
        panel.addEventListener("change", event => {
            const root = rootOf();
            if (root && event.target.matches?.('[name="type"]')) select(root, event.target.value);
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
