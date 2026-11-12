/**
 * The admin Trigger warnings tab's DTDD topics sub-view (#tw/{id}/dtdd): any change (a least severe level picked, a
 * topic removed or added) saves them all, in the order shown, to AdminTwController, then patches the list
 * (admin-tabs.js). A topic is free text: kept in data-topic and sent in the body, never in a path. A refusal is told
 * with the view's data-error-{status}, else its data-error ({0}: the status).
 * Listeners are delegated to the panel: its content comes and goes.
 */
window.CatapultAdminTwDtdd = (function () {
    const TOPIC_MAX = 200;

    const viewOf = element => element.closest(".admin-tw-dtdd-view");

    /** The topics shown, each with its least severe level ("" any), plus `extra` when given. */
    function topicsOf(root, extra) {
        const topics = Array.from(root.querySelectorAll(".admin-tw-dtdd-row")).map(row => ({
            topic: row.dataset.topic,
            minSeverity: row.querySelector(".admin-tw-dtdd-min")?.value ?? ""
        }));
        if (extra) topics.push(extra);
        return topics;
    }

    /** Saves `topics` with `button` disabled meanwhile; patches the list once saved. */
    async function save(panel, root, button, topics) {
        const sent = await CatapultAdminTabs.post(root, button,
            `/admin/tw/${encodeURIComponent(root.dataset.twId)}/dtdd`, { topics });
        if (sent !== "ok") return false;
        button.disabled = false;
        window.mdui?.snackbar?.({ message: root.dataset.saved });
        await CatapultAdminTabs.patch(panel);
        return true;
    }

    async function add(panel, root, button) {
        const field = root.querySelector('[name="topic"]');
        const topic = (field.value ?? "").trim();
        if (topic === "" || topic.length > TOPIC_MAX) {
            field.setAttribute("invalid", "");
            field.reportValidity?.();
            return;
        }
        const known = topicsOf(root).some(t => t.topic.toLowerCase() === topic.toLowerCase());
        if (known) {
            field.value = "";
            return;
        }
        if (await save(panel, root, button, topicsOf(root, { topic, minSeverity: "" }))) field.value = "";
    }

    function attach() {
        const panel = document.querySelector('mdui-tab-panel[value="tw"]');
        if (!panel || panel.dataset.twDtddWired) return;
        panel.dataset.twDtddWired = "true";

        panel.addEventListener("click", event => {
            const button = event.target.closest(".admin-tw-dtdd-add, .admin-tw-dtdd-remove");
            if (!button || button.disabled) return;
            const root = viewOf(button);
            if (!root) return;
            if (button.matches(".admin-tw-dtdd-add")) {
                add(panel, root, button);
                return;
            }
            const row = button.closest(".admin-tw-dtdd-row");
            save(panel, root, button, topicsOf(root).filter(t => t.topic !== row.dataset.topic));
        });
        // mdui's select tells its change on itself
        panel.addEventListener("change", event => {
            const select = event.target.closest?.(".admin-tw-dtdd-min");
            if (!select) return;
            const root = viewOf(select);
            // disabled while it saves, as a button would be
            if (root) save(panel, root, select, topicsOf(root));
        });
        panel.addEventListener("keydown", event => {
            if (event.key !== "Enter" || !event.target.matches?.('.admin-tw-dtdd-view [name="topic"]')) return;
            event.preventDefault();
            const root = viewOf(event.target);
            add(panel, root, root.querySelector(".admin-tw-dtdd-add"));
        });
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
