/**
 * The admin page's members tab. Each action posts to AdminMembersController, then re-renders the
 * page: a deletion, a promotion or an unlink changes more than its own row (the previous system
 * account, the migrate dialog's targets). The bot switch alone only posts, it already shows its state.
 */
window.CatapultAdminMembers = (function () {
    function memberUrl(row, action) {
        return `/admin/members/${row.dataset.memberId}/${action}`;
    }

    // A failed post already shows its snackbar (CatapultChannel.postJson): the page stays as it was.
    async function postThenRefresh(url, body) {
        const response = await CatapultChannel.postJson(url, body);
        if (response.ok) await CatapultChannel.refresh();
        return response;
    }

    /** Resolves to whether the admin confirmed `message`. */
    function confirmed(card, message) {
        return mdui.confirm({
            headline: message,
            confirmText: card.dataset.confirmText,
            cancelText: card.dataset.cancelText
        }).then(() => true, () => false);
    }

    function openMigrate(dialog, row) {
        dialog.dataset.memberId = row.dataset.memberId;
        document.getElementById("admin-migrate-headline").textContent =
            dialog.dataset.headline.replace("{0}", row.dataset.memberName);
        const target = document.getElementById("admin-migrate-target");
        target.value = "";
        // A member can't be migrated onto themself.
        target.querySelectorAll("mdui-menu-item").forEach(item => {
            item.disabled = item.getAttribute("value") === row.dataset.memberId;
        });
        ["settings", "getters", "bindings"].forEach(part => {
            document.getElementById(`admin-migrate-${part}`).checked = false;
        });
        dialog.open = true;
    }

    async function submitMigrate(dialog) {
        const targetId = document.getElementById("admin-migrate-target").value;
        const checked = part => document.getElementById(`admin-migrate-${part}`).checked;
        const body = {
            targetId,
            migrateSettings: checked("settings"),
            migrateGetters: checked("getters"),
            migrateBindings: checked("bindings")
        };
        if (!targetId || targetId === dialog.dataset.memberId
            || !(body.migrateSettings || body.migrateGetters || body.migrateBindings)) {
            CatapultChannel.showError(dialog.dataset.incomplete);
            return;
        }
        const response = await CatapultChannel.postJson(`/admin/members/${dialog.dataset.memberId}/migrate`, body);
        if (response.ok) dialog.open = false;
    }

    function attach() {
        const card = document.getElementById("admin-members");
        if (!card) return;
        const dialog = document.getElementById("admin-migrate-dialog");
        const rowOf = el => el.closest("[data-member-id]");

        card.querySelectorAll(".admin-member-bot-toggle").forEach(sw => {
            sw.addEventListener("change", async () => {
                const response = await CatapultChannel.postJson(memberUrl(rowOf(sw), "bot/toggle"), {});
                if (!response.ok) sw.checked = !sw.checked;
            });
        });

        card.querySelectorAll("mdui-menu-item[data-action]").forEach(item => {
            item.addEventListener("click", async () => {
                if (item.disabled) return;
                const row = rowOf(item);
                if (item.dataset.action === "migrate") {
                    openMigrate(dialog, row);
                    return;
                }
                if (item.dataset.confirm && !await confirmed(card, item.dataset.confirm)) return;
                await postThenRefresh(memberUrl(row, item.dataset.action), {});
            });
        });

        document.getElementById("admin-migrate-cancel")?.addEventListener("click", () => { dialog.open = false; });
        document.getElementById("admin-migrate-submit")?.addEventListener("click", () => submitMigrate(dialog));
    }

    document.addEventListener("catapult:render", attach);

    return { attach };
})();
