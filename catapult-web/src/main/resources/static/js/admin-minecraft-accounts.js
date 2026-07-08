// Enrôlement d'un compte de service Minecraft par device-code Microsoft.
// start → affiche user_code + lien microsoft.com/link → poll complete toutes
// les `interval` secondes ; 202 = PENDING (continue), 200 = créé (reload),
// autre = erreur (stop).
(function () {
    "use strict";

    const root = document.getElementById("mc-enroll");
    if (!root) return;

    const startBtn = document.getElementById("mc-enroll-start");
    const labelInput = document.getElementById("mc-enroll-label");
    const statusEl = document.getElementById("mc-enroll-status");
    const csrfHeader = root.dataset.csrfHeader;
    const csrfToken = root.dataset.csrfToken;
    let pollTimer = null;

    function post(url, params) {
        const body = new URLSearchParams(params || {});
        return fetch(url, {
            method: "POST",
            headers: { [csrfHeader]: csrfToken, "Content-Type": "application/x-www-form-urlencoded" },
            body: body.toString()
        }).then(function (r) { return r.json(); });
    }

    function stopPolling() {
        if (pollTimer !== null) { clearInterval(pollTimer); pollTimer = null; }
    }

    startBtn.addEventListener("click", function () {
        stopPolling();
        const label = (labelInput.value || "").trim() || "Compte Minecraft";
        statusEl.textContent = "…";
        post(root.dataset.startUrl).then(function (dc) {
            if (!dc || dc.status === "ERROR" || !dc.deviceCode) {
                statusEl.innerHTML = "<span class='text-danger'>Erreur</span>";
                return;
            }
            statusEl.innerHTML =
                "<div class='alert alert-info'>" +
                "<b>" + dc.userCode + "</b> — <a href='" + dc.verificationUri + "' target='_blank' rel='noopener'>" +
                dc.verificationUri + "</a></div>";
            const intervalMs = Math.max(3, dc.interval || 5) * 1000;
            pollTimer = setInterval(function () {
                post(root.dataset.completeUrl, { deviceCode: dc.deviceCode, label: label })
                    .then(function (res) {
                        if (res.status === "PENDING") return; // on continue
                        stopPolling();
                        if (res.status === "CREATED") {
                            window.location.reload();
                        } else {
                            statusEl.innerHTML = "<span class='text-danger'>" +
                                (res.message || "Erreur") + "</span>";
                        }
                    })
                    .catch(stopPolling);
            }, intervalMs);
        });
    });
}());
