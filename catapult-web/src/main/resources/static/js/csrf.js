window.CatapultCsrf = (function () {
    function readCookie(name) {
        const match = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
        return match ? decodeURIComponent(match[1]) : null;
    }

    async function postJson(url, body) {
        return fetch(url, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-XSRF-TOKEN": readCookie("XSRF-TOKEN") ?? ""
            },
            body: JSON.stringify(body ?? {})
        });
    }

    return { postJson, readCookie };
})();
