/**
 * The admin Experiments detail view. Status actions, weights, rules and overrides post to
 * AdminExperimentsController with their button disabled meanwhile; a success reloads the view, a
 * refusal is told by its status (400 invalid, 404 gone and reloads, anything else unavailable; a 409
 * is a refused change too). End and Reassign, and deleting a rule or an override, confirm first. The
 * rule and override forms hold one block of fields per type/target and show only the chosen one
 * (variantId only for FORCE_VARIANT); weights show their live share of the total. mdui-text-field
 * can't bind a <datalist> (shadow DOM), so the known tags and the chosen tag's values are offered as
 * chips under the tag fields. Listeners are delegated to the document: the view comes and goes.
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

    function refusal(root, status) {
        if (status === 404) return root.dataset.labelUnknown;
        return status === 400 || status === 409 ? root.dataset.labelInvalid : root.dataset.labelUnavailable;
    }

    /** Tells the outcome of `status`: done (snackbar, reload) or refused; a vanished target also reloads. */
    async function settle(panel, root, status) {
        if (ok(status)) {
            window.mdui?.snackbar?.({ message: root.dataset.labelSaved });
            await CatapultAdminTabs.reload(panel);
            return;
        }
        CatapultChannel.showError(refusal(root, status));
        if (status === 404) await CatapultAdminTabs.reload(panel);
    }

    /** Posts and settles; `button` stays busy until the view reloads. */
    async function send(panel, root, button, url, body) {
        const status = await post(button, url, body);
        if (button) button.disabled = true;
        try {
            await settle(panel, root, status);
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
        if (body) send(panel, root, form.querySelector('[type="submit"]'), urlOf(root, "overrides"), body);
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

    // ---- wiring

    const rootOf = element => element.closest?.(`${PANEL} .admin-experiment-detail`);

    function sync(form) {
        if (form.id === "experiment-rule-form") syncRule(form);
        else if (form.id === "experiment-override-form") syncOverride(form);
        else if (form.id === "experiment-weights") showShares(form);
    }

    document.addEventListener("catapult:admin-panel", event => {
        if (event.detail.tab !== "experiments") return;
        event.detail.panel.querySelectorAll("#experiment-weights, #experiment-rule-form, #experiment-override-form").forEach(sync);
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
