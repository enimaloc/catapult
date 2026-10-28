/*
 * The API documentation page (/docs/api, ApiDocsController): Swagger UI on the site's copy of catapult-api's spec
 * (#swagger-ui's data-spec), and an indicator of whether catapult-api answers (#api-docs-status, from /api/health).
 * A cached copy's banner (#api-docs-cached) tells when it was fetched in the visitor's own time zone: the server's
 * words, in its zone, are rewritten from data-fetched-at (an instant) into data-label's {0}, as DD/MM HH:MM.
 * The page is one of spa.js's: Swagger UI's bundle (data-bundle, data-css: served by the site, no CDN) is only
 * loaded on the first render that shows it, then started on each new #swagger-ui. "Try it out" calls the site
 * itself (the spec's servers are "/"), which relays the documented paths to catapult-api.
 * A member's keys card (#api-docs-keys-card) has the OBS widget's UUID with a copy button, wired here; its tokens'
 * rows are api-token-cards.js's.
 */
window.CatapultApiDocs = (function () {
    /** The bundle's loading, shared by every render until it is there; dropped when it fails, to try again. */
    let loading = null;

    function bundle(root) {
        if (window.SwaggerUIBundle) return Promise.resolve(window.SwaggerUIBundle);
        if (!loading) {
            loading = new Promise((resolve, reject) => {
                if (!document.head.querySelector(`link[href="${CSS.escape(root.dataset.css)}"]`)) {
                    const css = document.createElement("link");
                    css.rel = "stylesheet";
                    css.href = root.dataset.css;
                    document.head.append(css);
                }
                const script = document.createElement("script");
                script.src = root.dataset.bundle;
                script.addEventListener("load", () => resolve(window.SwaggerUIBundle));
                script.addEventListener("error", () => {
                    loading = null;
                    reject(new Error("Swagger UI could not be loaded"));
                });
                document.head.append(script);
            });
        }
        return loading;
    }

    function showStatus(el, up) {
        el.dataset.state = up ? "up" : "down";
        el.textContent = up ? el.dataset.labelUp : el.dataset.labelDown;
    }

    async function checkHealth(el) {
        try {
            const answer = await fetch("/api/health", { cache: "no-store" });
            showStatus(el, answer.ok);
        } catch {
            showStatus(el, false);
        }
    }

    const two = number => String(number).padStart(2, "0");

    function showFetchedAt(el) {
        const at = new Date(el.dataset.fetchedAt);
        if (!el.dataset.label || Number.isNaN(at.getTime())) return;
        const when = `${two(at.getDate())}/${two(at.getMonth() + 1)} ${two(at.getHours())}:${two(at.getMinutes())}`;
        el.textContent = el.dataset.label.replace("{0}", () => when);
    }

    /** The widget UUID's copy button: copied and told (data-label-copied), else the field selected to copy by hand. */
    function wireWidgetCopy(row) {
        const button = document.getElementById("api-docs-widget-copy-btn");
        const field = document.getElementById("api-docs-widget-value");
        if (!button || !field || row.dataset.wired) return;
        row.dataset.wired = "true";
        button.addEventListener("click", async () => {
            try {
                await navigator.clipboard.writeText(field.value);
                window.CatapultChannel?.showError(row.dataset.labelCopied);
            } catch {
                field.select?.();
            }
        });
    }

    async function start() {
        const widget = document.getElementById("api-docs-widget");
        if (widget) wireWidgetCopy(widget);
        const cached = document.getElementById("api-docs-cached");
        if (cached) showFetchedAt(cached);
        const status = document.getElementById("api-docs-status");
        if (status) checkHealth(status);
        const root = document.getElementById("swagger-ui");
        if (!root || root.dataset.started) return;
        root.dataset.started = "true";
        try {
            const SwaggerUIBundle = await bundle(root);
            SwaggerUIBundle({
                url: root.dataset.spec,
                domNode: root,
                // Off: its hash history would reach spa.js's popstate, which renders the page again.
                deepLinking: false,
                presets: [SwaggerUIBundle.presets.apis],
                layout: "BaseLayout",
            });
        } catch {
            root.textContent = root.dataset.labelFailed;
        }
    }

    document.addEventListener("catapult:render", start);

    return { start };
})();
