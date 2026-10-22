/*
 * The "also receive by" switches of the /notifications page: each change sends the whole chosen
 * set (the site is always on) and puts the switch back when catapult-api did not take it.
 * Bound on each render, as the page comes and goes with the SPA router.
 */
(function () {
    function chosen(card) {
        return Array.from(card.querySelectorAll("mdui-switch[data-channel]")).filter(sw => sw.checked).map(sw => sw.dataset.channel);
    }

    /** One request at a time: two quick toggles must not reach catapult-api out of order and keep the older set. */
    let sending = Promise.resolve();

    function bind() {
        const card = document.getElementById("notification-channels");
        if (!card) return;
        card.querySelectorAll("mdui-switch[data-channel]").forEach(sw => {
            if (sw.dataset.bound) return;
            sw.dataset.bound = "true";
            sw.addEventListener("change", () => {
                // The set is read when its turn comes, so the last request always carries the final state.
                sending = sending.then(() =>
                    CatapultChannel.postSwitch(sw, "/spa/notifications/channels", { channels: chosen(card) }));
            });
        });
    }

    document.addEventListener("catapult:render", bind);
    bind();
})();
