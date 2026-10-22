/**
 * The admin Experiments detail view. Status actions, weights, rules and overrides post to
 * AdminExperimentsController with their button disabled meanwhile; a success reloads the view, a
 * refusal is told by its status (400 invalid, 404 gone and reloads, 409 unavailable and reloads,
 * anything else unavailable). A 404 on an account override means an unknown Twitch login: it is told
 * without reloading, so the form keeps what was typed. End and Reassign, and deleting a rule or an override, confirm first. The rule and override forms
 * hold one block of fields per type/target and show only the chosen one (variantId only for
 * FORCE_VARIANT); weights show their live share of the total, and the weight bar above them (a
 * segment per variant) is dragged or arrowed by the handle between two neighbours, which moves points
 * from one to the other: the view opens on the weights as percentages summing to 100 so the bar and
 * the fields say the same thing. mdui-text-field can't bind a
 * <datalist> (shadow DOM), so the known tags and the chosen tag's values are offered as chips under
 * the tag fields. The Participants card reloads its chips and list from /spa/admin/experiments/{id}/participants
 * on a variant chip, a (debounced) search or a page; moving a member posts the chosen variant, then
 * refreshes the list and the Overrides card (the move adds an override). Listeners are delegated to the document: the view comes and goes.
 */
window.CatapultAdminExperiments = (function () {
    const PANEL = 'mdui-tab-panel[value="experiments"]';
    const ok = status => status >= 200 && status < 300;
    const trimmed = (form, name) => (form.querySelector(`[name="${name}"]`)?.value ?? "").trim();
    const fieldOf = (form, name) => form.querySelector(`[name="${name}"]`);
    const urlOf = (root, path) => `/admin/experiments/${encodeURIComponent(root.dataset.experiment)}/${path}`;

    function mark(field) {
        field.setAttribute("invalid", "");
        field.reportValidity?.();
    }

    /** Marks the fields of `names` that are blank; true when one was. */
    function missing(form, names) {
        const blank = names.filter(name => trimmed(form, name) === "");
        blank.forEach(name => mark(fieldOf(form, name)));
        return blank.length > 0;
    }

    /** The status of a POST (0 when it couldn't be sent), `button` disabled meanwhile. */
    async function post(button, url, body) {
        if (button) button.disabled = true;
        try {
            return (await CatapultCsrf.postJson(url, body)).status;
        } catch {
            return 0;
        } finally {
            if (button) button.disabled = false;
        }
    }

    /** What to tell for a refused status; `notFound` names what a 404 means for the action. */
    function refusal(root, status, notFound) {
        if (status === 404) return root.dataset[notFound];
        return status === 400 ? root.dataset.labelInvalid : root.dataset.labelUnavailable;
    }

    /**
     * Tells the outcome of `status`: done (snackbar, reload) or refused. A vanished (404) or
     * since-changed (409) experiment also reloads; an unknown account keeps the form as typed.
     */
    async function settle(panel, root, status, notFound) {
        if (ok(status)) {
            window.mdui?.snackbar?.({ message: root.dataset.labelSaved });
            await CatapultAdminTabs.reload(panel);
            return;
        }
        CatapultChannel.showError(refusal(root, status, notFound));
        if ((status === 404 && notFound === "labelUnknown") || status === 409) await CatapultAdminTabs.reload(panel);
    }

    /** Posts and settles; `button` stays busy until the view reloads. */
    async function send(panel, root, button, url, body, notFound = "labelUnknown") {
        const status = await post(button, url, body);
        if (button) button.disabled = true;
        try {
            await settle(panel, root, status, notFound);
        } finally {
            if (button) button.disabled = false;
        }
    }

    async function act(panel, root, button, confirmKey) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            if (confirmKey && !await CatapultAdminTabs.confirmed(root, root.dataset[confirmKey])) return;
            await send(panel, root, button, urlOf(root, button.dataset.experimentAction), {});
        } finally {
            button.disabled = false;
        }
    }

    async function remove(panel, root, button, path) {
        button.disabled = true;
        try {
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            await send(panel, root, button, urlOf(root, path), {});
        } finally {
            button.disabled = false;
        }
    }

    // ---- weights

    const weightFields = form => Array.from(form.querySelectorAll("[data-variant]"));
    const weightOf = field => {
        const text = (field.value ?? "").trim();
        return /^\d+$/.test(text) ? Number(text) : null;
    };

    function showShares(form) {
        const fields = weightFields(form);
        const total = fields.reduce((sum, field) => sum + (weightOf(field) ?? 0), 0);
        fields.forEach(field => {
            const weight = weightOf(field);
            const share = field.parentElement.querySelector("[data-weight-share]");
            if (share) share.textContent = weight === null || total === 0 ? "" : `${Math.round(weight / total * 100)} %`;
        });
    }

    /** The bar's segments sized by the fields, labelled with their share, and its handles' positions. */
    function showBar(form) {
        const bar = form.querySelector(".experiment-weight-bar");
        if (!bar) return;
        const fields = weightFields(form);
        const weights = fields.map(field => weightOf(field) ?? 0);
        const total = weights.reduce((a, b) => a + b, 0);
        fields.forEach((field, i) => {
            const segment = bar.querySelector(`[data-segment="${CSS.escape(field.dataset.variant)}"]`);
            if (!segment) return;
            segment.style.flexGrow = String(weights[i]);
            const label = segment.querySelector("b");
            if (label) label.textContent = `${total === 0 ? 0 : Math.round(weights[i] / total * 100)} %`;
        });
        bar.querySelectorAll("[data-handle]").forEach(handle => {
            const before = weights.slice(0, Number(handle.dataset.handle)).reduce((a, b) => a + b, 0);
            handle.setAttribute("aria-valuenow", String(total === 0 ? 0 : Math.round(before / total * 100)));
        });
    }

    /**
     * Rewrites the weights as percentages summing to 100 (largest remainders take the rounding's
     * points), so that a handle moves whole percents; all-zero weights stay as they are.
     */
    function asPercentages(form) {
        const fields = weightFields(form);
        const weights = fields.map(field => weightOf(field) ?? 0);
        const total = weights.reduce((a, b) => a + b, 0);
        if (total === 0 || total === 100) return;
        const exact = weights.map(weight => weight * 100 / total);
        const shares = exact.map(Math.floor);
        const order = exact.map((value, i) => [value - shares[i], i]).sort((a, b) => b[0] - a[0] || a[1] - b[1]);
        for (let k = 0; k < 100 - shares.reduce((a, b) => a + b, 0); k++) shares[order[k][1]]++;
        fields.forEach((field, i) => { field.value = String(shares[i]); });
    }

    /** Puts the boundary of `handle` at `percent` of the bar, between its two neighbours only. */
    function moveHandle(form, handle, percent) {
        asPercentages(form);
        const fields = weightFields(form);
        const right = Number(handle.dataset.handle);
        const weights = fields.map(field => weightOf(field) ?? 0);
        const before = weights.slice(0, right - 1).reduce((a, b) => a + b, 0);
        const pair = weights[right - 1] + weights[right];
        const left = Math.min(pair, Math.max(0, Math.round(percent) - before));
        fields[right - 1].value = String(left);
        fields[right].value = String(pair - left);
        sync(form);
    }

    function dragHandle(form, handle, event) {
        const bar = handle.closest(".experiment-weight-bar");
        const follow = moveEvent => {
            const box = bar.getBoundingClientRect();
            if (box.width > 0) moveHandle(form, handle, (moveEvent.clientX - box.left) / box.width * 100);
        };
        const stop = () => {
            bar.classList.remove("is-dragging");
            handle.removeEventListener("pointermove", follow);
            handle.removeEventListener("pointerup", stop);
            handle.removeEventListener("pointercancel", stop);
        };
        event.preventDefault();
        handle.setPointerCapture?.(event.pointerId);
        handle.focus?.();
        bar.classList.add("is-dragging");
        handle.addEventListener("pointermove", follow);
        handle.addEventListener("pointerup", stop);
        handle.addEventListener("pointercancel", stop);
    }

    function nudgeHandle(form, handle, event) {
        const step = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 }[event.key];
        if (!step) return;
        event.preventDefault();
        asPercentages(form);
        moveHandle(form, handle, Number(handle.getAttribute("aria-valuenow") ?? 0) + step * (event.shiftKey ? 10 : 1));
    }

    function saveWeights(panel, root, form) {
        const fields = weightFields(form);
        const bad = fields.filter(field => weightOf(field) === null);
        if (bad.length > 0) return bad.forEach(mark);
        const weights = Object.fromEntries(fields.map(field => [field.dataset.variant, weightOf(field)]));
        return send(panel, root, form.querySelector('[type="submit"]'), urlOf(root, "weights"), weights);
    }

    // ---- rules and overrides

    /** Shows the block of `kind` ("rule"/"override") fields matching `chosen`, hides the others. */
    function showBlocks(form, kind, chosen) {
        form.querySelectorAll(`[data-${kind}-fields]`).forEach(block => {
            block.hidden = block.getAttribute(`data-${kind}-fields`) !== chosen;
        });
    }

    function syncRule(form) {
        showBlocks(form, "rule", trimmed(form, "type"));
    }

    function syncOverride(form) {
        showBlocks(form, "override", trimmed(form, "target"));
        const variant = fieldOf(form, "variantId");
        if (variant) variant.hidden = trimmed(form, "action") !== "FORCE_VARIANT";
        suggest(form);
    }

    /** The rule to post, or null once its blank required fields are marked. */
    function ruleBody(form) {
        const type = trimmed(form, "type");
        switch (type) {
            case "RANDOM": {
                const percentage = Number(trimmed(form, "percentage"));
                if (trimmed(form, "percentage") === "" || !(percentage >= 0 && percentage <= 100)) return mark(fieldOf(form, "percentage"));
                return { type, percentage };
            }
            case "GROUP":
                return missing(form, ["groupKey"]) ? null : { type, groupKey: trimmed(form, "groupKey") };
            case "ATTRIBUTE": {
                const operator = trimmed(form, "attributeOperator");
                const value = trimmed(form, "attributeValue");
                if (missing(form, operator === "exists" ? ["attributeKey"] : ["attributeKey", "attributeValue"])) return null;
                // The form targets a tag, stored under the "flag:" attribute key
                return { type, attributeKey: `flag:${trimmed(form, "attributeKey")}`, attributeOperator: operator,
                    attributeValue: operator === "exists" ? null : value };
            }
            case "EXPERIMENT":
                return missing(form, ["experimentKey", "variantKey"]) ? null
                    : { type, experimentKey: trimmed(form, "experimentKey"), variantKey: trimmed(form, "variantKey") };
            default:
                return { type };
        }
    }

    const OVERRIDE_TARGETS = { USER: "account", TAG: "tag", GROUP: "group" };

    /** The override to post, or null once its blank required fields are marked. */
    function overrideBody(form) {
        const target = trimmed(form, "target");
        const action = trimmed(form, "action");
        const body = { target: OVERRIDE_TARGETS[target] ?? target };
        if (target === "USER") {
            if (missing(form, ["twitchUsername"])) return null;
            body.twitchUsername = trimmed(form, "twitchUsername");
        } else if (target === "TAG") {
            if (missing(form, ["tagKey"])) return null;
            body.tagKey = trimmed(form, "tagKey");
            body.tagValue = trimmed(form, "tagValue") || null;
        } else if (target === "GROUP") {
            if (missing(form, ["groupKey"])) return null;
            body.groupKey = trimmed(form, "groupKey");
        }
        body.action = action;
        if (trimmed(form, "priority") !== "") body.priority = Number(trimmed(form, "priority"));
        if (action === "FORCE_VARIANT") {
            if (missing(form, ["variantId"])) return null;
            body.variantId = trimmed(form, "variantId");
        }
        return body;
    }

    function submitRule(panel, root, form) {
        const body = ruleBody(form);
        if (body) send(panel, root, form.querySelector('[type="submit"]'), urlOf(root, "rules"), body);
    }

    function submitOverride(panel, root, form) {
        const body = overrideBody(form);
        if (body) {
            const notFound = body.target === "account" ? "labelAccountUnknown" : "labelUnknown";
            send(panel, root, form.querySelector('[type="submit"]'), urlOf(root, "overrides"), body, notFound);
        }
    }

    // ---- tag suggestions

    /** The chips container right after `field`, created on first use. */
    function chipsAfter(field, name) {
        let box = field.parentElement.querySelector(`[data-suggestions="${name}"]`);
        if (!box) {
            box = document.createElement("div");
            box.dataset.suggestions = name;
            field.after(box);
        }
        return box;
    }

    function fillChips(box, texts, onPick) {
        box.replaceChildren(...texts.map(text => {
            const chip = document.createElement("mdui-chip");
            chip.dataset.suggestion = "";
            chip.textContent = text;
            chip.addEventListener("click", () => onPick(text));
            return chip;
        }));
    }

    /** Offers the known tags matching the typed one, or (once it is one) its values. */
    function suggest(form) {
        const keyField = fieldOf(form, "tagKey");
        const valueField = fieldOf(form, "tagValue");
        const list = keyField && document.getElementById(keyField.dataset.list);
        if (!list) return;
        const known = Array.from(list.options).map(option => ({
            key: option.value,
            values: (option.dataset.values ?? "").split(",").filter(Boolean),
        }));
        const typed = (keyField.value ?? "").trim();
        const exact = known.find(tag => tag.key === typed);
        fillChips(chipsAfter(keyField, "key"),
            exact ? [] : known.filter(tag => tag.key.toLowerCase().includes(typed.toLowerCase())).map(tag => tag.key),
            key => {
                keyField.value = key;
                suggest(form);
            });
        fillChips(chipsAfter(valueField, "value"), exact ? exact.values : [], value => {
            valueField.value = value;
        });
    }

    // ---- participants

    const SEARCH_DELAY_MS = 300;
    const searchTimers = new WeakMap();
    /** The url of each card's latest ask: an older answer arriving after it is dropped. */
    const asked = new WeakMap();

    const participantsOf = root => root.querySelector("[data-participants]");

    /** The chips and list of `card`'s fragment swapped in, and its state (variant, q, page) taken over. */
    function swapParticipants(card, fresh) {
        for (const part of ["[data-participants-chips]", "[data-participants-list]"]) {
            const current = card.querySelector(part);
            const next = fresh.querySelector(part);
            if (current && next) current.replaceWith(next);
        }
        for (const key of ["variant", "q", "page"]) {
            if (fresh.dataset[key] === undefined) delete card.dataset[key];
            else card.dataset[key] = fresh.dataset[key];
        }
    }

    /** Fetches the participants for `want` (variant, q, page; the card's own where left out) and swaps them in. */
    async function loadParticipants(root, want = {}) {
        const card = participantsOf(root);
        if (!card) return;
        const params = new URLSearchParams({
            variant: want.variant ?? card.dataset.variant ?? "",
            q: want.q ?? card.dataset.q ?? "",
            page: String(want.page ?? Number(card.dataset.page ?? 0)),
        });
        const url = `/spa/admin/experiments/${encodeURIComponent(root.dataset.experiment)}/participants?${params}`;
        asked.set(card, url);
        let html = null;
        try {
            html = await (await fetch(url)).text();
        } catch { /* told below */ }
        if (asked.get(card) !== url) return;
        const fresh = html === null ? null : new DOMParser().parseFromString(html, "text/html").querySelector("[data-participants]");
        if (!fresh) {
            CatapultChannel.showError(root.dataset.labelUnavailable);
            return;
        }
        swapParticipants(card, fresh);
    }

    /** The Overrides card as the server has it now: a move adds or replaces one. */
    async function refreshOverrides(root) {
        let html = null;
        try {
            html = await (await fetch(`/spa/admin/experiments/${encodeURIComponent(root.dataset.experiment)}`)).text();
        } catch { /* the card stays as it was */ }
        const fresh = html === null ? null : new DOMParser().parseFromString(html, "text/html").querySelector("#experiment-overrides");
        const current = root.querySelector("#experiment-overrides");
        if (!fresh || !current) return;
        current.replaceWith(fresh);
        fresh.querySelectorAll("form").forEach(sync);
    }

    async function moveParticipant(root, select) {
        const variantId = select.value;
        if (!variantId) return;
        select.disabled = true;
        const status = await post(null, urlOf(root, `assignments/${encodeURIComponent(select.dataset.participantsMove)}/variant`), { variantId });
        if (ok(status)) {
            window.mdui?.snackbar?.({ message: root.dataset.labelSaved });
            await Promise.all([loadParticipants(root), refreshOverrides(root)]);
            return;
        }
        select.disabled = false;
        select.value = "";
        CatapultChannel.showError(refusal(root, status, "labelUnknown"));
        if (status === 404 || status === 409) await CatapultAdminTabs.reload(root.closest(PANEL));
    }

    document.addEventListener("click", event => {
        const target = event.target.closest?.("[data-participants-variant], [data-participants-page]");
        const root = target && rootOf(target);
        if (!root || target.disabled) return;
        if (target.dataset.participantsPage !== undefined) loadParticipants(root, { page: Number(target.dataset.participantsPage) });
        else loadParticipants(root, { variant: target.dataset.participantsVariant, page: 0 });
    });

    const onSearch = event => {
        const field = event.target.closest?.("[data-participants-search]");
        const root = field && rootOf(field);
        if (!root) return;
        clearTimeout(searchTimers.get(field));
        searchTimers.set(field, setTimeout(() => loadParticipants(root, { q: (field.value ?? "").trim(), page: 0 }), SEARCH_DELAY_MS));
    };
    document.addEventListener("input", onSearch);
    document.addEventListener("clear", onSearch);

    document.addEventListener("change", event => {
        const select = event.target.closest?.("[data-participants-move]");
        const root = select && rootOf(select);
        if (root) moveParticipant(root, select);
    });

    // ---- wiring

    const rootOf = element => element.closest?.(`${PANEL} .admin-experiment-detail`);

    function sync(form) {
        if (form.id === "experiment-rule-form") syncRule(form);
        else if (form.id === "experiment-override-form") syncOverride(form);
        else if (form.id === "experiment-weights") {
            showShares(form);
            showBar(form);
        }
    }

    document.addEventListener("catapult:admin-panel", event => {
        if (event.detail.tab !== "experiments") return;
        event.detail.panel.querySelectorAll("#experiment-weights").forEach(asPercentages);
        event.detail.panel.querySelectorAll("#experiment-weights, #experiment-rule-form, #experiment-override-form").forEach(sync);
    });

    const handleOf = event => {
        const handle = event.target.closest?.("#experiment-weights [data-handle]");
        return handle && rootOf(handle) ? handle : null;
    };
    document.addEventListener("pointerdown", event => {
        const handle = handleOf(event);
        if (handle) dragHandle(handle.closest("form"), handle, event);
    });
    document.addEventListener("keydown", event => {
        const handle = handleOf(event);
        if (handle) nudgeHandle(handle.closest("form"), handle, event);
    });

    document.addEventListener("click", event => {
        const target = event.target.closest?.("[data-experiment-action], [data-rule-delete], [data-override-delete]");
        const root = target && rootOf(target);
        if (!root || target.disabled) return;
        const panel = root.closest(PANEL);
        if (target.dataset.experimentAction) {
            const action = target.dataset.experimentAction;
            act(panel, root, target, { end: "labelEndConfirm", reassign: "labelReassignConfirm" }[action]);
        } else if (target.dataset.ruleDelete) {
            remove(panel, root, target, `rules/${encodeURIComponent(target.dataset.ruleDelete)}/delete`);
        } else {
            remove(panel, root, target, `overrides/${encodeURIComponent(target.dataset.overrideDelete)}/delete`);
        }
    });

    document.addEventListener("submit", event => {
        const form = event.target;
        const root = rootOf(form);
        if (!root) return;
        event.preventDefault();
        const panel = root.closest(PANEL);
        if (form.id === "experiment-weights") saveWeights(panel, root, form);
        else if (form.id === "experiment-rule-form") submitRule(panel, root, form);
        else if (form.id === "experiment-override-form") submitOverride(panel, root, form);
    });

    // mdui-select announces "change", mdui-text-field "input"; both re-sync the form around them
    const onEdit = event => {
        const form = event.target.closest?.("#experiment-weights, #experiment-rule-form, #experiment-override-form");
        if (!form || !rootOf(form)) return;
        if (event.type === "input") event.target.removeAttribute?.("invalid");
        sync(form);
    };
    document.addEventListener("input", onEdit);
    document.addEventListener("change", onEdit);

    return {};
})();
