/**
 * The DTDD mapping card. Its outcomes are pre-rendered hidden (see overview.html), so a
 * validation or a proposal only reveals them instead of re-rendering the page, and the mapping's
 * DtddMappingChangedEvent (channel-events.js) is applied to them in place (apply()).
 */
window.CatapultDtdd = (function () {
    const byId = id => document.getElementById(id);
    const show = (el, visible) => el?.classList.toggle("hidden", !visible);
    const setText = (el, text) => { if (el && el.textContent !== text) el.textContent = text; };

    /**
     * Shows the mapping a DtddMappingChangedEvent carries: as the event tells it (the live event's
     * hasPending) or as the viewer sees it (the page state's proposed and validatable). False
     * when the card can't show it in place, for the page to be re-rendered: the event is for another
     * game than the card's (or the page has no card), it lacks the mapping (an older catapult-api),
     * or proposals are pending (whether the viewer's own is among them, only the page knows).
     */
    function apply(data) {
        const card = byId("channel-dtdd-mapping-card");
        if (!data?.igdbId || !card || card.dataset.igdbId !== String(data.igdbId)) return false;
        const current = data.current ?? null;
        const pending = data.hasPending;
        const note = byId("dtdd-proposed");
        const proposed = typeof data.proposed === "boolean" ? data.proposed : pending === false ? false : null;
        if (proposed === null) return false;

        show(byId("dtdd-current"), !!current);
        show(byId("dtdd-no-mapping"), !current);
        if (current) {
            setText(byId("dtdd-name"), current.name || "—");
            setText(byId("dtdd-confidence"), `${Math.round((current.confidence ?? 0) * 100)}%`);
            show(byId("dtdd-verified"), !!current.verified);
        }
        // Rendered for admins alone: a mapping nobody verified, with no proposal pending.
        const validatable = typeof data.validatable === "boolean" ? data.validatable
            : !!current && !current.verified && pending === false;
        show(byId("dtdd-validate-btn"), validatable);
        show(note, proposed);
        return true;
    }

    document.addEventListener("catapult:render", function () {
        const card = byId("channel-dtdd-mapping-card");
        if (!card) return;

        const { on, postJson } = CatapultChannel;
        // ApiChannelDtddMappingController is globally scoped, not username-scoped, so these
        // routes don't carry the username (unlike CatapultChannel.baseUrl()).
        const dtddBaseUrl = "/channel/dtdd-mapping";
        const igdbId = card.dataset.igdbId;
        // Validating is only offered while no proposal is pending, nor the mapping verified.
        const hideValidate = () => byId("dtdd-validate-btn")?.classList.add("hidden");

        on("dtdd-validate-btn", "click", async () => {
            const response = await postJson(`${dtddBaseUrl}/validate`, { igdbId });
            if (!response.ok) return;
            hideValidate();
            byId("dtdd-verified")?.classList.remove("hidden");
            const confidence = byId("dtdd-confidence");
            if (confidence) confidence.textContent = "100%";
        });

        const dialog = byId("dtdd-search-dialog");
        on("dtdd-correct-btn", "click", () => { dialog.hidden = !dialog.hidden; });

        GameSearch.attach(byId("dtdd-search-input"), byId("dtdd-search-results"),
            `${dtddBaseUrl}/search`,
            async game => {
                const response = await postJson(`${dtddBaseUrl}/propose`, { igdbId, dtddId: game.dtddId, reason: "correction" });
                if (!response.ok) return;
                hideValidate();
                dialog.hidden = true;
                byId("dtdd-proposed")?.classList.remove("hidden");
            },
            { pick: data => data.results });
    });

    return { apply };
})();
