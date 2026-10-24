/*
 * The "also receive by" switches, on the /notifications page and in the owner's channel Settings tab:
 * each change sends the whole set chosen at that moment (the site is always on), one request at a time
 * so that none reaches catapult-api out
 * of order. The switches show what the server has again when a request fails and none is left waiting.
 * Bound on each render, as the page comes and goes with the SPA router.
 */
(function () {
    const URL = "/spa/notifications/channels";
    const switches = card => Array.from(card.querySelectorAll("mdui-switch[data-channel]"));
    const chosen = card => switches(card).filter(sw => sw.checked).map(sw => sw.dataset.channel);

    let sending = Promise.resolve();
    let waiting = 0;
    /** What catapult-api has, as far as known: the page's, then each request it took. */
    let confirmed = null;

    function submit(card, wanted) {
        waiting++;
        sending = sending.then(async () => {
            const response = await CatapultChannel.postJson(URL, { channels: wanted });
            waiting--;
            if (response.ok) {
                confirmed = wanted;
            } else if (waiting === 0) {
                switches(card).forEach(sw => { sw.checked = confirmed.includes(sw.dataset.channel); });
            }
        });
    }

    function bind() {
        const card = document.getElementById("notification-channels");
        if (!card) return;
        confirmed = chosen(card);
        switches(card).forEach(sw => {
            if (sw.dataset.bound) return;
            sw.dataset.bound = "true";
            // The set is captured now, at the change: a later one is not this one's business.
            sw.addEventListener("change", () => submit(card, chosen(card)));
        });
    }

    document.addEventListener("catapult:render", bind);
    bind();
})();
