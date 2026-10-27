/*
 * The API documentation page (/docs/api, ApiDocsController): Swagger UI on the site's copy of catapult-api's spec
 * (#swagger-ui's data-spec), and an indicator of whether catapult-api answers (#api-docs-status, from /api/health).
 * The page is one of spa.js's: Swagger UI's bundle (data-bundle, data-css: served by the site, no CDN) is only
 * loaded on the first render that shows it, then started on each new #swagger-ui. "Try it out" calls the site
 * itself (the spec's servers are "/"), which relays the documented paths to catapult-api.
 */
window.CatapultApiDocs = (function () {
    /**
     * catapult-api's generic example of a widget token (ApiV2.GENERIC_UUID_EXAMPLE), which "Try it out" pre-fills:
     * replaced by the visitor's own, which the dashboard keeps in localStorage, as catapult-api's own Swagger UI
     * does (SwaggerUiJwtTransformer). Entirely in the browser: no token of any kind goes to the server for it.
     */
    const GENERIC_UUID_EXAMPLE = "00000000-0000-0000-0001-000000000190";
    const WIDGET_UUID_KEY = "catapult_widget_uuid";

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

    function widgetUuid() {
        try {
            return window.localStorage.getItem(WIDGET_UUID_KEY);
        } catch {
            return null;
        }
    }

    /**
     * Swagger UI only creates a parameter's <input> once "Try it out" is clicked, pre-filled with the example: it is
     * watched for. The input is React-controlled: its value goes through the native setter, then an "input" event,
     * for React to take the change (assigning el.value would only change what the field shows).
     */
    function swapUuidExample(root) {
        const uuid = widgetUuid();
        if (!uuid) return;
        const setValue = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
        new MutationObserver(() => {
            root.querySelectorAll("input").forEach(input => {
                if (input.value !== GENERIC_UUID_EXAMPLE) return;
                setValue.call(input, uuid);
                input.dispatchEvent(new Event("input", { bubbles: true }));
                input.dispatchEvent(new Event("change", { bubbles: true }));
            });
        }).observe(root, { childList: true, subtree: true });
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

    async function start() {
        const status = document.getElementById("api-docs-status");
        if (status) checkHealth(status);
        const root = document.getElementById("swagger-ui");
        if (!root || root.dataset.started) return;
        root.dataset.started = "true";
        swapUuidExample(root);
        try {
            const SwaggerUIBundle = await bundle(root);
            SwaggerUIBundle({
                url: root.dataset.spec,
                domNode: root,
                deepLinking: true,
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
