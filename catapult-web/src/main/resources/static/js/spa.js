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

    if (!route) {
        window.location.href = path;
        return;
    }

    progress.hidden = false;

    let response;
    try {
        response = await fetch(route.templateUrl);
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

    if (!response.ok) {
        // Server-side error: the response body is the error fragment
        // (see ErrorPageController), render it in place instead of
        // reloading the whole page.
        return;
    }

    document.title = config.titles[route.id] ?? document.title;
    updateActiveLink(path);

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

    window.scrollTo({
        top: 0,
        behavior: "instant"
    });
}

updateActiveLink(config.page);

document.addEventListener("click", event => {
    const el = event.target.closest("a[data-link]");
    if (!el) {
        return;
    }

    const url = new URL(el.href);
    if (url.origin !== window.location.origin) {
        return;
    }

    const pathname = url.pathname.replace(/^\/+|\/+$/g, "");
    const route = routes[pathname];
    if (!route) {
        console.log("No SPA route for:", pathname);
        return;
    }

    event.preventDefault();
    navigate(pathname);
});

window.addEventListener("popstate", () => {
    const pathname = window.location.pathname.replace(/^\/+|\/+$/g, "");
    navigate(pathname, false);
});