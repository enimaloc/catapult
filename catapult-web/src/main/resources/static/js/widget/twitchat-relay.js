(function () {
    "use strict";
    const body = document.body;
    const widgetToken = body.dataset.widgetToken;
    const obsHost = body.dataset.obsHost;
    const obsPort = body.dataset.obsPort;
    const obsPassword = body.dataset.obsPassword;

    if (!widgetToken || !obsHost || !obsPort) {
        return;
    }

    obsWsConnect({ host: obsHost, port: Number(obsPort), password: obsPassword }).then((obs) => {
        function relay(notification) {
            obs.call("BroadcastCustomEvent", {
                eventData: {
                    origin: "twitchat",
                    type: "CUSTOM_CHAT_MESSAGE",
                    data: {
                        message: notification.message,
                        style: notification.style,
                        actions: (notification.actions || []).map((a) => ({
                            label: a.label,
                            actionType: a.actionType,
                            url: a.url,
                            theme: a.theme
                        }))
                    }
                }
            }).catch((err) => console.error("[twitchat-relay] BroadcastCustomEvent failed", err));
        }

        let subscribed = false;

        function subscribe() {
            if (subscribed) {
                return;
            }
            subscribed = true;
            // The subscription itself is still required: it is what makes the server
            // resolve twitchat.widget.<token> to catapult:events:twitchat:<ownerId> and
            // register this session for fan-out. No callback is passed, because
            // ws-client dispatches per-channel callbacks under the *public* channel name
            // carried by the frame — and the server does not rewrite the twitchat
            // internal channel, so incoming frames arrive with channel
            // "catapult:events:twitchat:<ownerId>" and would never match this key.
            window.catapultWs.subscribe("twitchat.widget." + widgetToken);
        }

        if (window.catapultWs) {
            // ws-client re-dispatches every typed frame on document as "ws:<type>"
            // with detail = the full frame, so filter event frames by their name.
            document.addEventListener("ws:event", (e) => {
                const frame = e.detail;
                if (frame && frame.name === "twitchat.notify" && frame.data) {
                    relay(frame.data);
                }
            });
            document.addEventListener("ws:auth.ok", subscribe, { once: true });
            // twitchat.widget.<token> requires no session; subscribe immediately too in
            // case ws:auth.ok never fires here (e.g. an OBS browser source with no cookies).
            subscribe();
        }
    }).catch((err) => console.error("[twitchat-relay] OBS-websocket connection failed", err));
})();
