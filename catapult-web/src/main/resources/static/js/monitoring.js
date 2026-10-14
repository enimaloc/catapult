/*
 * Reports the page's errors to Sentry, its own and the 5xx catapult-web answers it with, along with
 * what led to them, so a bug can be replayed step by step: the clicks, SPA navigations and fetches (Sentry's own breadcrumbs), the live update events
 * received (below), the trace that follows each fetch into catapult-web and catapult-api, and the
 * last minute of the page as a replay. Loaded with the SDK only when a DSN is set (see SentryBrowser).
 *
 * Replays show the page's text, which is the member's own dashboard, but never what is typed in a
 * field (a Steam token...), nor anything of a page without an error.
 */
(function () {
    const config = window.Catapult?.sentry;
    const Sentry = window.Sentry;
    if (!config || !Sentry) {
        return;
    }

    Sentry.init({
        dsn: config.dsn,
        environment: config.environment,
        release: config.release ?? undefined,
        // Through catapult-web (SentryTunnelController): blockers let it by.
        tunnel: "/monitoring/tunnel",
        sendDefaultPii: false,
        integrations: [
            Sentry.browserTracingIntegration(),
            // Dark Reader's style sheets left out: it rewrites them as the page loads, and the
            // replay would serialize each version (and may not read them anyway).
            Sentry.replayIntegration({
                maskAllText: false, maskAllInputs: true, blockAllMedia: false, block: [".darkreader"],
            }),
            // A request to catapult-web answered with a 5xx is an error of the page too, so the
            // replay of what led to it comes along with the server's own report, on the same trace.
            Sentry.httpClientIntegration({
                failedRequestStatusCodes: [[500, 599]],
                failedRequestTargets: [window.location.origin],
            }),
        ],
        tracesSampleRate: config.tracesSampleRate,
        replaysSessionSampleRate: 0,
        replaysOnErrorSampleRate: 1.0,
    });
    if (config.user) {
        Sentry.setUser({ id: config.user });
    }

    // The replay warns of each style sheet it may not read (an extension's, Dark Reader's...) and
    // records its text instead: silenced here, over Sentry's own console hook, so that dozens of
    // them don't crowd the bug's breadcrumbs out.
    const warn = console.warn;
    console.warn = function (message, ...rest) {
        if (typeof message === "string" && message.startsWith("Cannot get CSS styles from text's parentNode")) {
            return;
        }
        return warn.call(this, message, ...rest);
    };

    // Sentry sees none of the server's pushes: each event a live update stream delivers leaves a
    // breadcrumb (its name, not its data), as do the stream's drops and reconnections.
    const NativeEventSource = window.EventSource;
    if (!NativeEventSource) {
        return;
    }
    const crumb = (url, message, level = "info") =>
        Sentry.addBreadcrumb({ category: "sse", message, level, data: { url } });

    window.EventSource = class extends NativeEventSource {
        constructor(url, init) {
            super(url, init);
            super.addEventListener("open", () => crumb(String(url), "open"));
            super.addEventListener("error", () => crumb(String(url), "error", "warning"));
        }

        addEventListener(type, listener, options) {
            if (type === "open" || type === "error" || typeof listener !== "function") {
                super.addEventListener(type, listener, options);
                return;
            }
            const url = this.url;
            super.addEventListener(type, function (event) {
                crumb(url, type);
                return listener.call(this, event);
            }, options);
        }
    };
})();
