const app = document.querySelector("#app");

const config = window.Catapult;

const routes = Object.fromEntries(
    config.spa.map(route => [
        route.id,
        route
    ])
);

async function navigate(path, push = true) {
    const route = routes[path];

    if (!route) {
        window.location.href = path;
        return;
    }

    const response = await fetch(route.templateUrl);

    if (!response.ok) {
        window.location.href = path;
        return;
    }

    app.innerHTML = await response.text();

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