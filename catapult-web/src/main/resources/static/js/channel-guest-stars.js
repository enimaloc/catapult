/**
 * The Guest Star box of the title card (Settings tab), offered while the channel is in the stream.title.guest_stars
 * experiment. Its button leaves for catapult-api's Guest Star login (a Twitch consent of its own); catapult-api sends
 * the member back to this tab with ?guest_star=ok or ?error=guest_star_<why>, told once here: the code then leaves
 * the address, so a reload doesn't repeat it.
 */
(function () {
    const LOGIN = "/oauth2/authorization/twitch-guest-star";
    // ?error= code → the box's label (data-label-*) and its fallback; cancelled is not a failure.
    const ERRORS = {
        guest_star_failed: ["labelFailed", "The Guest Star authorization failed: try again later."],
        guest_star_mismatch: ["labelMismatch", "This is not your channel's Twitch account: authorize with the same account."],
        guest_star_cancelled: ["labelCancelled", "Guest Star authorization cancelled."],
    };

    function bind() {
        const box = document.getElementById("guest-stars-consent");
        if (!box || box.dataset.bound) return;
        box.dataset.bound = "1";
        document.getElementById("guest-stars-authorize-btn")
            ?.addEventListener("click", () => location.assign(LOGIN));
        tell(box);
    }

    function tell(box) {
        const url = new URL(location.href);
        const label = (name, fallback) => box.dataset[name] ?? fallback;
        const code = url.searchParams.get("error");
        if (Object.hasOwn(ERRORS, code)) {
            url.searchParams.delete("error");
            history.replaceState(history.state, "", url);
            const show = code === "guest_star_cancelled" ? CatapultChannel.showMessage : CatapultChannel.showError;
            show(label(...ERRORS[code]));
        } else if (url.searchParams.get("guest_star") === "ok") {
            url.searchParams.delete("guest_star");
            history.replaceState(history.state, "", url);
            CatapultChannel.showMessage(label("labelDone", "Guest Star authorization given."));
        }
    }

    // The Settings tab is built only once picked (lazy.js): the box is bound then, as with the page otherwise.
    document.addEventListener("catapult:render", bind);
    document.addEventListener("catapult:lazy", bind);
})();
