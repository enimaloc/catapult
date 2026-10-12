// catapult-web/src/main/resources/static/js/mock-popout.js
/**
 * Mock profile only. The member's config (/mock/admin) lives in a popout window next to the app.
 *
 * In the app (index.html): the "Mock" button opens the popout.
 *
 * In the popout (the mock pages, data-role "popout"): picking an account logs the app in without
 * leaving the popout, and saving the config reloads the app to show it.
 */
window.CatapultMockPopout = (function () {
    const NAME = "catapult-mock";
    const FEATURES = "popup,width=780,height=900";
    const SAVED = "catapult-mock-saved";

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
        document.getElementById("mock-admin-form")?.addEventListener("submit", () => {
            try { sessionStorage.setItem(SAVED, "1"); } catch { /* no storage: the app just won't reload */ }
        });
        let saved = null;
        try {
            saved = sessionStorage.getItem(SAVED);
            sessionStorage.removeItem(SAVED);
        } catch { /* no storage */ }
        if (saved) app.location.reload();
    }

    if (script?.dataset.role === "popout") wirePopout();
    else wireApp();

    return { open };
})();
