(function () {
    const username = location.pathname.split("/")[2];
    const baseUrl = `/channel/${username}`;

    async function refresh() {
        navigate(location.pathname.replace(/^\/+/, ""), false);
    }

    const cclForm = document.getElementById("channel-ccl-settings-form");
    if (cclForm) {
        document.getElementById("ccl-settings-save-btn").addEventListener("click", async () => {
            const enabled = document.getElementById("ccl-enabled-checkbox").checked;
            const blockedCcls = Array.from(cclForm.querySelectorAll(".ccl-block-checkbox:checked")).map(cb => cb.value);
            await CatapultCsrf.postJson(`${baseUrl}/settings/ccl`, { cclEnabled: enabled, blockedCcls });
            await refresh();
        });
    }
})();
