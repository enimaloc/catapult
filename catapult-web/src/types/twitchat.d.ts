// IDE typings of static/js/twitchat.js; never served.

/** catapult-common's TwitchatAction. */
interface TwitchatAction {
    label: string;
    actionType: string;
    url?: string | null;
    message?: string | null;
    theme?: string | null;
}

/** catapult-common's TwitchatNotification. */
interface TwitchatNotification {
    /** Shared by every client relaying it, so only one of them does. */
    id: string;
    message: string;
    style?: string | null;
    icon?: string | null;
    authorName?: string | null;
    actions?: TwitchatAction[] | null;
}

interface TwitchatTrigger {
    id: string;
    name: string;
}

interface CatapultTwitchatApi {
    /** Sends the notification to Twitchat; resolves with whether this client sent it. */
    relay(notification: TwitchatNotification): Promise<boolean>;
    /** Asks Twitchat for its triggers; the answer fires "catapult:twitchat:triggers". */
    requestTriggers(): Promise<void>;
    /** Twitchat's triggers as of its last answer, [] while OBS is disconnected. */
    triggers(): TwitchatTrigger[];
}

interface DocumentEventMap {
    /** A notification for twitchat.js to relay. */
    "catapult:twitchat:notify": CustomEvent<TwitchatNotification>;
    /** Twitchat answered with its triggers. */
    "catapult:twitchat:triggers": CustomEvent<{ triggers: TwitchatTrigger[] }>;
}

declare var CatapultTwitchat: CatapultTwitchatApi;
