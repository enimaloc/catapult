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

    const twForm = document.getElementById("channel-tw-settings-form");
    if (twForm) {
        document.getElementById("tw-settings-save-btn").addEventListener("click", async () => {
            const enabled = document.getElementById("tw-enabled-checkbox").checked;
            const blockedTws = Array.from(twForm.querySelectorAll(".tw-block-checkbox:checked")).map(cb => cb.value);
            await CatapultCsrf.postJson(`${baseUrl}/settings/tws`, { enabled, blockedTws });
            await refresh();
        });
    }
})();
