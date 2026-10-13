// catapult-web/src/main/resources/static/js/mock-popout.js
/**
 * Mock profile only. The member's config (/mock/admin) lives in a popout window next to the app.
 *
 * In the app (index.html): the "Mock" button opens the popout.
 *
 * In the popout (the mock pages, data-role "popout"): picking an account logs the app in without
 * leaving the popout. The config's forms post in the background, the popout swapping in the page
 * it gets back; the app's page follows through its events, re-rendering only for what none carries.
 */
window.CatapultMockPopout = (function () {
    const NAME = "catapult-mock";
    const FEATURES = "popup,width=780,height=900";

    const script = document.currentScript ?? document.querySelector('script[src$="mock-popout.js"]');

    function open(url = "/mock/admin") {
        return window.open(url, NAME, FEATURES);
    }

    /** Logs the app in through `url` in the background: it goes to its channels, the popout back to the config. */
    async function loginThrough(url, app, popout) {
        try {
            await fetch(url, { credentials: "same-origin" });
        } catch (error) {
            console.warn("[mock] login failed", error);
            app.location.href = url; // the plain way, at least
            return;
        }
        app.location.href = "/channels";
        popout.location.href = "/mock/admin";
    }

    function wireApp() {
        document.getElementById("mock-popout-btn")?.addEventListener("click", () => open());
    }

    function wirePopout() {
        const app = window.opener;
        if (!app) return; // opened on its own: the pages work as plain pages
        document.addEventListener("click", (event) => {
            const link = event.target.closest?.('a[href^="/auth/callback"]');
            if (!link) return;
            event.preventDefault();
            loginThrough(link.getAttribute("href"), app, window);
        });
        // Bubbling, so jwt-select.html's own handler has filled the code in first.
        document.getElementById("mock-form")?.addEventListener("submit", (event) => {
            event.preventDefault();
            const code = document.getElementById("code-field").value;
            loginThrough(`/auth/callback?code=${encodeURIComponent(code)}`, app, window);
        });
        // The button that submitted, for its formaction ("add a binding"): mdui-buttons aren't
        // reliably the submit event's submitter.
        document.addEventListener("click", (event) => {
            submitter = event.target.closest?.('[type="submit"]') ?? null;
        }, true);
        wireForms(app);
    }

    let submitter = null;

    /** The config's forms, wired again on every page swapped in. */
    function wireForms(app) {
        for (const form of document.querySelectorAll("#mock-admin-form, #mock-twitchat-form")) {
            form.addEventListener("submit", (event) => {
                event.preventDefault();
                const action = submitter?.closest("form") === form ? submitter.getAttribute("formaction") : null;
                submitter = null;
                postInPlace(form, action ?? form.getAttribute("action"), app);
            });
        }
    }

    /**
     * Posts `form` to `action`, swapping its answer into the popout. The app's page re-renders
     * only when the config changed something no event carries to it (MockAdminController's appStale).
     */
    async function postInPlace(form, action, app) {
        let response;
        try {
            response = await fetch(action, {
                method: "POST",
                credentials: "same-origin",
                body: new URLSearchParams(new FormData(form)),
            });
        } catch (error) {
            console.warn("[mock] saving failed", error);
            return;
        }
        const landed = response.url ? new URL(response.url).pathname : "/mock/admin";
        if (!response.ok || landed !== "/mock/admin") {
            location.href = response.url || "/mock/admin"; // logged out, or failed: the plain page says why
            return;
        }
        const page = new DOMParser().parseFromString(await response.text(), "text/html");
        const main = document.adoptNode(page.querySelector("main"));
        const scroll = window.scrollY;
        document.querySelector("main").replaceWith(main);
        window.scrollTo(0, scroll);
        wireForms(app);
        // The app's page follows the rest through its events.
        if (main.dataset.appStale === "true") app.CatapultChannel?.refresh();
    }

    if (script?.dataset.role === "popout") wirePopout();
    else wireApp();

    return { open };
})();
