(function () {
    const list = document.getElementById("channel-bindings-list");
    if (!list) return;

    function bindingUrl(row, suffix) {
        const username = location.pathname.split("/")[2];
        return `/channel/${username}/bindings/${row.dataset.bindingId}/${suffix}`;
    }

    async function refresh() {
        navigate(location.pathname.replace(/^\/+/, ""), false);
    }

    list.querySelectorAll(".binding-ccl-toggle").forEach(toggle => {
        toggle.addEventListener("change", async () => {
            const row = toggle.closest("[data-binding-id]");
            await CatapultCsrf.postJson(bindingUrl(row, "ccl-toggle"), { enabled: toggle.checked });
            await refresh();
        });
    });

    list.querySelectorAll(".binding-ignored-toggle").forEach(toggle => {
        toggle.addEventListener("change", async () => {
            const row = toggle.closest("[data-binding-id]");
            await CatapultCsrf.postJson(bindingUrl(row, "ignored-toggle"), { ignored: toggle.checked });
            await refresh();
        });
    });

    list.querySelectorAll(".binding-delete-btn").forEach(btn => {
        btn.addEventListener("click", async () => {
            const row = btn.closest("[data-binding-id]");
            await CatapultCsrf.postJson(bindingUrl(row, "delete"), {});
            await refresh();
        });
    });
})();
