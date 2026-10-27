window.CatapultCsrf = (function () {
    function readCookie(name) {
        const match = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
        return match ? decodeURIComponent(match[1]) : null;
    }

    // Whatever it changed, the pages spa.js kept may show it as it was: they go once it is done.
    async function postJson(url, body) {
        return fetch(url, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-XSRF-TOKEN": readCookie("XSRF-TOKEN") ?? ""
            },
            body: JSON.stringify(body ?? {})
        }).finally(() => window.CatapultSpa?.forget());
    }

    return { postJson, readCookie };
})();
