// Enrôlement / ré-authentification d'un compte de service Minecraft par
// device-code Microsoft. start → affiche user_code + lien microsoft.com/link →
// poll complete toutes les `interval` secondes ; 202 = PENDING (continue),
// 200 = CREATED (reload), autre = erreur (stop).
(function () {
    "use strict";

    const root = document.getElementById("mc-enroll");
    if (!root) return;

    const csrfHeader = root.dataset.csrfHeader;
    const csrfToken = root.dataset.csrfToken;

    function post(url, params) {
        const body = new URLSearchParams(params || {});
        return fetch(url, {
            method: "POST",
            headers: { [csrfHeader]: csrfToken, "Content-Type": "application/x-www-form-urlencoded" },
            body: body.toString()
        }).then(function (r) { return r.json(); });
    }

    // Jamais d'innerHTML avec des valeurs dynamiques : DOM + textContent uniquement.
    function showError(statusEl, message) {
        const span = document.createElement("span");
        span.className = "text-danger";
        span.textContent = message || "Erreur";
        statusEl.replaceChildren(span);
    }

    function showDeviceCode(statusEl, dc) {
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

    /**
     * Lance un flow device-code complet : start(), affiche le code, puis poll
     * complete(deviceCode) jusqu'à CREATED/ERROR. `onSuccess` reçoit la réponse
     * finale ; `setDisabled` pilote l'état du bouton déclencheur pendant le flow.
     */
    function runDeviceCodeFlow(startUrl, completeUrl, extraParams, statusEl, setDisabled, onSuccess) {
        let pollTimer = null;
        function stopPolling() {
            if (pollTimer !== null) { clearInterval(pollTimer); pollTimer = null; }
        }

        setDisabled(true);
        statusEl.textContent = "…";
        post(startUrl).then(function (dc) {
            if (!dc || dc.status === "ERROR" || !dc.deviceCode) {
                showError(statusEl, dc && dc.message);
                setDisabled(false);
                return;
            }
            if (typeof dc.verificationUri !== "string" || dc.verificationUri.indexOf("https://") !== 0) {
                showError(statusEl);
                setDisabled(false);
                return;
            }
            showDeviceCode(statusEl, dc);
            const intervalMs = Math.max(3, dc.interval || 5) * 1000;
            pollTimer = setInterval(function () {
                const params = Object.assign({ deviceCode: dc.deviceCode }, extraParams || {});
                post(completeUrl, params)
                    .then(function (res) {
                        if (res.status === "PENDING") return; // on continue
                        stopPolling();
                        if (res.status === "CREATED") {
                            onSuccess(res);
                        } else {
                            showError(statusEl, res.message);
                            setDisabled(false);
                        }
                    })
                    .catch(function () { stopPolling(); setDisabled(false); });
            }, intervalMs);
        }).catch(function () { showError(statusEl); setDisabled(false); });
    }

    const startBtn = document.getElementById("mc-enroll-start");
    const labelInput = document.getElementById("mc-enroll-label");
    const statusEl = document.getElementById("mc-enroll-status");

    startBtn.addEventListener("click", function () {
        const label = (labelInput.value || "").trim() || "Compte Minecraft";
        runDeviceCodeFlow(
            root.dataset.startUrl,
            root.dataset.completeUrl,
            { label: label },
            statusEl,
            function (disabled) { startBtn.disabled = disabled; },
            function () { window.location.reload(); }
        );
    });

    document.querySelectorAll(".mc-reauth-btn").forEach(function (btn) {
        const id = btn.dataset.id;
        const statusEl = document.querySelector('.mc-reauth-status[data-for="' + id + '"]');
        if (!statusEl) return;
        btn.addEventListener("click", function () {
            runDeviceCodeFlow(
                btn.dataset.startUrl,
                btn.dataset.completeUrl,
                null,
                statusEl,
                function (disabled) { btn.disabled = disabled; },
                function () { window.location.reload(); }
            );
        });
    });
}());
