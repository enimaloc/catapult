package fr.enimaloc.catapult.web;

import java.util.List;

/** Fixed display order for the 5 Twitchat notification event types across the settings UI. */
public final class TwitchatEventTypes {
    public static final List<String> ALL = List.of(
            "CATEGORY_CHANGED_BY_CATAPULT", "CATEGORY_CHANGED_MANUALLY", "STREAM_STARTED",
            "BOT_ENABLED", "BOT_DISABLED");

    private TwitchatEventTypes() {}
}
