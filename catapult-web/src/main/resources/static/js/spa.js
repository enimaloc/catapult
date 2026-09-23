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

async function navigate(path, push = true) {
    const route = routes[path];
    const templateUrl = route ? route.templateUrl : `/spa/${path}`;

    progress.hidden = false;

    let response;
    try {
        response = await fetch(templateUrl);
    } catch {
        // Network failure (offline, timeout, ...): fall back to a full
        // navigation so the browser's own error handling takes over.
        window.location.href = path;
        return;
    } finally {
        progress.hidden = true;
    }

    const html = await response.text();
    app.innerHTML = html;

    if (push) {
        history.pushState(
            {
                spa: true,
                path
            },
            "",
            path === "" ? "/" : `/${path}`
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
    navigate(url.pathname.replace(/^\/+|\/+$/g, ""));
});

window.addEventListener("popstate", () => {
    const pathname = window.location.pathname.replace(/^\/+|\/+$/g, "");
    navigate(pathname, false);
});