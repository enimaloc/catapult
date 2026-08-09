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

        function onNotifyFrame(frame) {
            if (frame.name === "twitchat.notify") {
                relay(frame.data);
            }
        }

        function subscribe() {
            if (subscribed) {
                return;
            }
            subscribed = true;
            window.catapultWs.subscribe("twitchat.widget." + widgetToken, onNotifyFrame);
        }

        if (window.catapultWs) {
            document.addEventListener("ws:auth.ok", subscribe, { once: true });
            // twitchat.widget.<token> requires no session; subscribe immediately too in
            // case ws:auth.ok never fires here (e.g. an OBS browser source with no cookies).
            subscribe();
        }
    }).catch((err) => console.error("[twitchat-relay] OBS-websocket connection failed", err));
})();
