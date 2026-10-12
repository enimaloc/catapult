// catapult-web/src/main/resources/static/js/twitchat-events.js
/**
 * The Twitchat card's event editor (TwitchatEventForms): per Catapult event, its payloads, each
 * a Twitchat action edited through its form (data-field paths into its data) or as raw JSON.
 * Saving posts an event's whole ordered list; testing sends it as it stands, saved or not. New
 * payloads, and going back from raw JSON to a form, come rendered by the server
 * (/channel/{username}/twitchat/payload-form), so no form is ever built here.
 */
window.CatapultTwitchatEvents = (function () {
    const ACTION_URL = (type) => `{{baseUrl}}/widget/twitchat/action/{{action:${type}}}`;

    function setPath(target, path, value) {
        const steps = path.split(".");
        let current = target;
        for (const step of steps.slice(0, -1)) {
            current[step] ??= {};
            current = current[step];
        }
        current[steps.at(-1)] = value;
    }

    /** What an element holds, typed by its kind; undefined when empty (left out of the data). */
    function fieldValue(el) {
        if (el.tagName === "MDUI-SWITCH") return Boolean(el.checked);
        const raw = el.value ?? el.getAttribute("value") ?? "";
        if (raw === "") return undefined;
        if (el.classList.contains("json-field")) return JSON.parse(raw);
        if (el.getAttribute("type") === "number") return Number(raw);
        return raw;
    }

    /**
     * The data under `root`: each data-field set at its path, a repeater's rows as an array.
     * A field its data-show-if hides (a button's URL while it runs a trigger) is left out.
     */
    function formData(root) {
        const data = {};
        for (const el of root.querySelectorAll("[data-field]")) {
            if (el.closest("template")) continue;
            const hidden = el.closest(".hidden");
            if (hidden && hidden !== root && root.contains(hidden)) continue;
            const repeater = el.parentElement.closest("[data-repeater]");
            if (repeater && root.contains(repeater)) continue; // a row's field, read with its row
            if (el.hasAttribute("data-repeater")) {
                const rows = [...el.querySelector(".repeater-rows").children].filter((row) => row.classList.contains("repeater-row"));
                setPath(data, el.dataset.field, rows.map(formData));
            } else {
                const value = fieldValue(el);
                if (value !== undefined) setPath(data, el.dataset.field, value);
            }
        }
        return data;
    }

    const part = (card, name) => card.querySelector(`.twitchat-payload-${name}`);

    function payloadOf(card) {
        const mode = part(card, "mode").value;
        return {
            action: part(card, "action").value,
            mode,
            dataJson: mode === "RAW" ? part(card, "raw-json").value : JSON.stringify(formData(part(card, "form"))),
            enabled: Boolean(part(card, "enabled").checked),
        };
    }

    const cardsOf = (event) => [...event.querySelectorAll(".twitchat-payload")];
    const payloadsOf = (event) => cardsOf(event).map(payloadOf);
    const eventTypeOf = (event) => event.querySelector(".twitchat-event-type").value;
    const url = (path) => `${CatapultChannel.baseUrl()}/twitchat/${path}`;

    /** Errors on the cards they concern: an error's index is its card's among those posted. */
    function showErrors(event, cards, errors) {
        cards.forEach((card) => { part(card, "errors").textContent = ""; });
        const general = [];
        for (const { index, field, message } of errors) {
            const card = cards[index];
            if (card) part(card, "errors").textContent = `${field}: ${message}`;
            else general.push(message);
        }
        event.querySelector(".twitchat-event-errors").textContent = general.join(" ");
    }

    /** Posts these cards' payloads; shows the errors a 400 lists. Resolves with whether it went through. */
    async function send(event, path, cards) {
        event.querySelector(".twitchat-event-errors").textContent = "";
        let body;
        try {
            body = cards.map(payloadOf);
        } catch (error) {
            event.querySelector(".twitchat-event-errors").textContent = error.message;
            return false;
        }
        let response;
        try {
            response = await CatapultCsrf.postJson(url(path), body);
        } catch (error) {
            event.querySelector(".twitchat-event-errors").textContent = `Request failed: ${error.message}`;
            return false;
        }
        if (response.status === 400) {
            showErrors(event, cards, await response.json());
            return false;
        }
        if (!response.ok) {
            CatapultChannel.showError(`Action failed (${response.status})`);
            return false;
        }
        showErrors(event, cards, []);
        return true;
    }

    async function cardFromServer(event, payload) {
        let response;
        try {
            response = await CatapultCsrf.postJson(url(`payload-form?event=${eventTypeOf(event)}`), payload);
        } catch (error) {
            CatapultChannel.showError(`Request failed: ${error.message}`);
            return {};
        }
        if (response.status === 422) return { errors: await response.json() };
        if (!response.ok) {
            CatapultChannel.showError(`Action failed (${response.status})`);
            return {};
        }
        const template = document.createElement("template");
        template.innerHTML = (await response.text()).trim();
        const card = template.content.querySelector(".twitchat-payload");
        wireCard(card);
        return { card };
    }

    async function toggleRaw(card) {
        const event = card.closest(".twitchat-event");
        if (part(card, "mode").value === "FORM") {
            try {
                part(card, "raw-json").value = JSON.stringify(formData(part(card, "form")), null, 2);
            } catch (error) {
                part(card, "errors").textContent = error.message;
                return;
            }
            part(card, "errors").textContent = "";
            part(card, "mode").value = "RAW";
            part(card, "form").classList.add("hidden");
            part(card, "raw").classList.remove("hidden");
            return;
        }
        const { card: rendered, errors } = await cardFromServer(event, { ...payloadOf(card), mode: "FORM" });
        if (errors) {
            part(card, "errors").textContent = errors.map(({ field, message }) => `${field}: ${message}`).join(", ");
            return;
        }
        if (rendered) {
            card.replaceWith(rendered);
            markAvailability(event);
        }
    }

    function move(card, offset) {
        const sibling = offset < 0 ? card.previousElementSibling : card.nextElementSibling;
        if (!sibling?.classList.contains("twitchat-payload")) return;
        if (offset < 0) sibling.before(card);
        else sibling.after(card);
    }

    /** The row (or form) a field belongs to: where its data-show-if looks up its sibling. */
    const scopeOf = (el) => el.closest(".repeater-row, .twitchat-payload-form");

    /** Shows each field under `root` whose data-show-if sibling holds one of its values. */
    function applyConditions(root) {
        for (const el of root.querySelectorAll("[data-show-if]")) {
            const { path, values } = JSON.parse(el.dataset.showIf);
            const scope = scopeOf(el);
            const sibling = [...(scope?.querySelectorAll(`[data-field="${path}"]`) ?? [])].find((field) => scopeOf(field) === scope);
            el.classList.toggle("hidden", !values.includes(sibling?.value ?? ""));
        }
    }

    function pointAtCatapultAction(select) {
        const row = select.closest(".repeater-row");
        if (!row || !select.value) return;
        const set = (field, value) => {
            const input = row.querySelector(`[data-field="${field}"]`);
            if (input) input.value = value;
        };
        set("actionType", "url");
        set("url", ACTION_URL(select.value));
        applyConditions(row);
    }

    /** The option list a live select's data answer holds: its first array, by id and name. */
    function liveOptions(data) {
        const list = Object.values(data ?? {}).find(Array.isArray) ?? [];
        return list.filter((item) => item?.id != null).map((item) => ({ value: String(item.id), label: item.name ?? item.label ?? String(item.id) }));
    }

    async function fillLiveSelect(select) {
        const twitchat = window.CatapultTwitchat;
        if (!twitchat?.isTwitchatConnected()) return;
        const source = JSON.parse(select.dataset.source);
        let action;
        try {
            const protocol = twitchat.protocol ?? await twitchat.detectProtocol() ?? "stable";
            action = source[protocol];
            if (!action) return;
            const options = liveOptions(await twitchat.send(action, {}));
            const known = new Set([...select.querySelectorAll("mdui-menu-item")].map((item) => item.getAttribute("value")));
            for (const { value, label } of options) {
                if (known.has(value)) continue;
                const item = document.createElement("mdui-menu-item");
                item.setAttribute("value", value);
                item.textContent = label;
                select.appendChild(item);
            }
        } catch (error) {
            console.warn(`[twitchat] ${action} failed`, error);
        }
    }

    /**
     * Flags the payloads Twitchat's protocol hasn't: the detected one, else the branch picked
     * (forced, connected or not). On auto, nothing is flagged until a Twitchat answered.
     */
    function markAvailability(root) {
        const twitchat = window.CatapultTwitchat;
        const known = twitchat?.protocol != null;
        for (const card of root.querySelectorAll(".twitchat-payload")) {
            const flag = part(card, "unavailable");
            if (!flag) continue;
            const action = part(card, "action")?.value;
            flag.classList.toggle("hidden", !known || twitchat.actionName(action) != null);
        }
    }

    function wireCard(card) {
        const on = (name, handler) => part(card, name)?.addEventListener("click", () => handler(card));
        on("up", (c) => move(c, -1));
        on("down", (c) => move(c, 1));
        on("delete", (c) => c.remove());
        on("raw-btn", toggleRaw);
        on("test", (c) => send(c.closest(".twitchat-event"), `events/${eventTypeOf(c.closest(".twitchat-event"))}/test`, [c]));
        card.addEventListener("click", (event) => {
            const button = event.target.closest(".repeater-add, .repeater-remove");
            if (!button) return;
            if (button.classList.contains("repeater-remove")) {
                button.closest(".repeater-row").remove();
                return;
            }
            const repeater = button.closest("[data-repeater]");
            const row = repeater.querySelector("template").content.firstElementChild.cloneNode(true);
            repeater.querySelector(".repeater-rows").appendChild(row);
            row.querySelectorAll("[data-source]").forEach(fillLiveSelect);
        });
        card.addEventListener("change", (event) => {
            const target = event.target;
            if (target.classList?.contains("twitchat-catapult-action")) {
                pointAtCatapultAction(target);
                return;
            }
            // A button no longer opening a link no longer runs a Catapult action either.
            const row = target.closest?.(".repeater-row");
            if (row && target.dataset?.field === "actionType" && target.value !== "url") {
                const action = row.querySelector(".twitchat-catapult-action");
                if (action) action.value = "";
            }
            const scope = target.closest?.(".repeater-row, .twitchat-payload-form");
            if (scope) applyConditions(scope);
        });
        card.querySelectorAll("[data-source]").forEach(fillLiveSelect);
    }

    function wireEvent(event) {
        const type = () => eventTypeOf(event);
        cardsOf(event).forEach(wireCard);
        event.querySelector(".twitchat-save-btn")?.addEventListener("click", () =>
            send(event, `events/${type()}`, cardsOf(event)));
        event.querySelector(".twitchat-test-all-btn")?.addEventListener("click", () =>
            send(event, `events/${type()}/test`, cardsOf(event)));
        event.querySelector(".twitchat-add-action")?.addEventListener("change", async (change) => {
            const action = change.target.value;
            change.target.value = ""; // so picking the same action again fires another change
            if (!action) return;
            const { card } = await cardFromServer(event, { action, mode: "FORM", dataJson: null, enabled: true });
            if (card) {
                event.querySelector(".twitchat-payloads").appendChild(card);
                markAvailability(event);
            }
        });
    }

    document.addEventListener("catapult:render", () => {
        const root = document.getElementById("channel-connections");
        if (!root) return;
        root.querySelectorAll(".twitchat-event").forEach(wireEvent);
        // Payloads outside an event block (a lone card) still get their live selects.
        root.querySelectorAll(".twitchat-payload").forEach((card) => {
            if (!card.closest(".twitchat-event")) card.querySelectorAll("[data-source]").forEach(fillLiveSelect);
        });
        markAvailability(root);
    });

    document.addEventListener("catapult:twitchat:presence", () => {
        const root = document.getElementById("channel-connections");
        if (!root) return;
        markAvailability(root);
        if (window.CatapultTwitchat?.isTwitchatConnected()) root.querySelectorAll("[data-source]").forEach(fillLiveSelect);
    });

    document.addEventListener("catapult:twitchat:protocol", () => {
        const root = document.getElementById("channel-connections");
        if (root) markAvailability(root);
    });

    return { formData, payloadOf, payloadsOf };
})();
