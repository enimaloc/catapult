/**
 * The personal API token cards: since when a token exists, generate/regenerate (regeneration confirmed: it
 * invalidates the old token) and revoke. A generated token is shown once, in a read-only field with a copy button and
 * an example on the site's own address (catapult-web relays /api/me/** to catapult-api); it is kept nowhere but that
 * field. One card per token, each parameterized by its base URL (catapult-web's relay of the token's internal routes:
 * GET status, POST regenerate, POST {base}/delete) and its elements' id prefix:
 *
 * - #channel-api-token-card (Account tab, while custom bindings are open to the channel): the full token,
 *   /me/api-token, a curl example activating a custom binding;
 * - #channel-read-token-card (Account tab): the read-only token, /me/api-token/read, the chat bots' summary link;
 * - #api-docs-read-token and #api-docs-full-token (the API docs page's keys card, /docs/api): the same two tokens,
 *   the full one only while custom bindings are open to the member.
 *
 * Each token generated or revoked is told to the page as a `catapult:api-token` event ({base, created}, created null
 * once revoked): the Commands tab's links put a new read-only token in (channel-command-migration.js).
 *
 * Every text goes in through textContent or a property, never as markup.
 */
window.CatapultApiTokenCards = (function () {
    const byId = id => document.getElementById(id);
    const show = (el, visible) => el?.classList.toggle("hidden", !visible);

    const FULL = "/me/api-token";
    const READ = "/me/api-token/read";

    /** `text` inside a shell single-quoted string. */
    const shellQuote = text => `'${String(text).replace(/'/g, "'\\''")}'`;

    /** The full token's example: activating the card's example custom binding (data-example-name). */
    function customBindingsCurl(token, card) {
        return `curl -X PUT -H ${shellQuote(`Authorization: Bearer ${token}`)} `
            + `-H 'Content-Type: application/json' -d ${shellQuote(JSON.stringify({ idOrName: card.dataset.exampleName }))} `
            + `${window.location.origin}/api/me/custom-bindings/active`;
    }

    /** The read-only token's example: the summary a chat bot reads, as its answer gives it (else built here). */
    function summaryCurl(token, card, created) {
        const url = created?.summaryUrl
            ?? `${window.location.origin}/api/me/game/summary?token=${encodeURIComponent(token)}`;
        return `curl ${shellQuote(url)}`;
    }

    // The promise of each base's last status answer, kept for the page: /me is the logged-in member, not the channel,
    // so SPA navigations reuse it. Absent until fetched, and again after a failure so a later render retries.
    const statuses = new Map();

    function fetchStatus(base) {
        if (!statuses.has(base)) {
            const request = (async () => {
                const response = await fetch(base, { headers: { Accept: "application/json" } });
                if (!response.ok) throw new Error(String(response.status));
                return response.json();
            })();
            statuses.set(base, request);
            request.catch(() => { if (statuses.get(base) === request) statuses.delete(base); });
        }
        return statuses.get(base);
    }

    /** Posts and answers the response, or null (told) when the request could not be sent. */
    async function post(url) {
        try {
            return await CatapultCsrf.postJson(url, {});
        } catch {
            CatapultChannel.showError(CatapultChannel.unreachable());
            return null;
        }
    }

    function tell(base, created) {
        document.dispatchEvent(new CustomEvent("catapult:api-token", { detail: { base, created } }));
    }

    /**
     * Wires `card` to the token behind `base`: its elements are `#<prefix>-status`, `-generate-btn`, `-revoke-btn`,
     * `-reveal`, `-value`, `-copy-btn`, `-curl` and `-confirm-dialog`; `curl(token, card, created)` is the example
     * shown under a token just generated.
     */
    function attach(card, { base, prefix, curl }) {
        const el = name => byId(`${prefix}-${name}`);
        const status = el("status");
        const generate = el("generate-btn");
        const revoke = el("revoke-btn");
        const reveal = el("reveal");
        const value = el("value");
        const example = el("curl");
        const dialog = el("confirm-dialog");
        let exists = false;
        let pending = null;

        function render(token) {
            exists = !!token?.exists;
            status.textContent = exists
                ? card.dataset.labelSince.replace("{0}", new Date(token.createdAt)
                    .toLocaleString(document.documentElement.lang || undefined))
                : card.dataset.labelNone;
            generate.textContent = exists ? card.dataset.labelRegenerate : card.dataset.labelGenerate;
            show(revoke, exists);
        }

        function forget() {
            value.value = "";
            example.textContent = "";
            show(reveal, false);
        }

        async function regenerate() {
            const response = await post(base);
            if (!response) return;
            if (!response.ok) return CatapultChannel.showError(CatapultChannel.actionFailed(response.status));
            const created = await response.json();
            value.value = created.token;
            example.textContent = curl(created.token, card, created);
            show(reveal, true);
            const generated = { exists: true, createdAt: created.createdAt };
            statuses.set(base, Promise.resolve(generated));
            render(generated);
            tell(base, created);
        }

        async function revokeToken() {
            const response = await post(`${base}/delete`);
            if (!response) return;
            if (!response.ok) return CatapultChannel.showError(CatapultChannel.actionFailed(response.status));
            forget();
            const revoked = { exists: false };
            statuses.set(base, Promise.resolve(revoked));
            render(revoked);
            tell(base, null);
        }

        function confirm(action) {
            pending = action;
            dialog.querySelectorAll("[data-for]").forEach(p => show(p, p.dataset.for === action));
            dialog.open = true;
        }

        generate.addEventListener("click", () => (exists ? confirm("regenerate") : regenerate()));
        revoke.addEventListener("click", () => confirm("revoke"));
        dialog.querySelector(".api-token-confirm-dismiss")?.addEventListener("click", () => { dialog.open = false; });
        dialog.querySelector(".api-token-confirm-ok")?.addEventListener("click", async () => {
            const action = pending;
            pending = null;
            dialog.open = false;
            await (action === "revoke" ? revokeToken() : regenerate());
        });

        el("copy-btn")?.addEventListener("click", async () => {
            try {
                await navigator.clipboard.writeText(value.value);
                CatapultChannel.showError(card.dataset.labelCopied);
            } catch {
                value.select?.();
            }
        });

        // The token itself is never part of the page: only whether one exists.
        (async () => {
            try {
                render(await fetchStatus(base));
            } catch {
                status.textContent = card.dataset.labelUnavailable ?? "";
            }
        })();
    }

    /** Wires every token card the page has (each render; api-docs.js again once its pending keys card is in). */
    function attachAll() {
        const full = byId("channel-api-token-card");
        if (full) attach(full, { base: FULL, prefix: "api-token", curl: customBindingsCurl });
        const read = byId("channel-read-token-card");
        if (read) attach(read, { base: READ, prefix: "read-token", curl: summaryCurl });
        const docsRead = byId("api-docs-read-token");
        if (docsRead) attach(docsRead, { base: READ, prefix: "api-docs-read-token", curl: summaryCurl });
        const docsFull = byId("api-docs-full-token");
        if (docsFull) attach(docsFull, { base: FULL, prefix: "api-docs-full-token", curl: customBindingsCurl });
    }

    document.addEventListener("catapult:render", attachAll);

    return { attach, attachAll, customBindingsCurl, summaryCurl, FULL, READ };
})();
