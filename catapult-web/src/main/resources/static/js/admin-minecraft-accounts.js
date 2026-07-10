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

    // Jamais d'innerHTML avec des valeurs dynamiques : DOM + textContent uniquement.
    function showError(message) {
        const span = document.createElement("span");
        span.className = "text-danger";
        span.textContent = message || "Erreur";
        statusEl.replaceChildren(span);
        startBtn.disabled = false;
    }

    function showDeviceCode(dc) {
        const div = document.createElement("div");
        div.className = "alert alert-info";
        const b = document.createElement("b");
        b.textContent = dc.userCode;
        const a = document.createElement("a");
        a.href = dc.verificationUri;
        a.target = "_blank";
        a.rel = "noopener";
        a.textContent = dc.verificationUri;
        div.append(b, document.createTextNode(" — "), a);
        statusEl.replaceChildren(div);
    }

    startBtn.addEventListener("click", function () {
        startBtn.disabled = true;
        stopPolling();
        const label = (labelInput.value || "").trim() || "Compte Minecraft";
        statusEl.textContent = "…";
        post(root.dataset.startUrl).then(function (dc) {
            if (!dc || dc.status === "ERROR" || !dc.deviceCode) {
                showError(dc && dc.message);
                return;
            }
            if (typeof dc.verificationUri !== "string" || dc.verificationUri.indexOf("https://") !== 0) {
                showError();
                return;
            }
            showDeviceCode(dc);
            const intervalMs = Math.max(3, dc.interval || 5) * 1000;
            pollTimer = setInterval(function () {
                post(root.dataset.completeUrl, { deviceCode: dc.deviceCode, label: label })
                    .then(function (res) {
                        if (res.status === "PENDING") return; // on continue
                        stopPolling();
                        if (res.status === "CREATED") {
                            startBtn.disabled = false;
                            window.location.reload();
                        } else {
                            showError(res.message);
                        }
                    })
                    .catch(function () { stopPolling(); startBtn.disabled = false; });
            }, intervalMs);
        }).catch(function () { showError(); });
    });
}());
