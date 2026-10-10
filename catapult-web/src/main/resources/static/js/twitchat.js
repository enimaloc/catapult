/**
 * Relays Catapult's Twitchat notifications to Twitchat, through the page's OBS connection
 * (obs.js): Twitchat listens on obs-websocket for "twitchat"-origin custom events. Each
 * notification arrives as a "catapult:twitchat:notify" event on document, its detail being the
 * TwitchatNotification ({ id, message, style, icon, authorName, actions }).
 *
 * Also asks Twitchat for its triggers on every OBS connection — the only way Twitchat exposes
 * them — and keeps the answer, announced as "catapult:twitchat:triggers" ({ triggers }).
 */
window.CatapultTwitchat = (function () {
    // Shared by every client of the same obs-websocket server (this page, the old widget page,
    // a second tab…), the only state they all see: a client inside OBS's embedded browser
    // shares no storage with a regular tab.
    const DEDUP_REALM = "OBS_WEBSOCKET_DATA_REALM_GLOBAL";
    const DEDUP_SLOT = "catapult_twitchat_relay_dedup";
    const MAX_JITTER_MS = 150;

    let triggers = [];

    function log(message, ...args) {
        console.log(`[twitchat] ${message}`, ...args);
    }

    function broadcast(type, data) {
        return CatapultObs.requests.broadcastCustomEvent({ eventData: { origin: "twitchat", type, data } });
    }

    /**
     * Whether this client should relay the notification: false when another one already
     * claimed it. The random jitter narrows (but can't close) the race between two clients
     * checking at once. If OBS fails to answer, relays anyway: a duplicate beats a lost one.
     */
    async function claim(notificationId) {
        await new Promise((resolve) => setTimeout(resolve, Math.floor(Math.random() * MAX_JITTER_MS)));
        try {
            const { slotValue } = await CatapultObs.requests.getPersistentData({ realm: DEDUP_REALM, slotName: DEDUP_SLOT });
            if (slotValue === notificationId) return false;
            await CatapultObs.requests.setPersistentData({ realm: DEDUP_REALM, slotName: DEDUP_SLOT, slotValue: notificationId });
        } catch (error) {
            log("relay coordination failed, relaying anyway", error);
        }
        return true;
    }

    /**
     * Sends the notification to Twitchat as a chat message. Resolves with whether it was sent:
     * not while OBS is disconnected (dropped rather than queued: a late notification is stale),
     * nor when another client relayed it already.
     */
    async function relay(notification) {
        if (!CatapultObs.isConnected()) {
            log("OBS not connected, notification dropped:", notification.message);
            return false;
        }
        if (!await claim(notification.id)) {
            log("already relayed by another client:", notification.message);
            return false;
        }
        try {
            await broadcast("CUSTOM_CHAT_MESSAGE", {
                message: notification.message,
                style: notification.style,
                icon: notification.icon,
                user: notification.authorName ? { name: notification.authorName } : undefined,
                actions: (notification.actions ?? []).map(({ label, actionType, url, message, theme }) =>
                    ({ label, actionType, url, message, theme })),
            });
            log("relayed:", notification.message);
            return true;
        } catch (error) {
            log("relay failed", error);
            return false;
        }
    }

    /** Asks Twitchat for its triggers; the answer comes back as a TRIGGER_LIST custom event. */
    function requestTriggers() {
        if (!CatapultObs.isConnected()) return Promise.resolve();
        return broadcast("TRIGGERS_GET_ALL", {}).catch((error) => log("trigger list request failed", error));
    }

    CatapultObs.on("CustomEvent", (eventData) => {
        if (eventData?.origin !== "twitchat" || eventData.type !== "TRIGGER_LIST") return;
        triggers = eventData.data?.triggers ?? [];
        log(`${triggers.length} trigger(s):`, triggers.map((trigger) => trigger.name));
        document.dispatchEvent(new CustomEvent("catapult:twitchat:triggers", { detail: { triggers } }));
    });

    document.addEventListener("catapult:obs:connected", () => requestTriggers());
    document.addEventListener("catapult:obs:disconnected", () => { triggers = []; });
    document.addEventListener("catapult:twitchat:notify", (event) => relay(event.detail));

    return {
        relay,
        requestTriggers,
        /** Twitchat's triggers ({ id, name }) as of its last answer, [] while OBS is disconnected. */
        triggers: () => triggers,
    };
})();
