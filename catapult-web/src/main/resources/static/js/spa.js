const app = document.querySelector("#app");
const progress = document.querySelector("#nav-progress");

const config = window.Catapult;

const routes = Object.fromEntries(
    config.spa.map(route => [
        route.id,
        route
    ])
);

function updateActiveLink(path) {
    document.querySelectorAll("a[data-link]").forEach(a => {
        const href = new URL(a.href).pathname.replace(/^\/+|\/+$/g, "");
        a.classList.toggle("active", href === path);
    });
}

// Static pages match `path` against a route id exactly. A `dynamic` route (e.g.
// "channel") additionally matches "<id>/<param>" — the param is appended as its
// own path segment to templateUrl, mirroring the server's own
// /spa/<id>/{param} mapping.
function resolveRoute(path) {
    if (routes[path]) {
        return { route: routes[path], param: null };
    }
    const segments = path.split("/");
    if (segments.length === 2) {
        const base = routes[segments[0]];
        if (base && base.dynamic && segments[1]) {
            return { route: base, param: segments[1] };
        }
    }
    return { route: undefined, param: null };
}

async function navigate(path, push = true, search = "") {
    const { route, param } = resolveRoute(path);
    const templateUrl = route
        ? (param ? `${route.templateUrl}/${param}` : route.templateUrl)
        : `/spa/${path}`;

    progress.hidden = false;

    let response;
    try {
        response = await fetch(templateUrl + search);
    } catch {
        // Network failure (offline, timeout, ...): fall back to a full
        // navigation so the browser's own error handling takes over.
        window.location.href = path + search;
        return;
    } finally {
        progress.hidden = true;
    }

    const html = await response.text();
    app.innerHTML = html;
    document.dispatchEvent(new CustomEvent("catapult:render"));

    if (push) {
        history.pushState(
            {
                spa: true,
                path
            },
            "",
            (path === "" ? "/" : `/${path}`) + search
        );
    }

    if (!response.ok) {
        // Server-side error (unknown page or fragment failure): the
        // response body is the error fragment (see ErrorPageController
        // and IndexController.SPAPages#unknown), render it in place
        // instead of reloading the whole page.
        return;
    }

    document.title = config.titles[route.id] ?? document.title;
    updateActiveLink(path);

    window.scrollTo({
        top: 0,
        behavior: "instant"
    });
}

updateActiveLink(config.page);

document.addEventListener("click", event => {
    // composedPath() (rather than event.target) is required here: for a
    // click inside a web component's shadow DOM (e.g. <mdui-button href>),
    // event.target is retargeted to the component itself, which isn't an
    // <a> and wouldn't match a plain `a[data-link]` selector.
    const el = event.composedPath().find(node => node instanceof Element && node.hasAttribute("data-link"));
    const href = el?.getAttribute("href");
    if (!href) {
        return;
    }

    const url = new URL(href, window.location.href);
    if (url.origin !== window.location.origin) {
        return;
    }

    event.preventDefault();
    navigate(url.pathname.replace(/^\/+|\/+$/g, ""), true, url.search);
});

window.addEventListener("popstate", () => {
    const pathname = window.location.pathname.replace(/^\/+|\/+$/g, "");
    navigate(pathname, false, window.location.search);
});

// spa.js is loaded without defer/async, before the per-fragment scripts that follow it
// in index.html (channel-status.js and friends) — those haven't registered their
// "catapult:render" listeners yet when this line runs, so the dispatch must wait until
// the whole document (all script tags included) has finished parsing, not just until
// spa.js itself has executed. DOMContentLoaded fires after every script tag has run.
document.addEventListener("DOMContentLoaded", () => {
    document.dispatchEvent(new CustomEvent("catapult:render"));
});