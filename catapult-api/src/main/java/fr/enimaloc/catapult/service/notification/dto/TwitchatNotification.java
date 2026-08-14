package fr.enimaloc.catapult.service.notification.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code id} is a fresh random value per notification, carried through to every relaying
 * client (widget page, settings-page fallback) so they can coordinate via OBS-websocket's
 * persistent data and avoid relaying the same notification to Twitchat twice when more than
 * one client is connected at once — see obs-source-relay coordination in twitchat-relay.js /
 * twitchat-settings.html.
 */
public record TwitchatNotification(String id, String message, String style, String icon, String authorName,
                                    List<TwitchatAction> actions) {

    public TwitchatNotification(String message, String style, String icon, String authorName,
                                 List<TwitchatAction> actions) {
        this(UUID.randomUUID().toString(), message, style, icon, authorName, actions);
    }
}
