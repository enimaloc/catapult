/**
 * The admin Notifications tab. "New notification" opens the dialog (built in the tab's fragment),
 * emptied each time. Save checks the fields as catapult-api does (title and body required, the call
 * to action's label and http(s) URL both or neither, an expiry in the future, a member chosen for
 * "One member") before posting to AdminNotificationsController; a refusal is told by its status
 * (400: data-label-invalid, 404: data-label-unknown-member, else data-label-unavailable) and leaves the dialog open, a success
 * closes it and reloads the tab. The Preview toggle renders the Markdown through the server
 * (catapult-api sanitizes it), shown as HTML in .notification-preview. Delete asks first.
 * "Experiment participants" picks an experiment and, optionally, one of its variants (the options come
 * from the fragment's .admin-notifications-variants-of lists), shows how many members that reaches
 * (/admin/notifications/audience-count, asked on every change) and, once sent, how many were told.
 * "One member" searches as the admin types (300 ms after the last key, /spa/admin/members/search, at
 * most 20 matches listed as options); picking an option sets the recipient.
 * Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminNotifications = (function () {
    const MAX_CTA_URL = 512;

    const fieldOf = (form, name) => form.querySelector(`[name="${name}"]`);
    const valueOf = (form, name) => (fieldOf(form, name).value ?? "").trim();

    function mark(field) {
        field.setAttribute("invalid", "");
        field.reportValidity?.();
    }

    function resetForm(form) {
        ["title", "body", "ctaLabel", "ctaUrl", "expiresAt", "memberSearch", "experimentId"].forEach(name => {
            const field = fieldOf(form, name);
            field.value = "";
            field.removeAttribute("invalid");
        });
        fieldOf(form, "severity").value = "INFO";
        fieldOf(form, "audience").value = "ALL";
        fieldOf(form, "mode").value = "edit";
        delete form.dataset.member;
        fillVariants(form, "");
        showCount(form, null);
        showMode(form, "edit");
        showAudience(form, "ALL");
        clearMemberOptions(form);
    }

    function clearMemberOptions(form) {
        searching++; // an answer still on its way is no longer wanted
        clearTimeout(searchTimer);
        form.querySelector(".admin-notifications-members").replaceChildren();
    }

    function showMode(form, mode) {
        form.querySelector(".notification-preview").hidden = mode !== "preview";
        fieldOf(form, "body").hidden = mode === "preview";
    }

    function showAudience(form, audience) {
        form.querySelector(".admin-notifications-member").hidden = audience !== "ONE";
        form.querySelector(".admin-notifications-chat-commands").hidden = audience !== "CHAT_COMMANDS";
        const experiment = form.querySelector(".admin-notifications-experiment-audience");
        if (experiment) experiment.hidden = audience !== "EXPERIMENT";
        if (audience === "EXPERIMENT") refreshCount(form);
    }

    const ALL_VARIANTS = "ALL";

    /** The variant select's options: "all variants", then the experiment's own (none for a blank id); back on "all". */
    function fillVariants(form, experimentId) {
        const root = rootOfForm(form);
        const select = fieldOf(form, "variantId");
        if (!select) return;
        const option = (value, label) => {
            const item = document.createElement("mdui-menu-item");
            item.setAttribute("value", value);
            item.dataset.variantOption = "";
            item.textContent = label;
            return item;
        };
        const source = [...form.querySelectorAll(".admin-notifications-variants-of")]
            .find(list => list.dataset.experimentId === experimentId);
        const variants = source ? [...source.querySelectorAll("[data-variant-id]")] : [];
        select.replaceChildren(option(ALL_VARIANTS, root.dataset.labelVariantAll),
                ...variants.map(v => option(v.dataset.variantId, v.textContent)));
        select.value = ALL_VARIANTS;
    }

    const experimentOf = form => fieldOf(form, "experimentId")?.value || "";
    const variantOf = form => {
        const value = fieldOf(form, "variantId")?.value;
        return !value || value === ALL_VARIANTS ? null : value;
    };

    function showCount(form, count) {
        const line = form.querySelector(".admin-notifications-count");
        if (!line) return;
        line.hidden = count === null;
        line.textContent = count === null ? "" : rootOfForm(form).dataset.labelRecipients.replace("{n}", count);
    }

    let counting = 0; // the latest count asked: an older answer is dropped

    /** Asks how many members the chosen experiment (and variant) reaches; the line is hidden when there is no answer. */
    async function refreshCount(form) {
        const asked = ++counting;
        const experimentId = experimentOf(form);
        if (experimentId === "") return showCount(form, null);
        let count = null;
        try {
            const response = await CatapultCsrf.postJson("/admin/notifications/audience-count",
                { audience: "EXPERIMENT", experimentId, variantId: variantOf(form) });
            if (response.ok) count = (await response.json()).count ?? null;
        } catch {
            // no count: the line stays hidden, sending is unaffected
        }
        if (asked === counting) showCount(form, count);
    }

    /** The request's body, or null when a field is refused (told in the view's language). */
    function bodyOf(root, form) {
        const refuse = (label, ...fields) => {
            fields.forEach(mark);
            if (label) CatapultChannel.showError(root.dataset[label]);
            return null;
        };
        const title = valueOf(form, "title");
        if (title === "") return refuse(null, fieldOf(form, "title"));
        if (valueOf(form, "body") === "") return refuse(null, fieldOf(form, "body"));

        const ctaLabel = valueOf(form, "ctaLabel");
        const ctaUrl = valueOf(form, "ctaUrl");
        if ((ctaLabel === "") !== (ctaUrl === "")) return refuse("labelCtaPair", fieldOf(form, "ctaLabel"), fieldOf(form, "ctaUrl"));
        if (ctaUrl !== "" && (!/^https?:\/\/\S+$/i.test(ctaUrl) || ctaUrl.length > MAX_CTA_URL)) {
            return refuse("labelInvalid", fieldOf(form, "ctaUrl"));
        }

        let expiresAt = null;
        const expiry = valueOf(form, "expiresAt");
        if (expiry !== "") {
            const date = new Date(expiry);
            if (Number.isNaN(date.getTime())) return refuse("labelInvalid", fieldOf(form, "expiresAt"));
            if (date.getTime() <= Date.now()) return refuse("labelPastExpiry", fieldOf(form, "expiresAt"));
            expiresAt = date.toISOString();
        }

        let targetUserId = null;
        if (fieldOf(form, "audience").value === "ONE") {
            targetUserId = form.dataset.member || null;
            if (targetUserId === null) return refuse("labelMemberRequired", fieldOf(form, "memberSearch"));
        }
        const audience = fieldOf(form, "audience").value;
        const experiment = audience === "EXPERIMENT" ? { experimentId: experimentOf(form), variantId: variantOf(form) } : {};
        if (audience === "EXPERIMENT" && experiment.experimentId === "") {
            return refuse("labelExperimentRequired", fieldOf(form, "experimentId"));
        }
        return {
            title,
            body: fieldOf(form, "body").value,
            severity: fieldOf(form, "severity").value,
            ctaUrl: ctaUrl || null,
            ctaLabel: ctaLabel || null,
            expiresAt,
            targetUserId,
            audience: audience === "CHAT_COMMANDS" || audience === "EXPERIMENT" ? audience : null,
            ...experiment
        };
    }

    /** The status of a POST (0 when it couldn't be sent) and its JSON body when it has one, `button` disabled meanwhile. */
    async function post(button, url, body) {
        button.disabled = true;
        try {
            const response = await CatapultCsrf.postJson(url, body);
            let data = null;
            try {
                data = await response.json();
            } catch {
                // no body, or not JSON
            }
            return { status: response.status, data };
        } catch {
            return { status: 0, data: null };
        } finally {
            button.disabled = false;
        }
    }

    const REFUSALS = { 400: "labelInvalid", 404: "labelUnknownMember" };
    const refusal = (root, status) => root.dataset[REFUSALS[status] ?? "labelUnavailable"];
    const ok = status => status >= 200 && status < 300;

    async function saveForm(panel, root, dialog, form, button) {
        const body = bodyOf(root, form);
        if (body === null) return;
        const { status, data } = await post(button, "/admin/notifications", body);
        if (!ok(status)) {
            CatapultChannel.showError(refusal(root, status));
            return;
        }
        dialog.open = false;
        const message = Number.isInteger(data?.sent) ? root.dataset.labelSent.replace("{n}", data.sent) : root.dataset.labelCreated;
        window.mdui?.snackbar?.({ message });
        await CatapultAdminTabs.reload(panel);
    }

    async function remove(panel, root, button) {
        button.disabled = true; // before the confirmation: a second click must not open another
        try {
            if (!await CatapultAdminTabs.confirmed(root, button.dataset.confirm)) return;
            const { status } = await post(button, `/admin/notifications/${encodeURIComponent(button.dataset.delete)}/delete`, {});
            button.disabled = true; // post() re-enabled it; still busy until the tab reloads
            if (ok(status)) await CatapultAdminTabs.reload(panel);
            else CatapultChannel.showError(refusal(root, status));
        } finally {
            button.disabled = false;
        }
    }

    let previewing = 0; // the latest preview asked: an older answer is dropped

    async function switchMode(root, form, mode) {
        const asked = ++previewing;
        if (mode !== "preview") return showMode(form, "edit");
        let html = null;
        try {
            const response = await CatapultCsrf.postJson("/admin/notifications/preview", { body: fieldOf(form, "body").value });
            if (response.ok) html = await response.text();
        } catch {
            // not sent: told below
        }
        if (asked !== previewing) return;
        if (html === null) {
            CatapultChannel.showError(root.dataset.labelUnavailable);
            fieldOf(form, "mode").value = "edit";
            return;
        }
        form.querySelector(".notification-preview").innerHTML = html; // sanitized by catapult-api
        showMode(form, "preview");
    }

    const SEARCH_DELAY_MS = 300;
    let searchTimer = null;
    let searching = 0; // the latest member search asked: an older answer is dropped

    /** Lists the members matching `query` as options; none for a blank query, and the list stays as it was when the search fails. */
    async function searchMembers(form, query) {
        const asked = ++searching;
        const q = query.trim();
        if (q === "") return clearMemberOptions(form);
        let found = null;
        try {
            const response = await fetch(`/spa/admin/members/search?q=${encodeURIComponent(q)}`);
            if (response.ok) found = await response.json();
        } catch {
            // not sent: told below
        }
        if (asked !== searching) return;
        if (found === null) {
            CatapultChannel.showError(rootOfForm(form).dataset.labelUnavailable);
            return;
        }
        form.querySelector(".admin-notifications-members").replaceChildren(...found.map(member => {
            const option = document.createElement("mdui-list-item");
            option.className = "admin-notifications-member-option";
            option.dataset.memberId = member.id;
            option.dataset.login = member.login;
            option.textContent = member.login;
            return option;
        }));
    }

    const rootOfForm = form => form.closest(".admin-notifications");

    function scheduleSearch(form, query) {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => searchMembers(form, query), SEARCH_DELAY_MS);
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="notifications"]');
        if (!panel) return;
        const rootOf = () => panel.querySelector(".admin-notifications");
        const dialogOf = () => panel.querySelector("#admin-notifications-dialog");
        const formOf = () => dialogOf().querySelector(".admin-notifications-form");

        panel.addEventListener("click", event => {
            const target = event.target.closest(
                "#admin-notifications-new, .admin-notifications-save, .admin-dialog-close,"
                + " .admin-notification-delete, .admin-notifications-member-option");
            if (!target || target.disabled) return;
            const root = rootOf();
            if (target.matches("#admin-notifications-new")) {
                resetForm(formOf());
                dialogOf().open = true;
            } else if (target.matches(".admin-dialog-close")) {
                dialogOf().open = false;
            } else if (target.matches(".admin-notifications-save")) {
                saveForm(panel, root, dialogOf(), formOf(), target);
            } else if (target.matches(".admin-notification-delete")) {
                remove(panel, root, target);
            } else {
                const form = formOf();
                form.dataset.member = target.dataset.memberId;
                fieldOf(form, "memberSearch").value = target.dataset.login;
                clearMemberOptions(form);
            }
        });
        panel.addEventListener("input", event => {
            const target = event.target;
            const form = target.closest?.(".admin-notifications-form");
            if (!form) return;
            target.removeAttribute?.("invalid");
            if (target.matches('[name="memberSearch"]')) {
                delete form.dataset.member; // typed over: the choice is to be made again
                scheduleSearch(form, target.value ?? "");
            }
        });
        panel.addEventListener("change", event => {
            const target = event.target;
            const form = target.closest?.(".admin-notifications-form");
            if (!form) return;
            if (target.matches('[name="mode"]')) switchMode(rootOf(), form, target.value);
            else if (target.matches('[name="audience"]')) showAudience(form, target.value);
            else if (target.matches('[name="experimentId"]')) {
                target.removeAttribute("invalid");
                fillVariants(form, target.value);
                refreshCount(form);
            } else if (target.matches('[name="variantId"]')) refreshCount(form);
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
