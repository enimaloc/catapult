package fr.enimaloc.catapult.common.dto.twitchat;

import java.util.List;
import java.util.Map;

/** The Catapult events Twitchat payloads hang off, as the web shows them, and what each can use. */
public final class TwitchatEvents {

    public static final List<String> ALL = List.of("CATEGORY_CHANGED_BY_CATAPULT", "CATEGORY_CHANGED_MANUALLY",
            "STREAM_STARTED", "BOT_ENABLED", "BOT_DISABLED", "NO_GAME_DETECTED", "NOTIFICATION");

    /** The Catapult actions a button can trigger on each event (when they apply at that moment). */
    public static final Map<String, List<TwitchatActionType>> ACTIONS = Map.of(
            "CATEGORY_CHANGED_BY_CATAPULT", List.of(TwitchatActionType.REVERT_CATEGORY, TwitchatActionType.DISABLE_BOT),
            "CATEGORY_CHANGED_MANUALLY", List.of(TwitchatActionType.BIND_GAME_CATEGORY, TwitchatActionType.REVERT_TO_APP_CATEGORY),
            "STREAM_STARTED", List.of(TwitchatActionType.DISABLE_BOT),
            "BOT_ENABLED", List.of(TwitchatActionType.DISABLE_BOT),
            "BOT_DISABLED", List.of(TwitchatActionType.ENABLE_BOT),
            "NO_GAME_DETECTED", List.of(),
            "NOTIFICATION", List.of());

    /** The {{variables}} each event fills, besides baseUrl and channel. */
    public static final Map<String, List<String>> VARIABLES = Map.of(
            "CATEGORY_CHANGED_BY_CATAPULT", List.of("gameName", "previousGameName"),
            "CATEGORY_CHANGED_MANUALLY", List.of("gameName", "previousGameName"),
            "STREAM_STARTED", List.of(), "BOT_ENABLED", List.of(), "BOT_DISABLED", List.of(), "NO_GAME_DETECTED", List.of(),
            "NOTIFICATION", List.of("title", "body", "ctaUrl", "ctaLabel"));

    private TwitchatEvents() {
    }
}
