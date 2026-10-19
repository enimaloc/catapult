/**
 * The OBS card's widget link (the browser source URL, #obs-widget): Copy puts it on the clipboard,
 * Regenerate asks first (a dialog), posts once however often it is clicked, then re-renders the page,
 * which shows the new link. Below it, the check of the old page: is there a browser source in OBS pointing
 * at the link? It goes through the page's own OBS connection (CatapultObs), waiting a few seconds for it
 * when it isn't up yet, and offers to create the source on the current scene when none matches.
 * The card is replaced by each render: listeners go on its elements.
 */
window.CatapultObsWidget = (function () {
    // Same source as the old page's "Créer la source dans OBS".
    const SOURCE_NAME = "Catapult — Relais Twitchat";
    const SOURCE_SIZE = 100;
    const CONNECT_WAIT_MS = 5000;

    function attachLink(card) {
        card.querySelector("#obs-widget-copy-btn")?.addEventListener("click", () => {
            const field = card.querySelector("#obs-widget-link");
            const link = field?.value || field?.getAttribute("value");
            if (!link) return;
            const copied = navigator.clipboard?.writeText(link) ?? Promise.reject(new Error("no clipboard"));
            copied.then(() => CatapultChannel.showError(card.dataset.labelCopied))
                .catch(() => CatapultChannel.showError(card.dataset.labelCopyFailed));
        });

        const dialog = card.querySelector("#obs-widget-regenerate-dialog");
        card.querySelector("#obs-widget-regenerate-btn")?.addEventListener("click", () => { dialog.open = true; });
        dialog.querySelector(".obs-widget-regenerate-dismiss")?.addEventListener("click", () => { dialog.open = false; });

        const confirm = dialog.querySelector(".obs-widget-regenerate-confirm");
        confirm.addEventListener("click", async () => {
            if (confirm.disabled) return;
            confirm.disabled = true;
            let succeeded = false;
            try {
                const response = await CatapultCsrf.postJson(`${CatapultChannel.baseUrl()}/settings/twitchat/regenerate`, {});
                succeeded = response.ok;
                if (!succeeded) {
                    // Closed, so the message isn't hidden behind it; the button is free for the next try.
                    dialog.open = false;
                    CatapultChannel.showError(response.status === 401 || response.status === 403
                        ? card.dataset.labelRefused : card.dataset.labelUnavailable);
                }
            } catch (e) {
                dialog.open = false;
                CatapultChannel.showError(card.dataset.labelUnavailable);
            } finally {
                if (!succeeded) confirm.disabled = false;
            }
            if (succeeded) await CatapultChannel.refresh();
        });
    }

    // The current card's source check: one document listener for the page's life, not one per render.
    let active = null;

    document.addEventListener("catapult:obs:connected", () => {
        if (active?.card.isConnected) active.check();
    });

    function attachSourceCheck(card) {
        active?.stop();
        active = null;
        const status = card.querySelector("#obs-widget-source-status");
        const missing = card.querySelector("#obs-widget-source-missing");
        const create = card.querySelector("#obs-widget-source-create-btn");
        const widgetUrl = card.dataset.widgetUrl;
        if (!status || !missing || !widgetUrl) return;
        let stopped = false;

        const show = (label, isMissing) => {
            status.textContent = status.dataset[label];
            status.classList.toggle("hidden", isMissing);
            missing.classList.toggle("hidden", !isMissing);
        };
        // Bumped by every check, so an older one's answer is dropped.
        let generation = 0;
        let waiting = null;

        async function hasSource() {
            const { inputs = [] } = await CatapultObs.requests.getInputList({});
            for (const input of inputs.filter(i => i.inputKind === "browser_source")) {
                try {
                    const settings = await CatapultObs.requests.getInputSettings({ inputName: input.inputName });
                    if (settings?.inputSettings?.url === widgetUrl) return true;
                } catch (e) {
                    // an input we can't introspect
                }
            }
            return false;
        }

        async function check() {
            clearTimeout(waiting);
            const gen = ++generation;
            if (!window.CatapultObs?.isConnected()) {
                show("labelChecking", false);
                // The page's OBS session may still be connecting.
                waiting = setTimeout(() => { if (gen === generation) show("labelUnreachable", false); }, CONNECT_WAIT_MS);
                return;
            }
            show("labelChecking", false);
            let found;
            try {
                found = await hasSource();
                if (stopped) return;
            } catch (e) {
                if (gen === generation) show("labelUnreachable", false);
                return;
            }
            if (gen === generation) show(found ? "labelFound" : "labelChecking", !found);
        }

        create.addEventListener("click", async () => {
            if (create.disabled) return;
            create.disabled = true;
            try {
                const scene = await CatapultObs.requests.getCurrentProgramScene({});
                await CatapultObs.requests.createInput({
                    sceneName: scene.currentProgramSceneName ?? scene.sceneName,
                    inputName: SOURCE_NAME,
                    inputKind: "browser_source",
                    inputSettings: { url: widgetUrl, width: SOURCE_SIZE, height: SOURCE_SIZE },
                    sceneItemEnabled: true,
                });
                generation++;
                show("labelFound", false);
            } catch (e) {
                CatapultChannel.showError(card.dataset.labelCreateFailed);
            } finally {
                create.disabled = false;
            }
        });

        // Whenever the page's OBS (re)connects, e.g. right after "Connect" in this card, the listener above re-checks.
        active = { card, check, stop() { stopped = true; generation++; clearTimeout(waiting); } };
        check();
    }

    function attach(card) {
        attachLink(card);
        attachSourceCheck(card);
    }

    document.addEventListener("catapult:render", () => {
        const card = document.getElementById("obs-widget");
        if (card) attach(card);
    });

    return { attach };
})();
