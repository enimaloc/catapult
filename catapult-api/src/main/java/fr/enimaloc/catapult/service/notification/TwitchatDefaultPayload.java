package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;

import java.util.Map;

/** One notification of {@link TwitchatDefaultPayloads}: its chat message and the label and theme of each button. */
public record TwitchatDefaultPayload(String message, String style, String icon, String authorName,
                                      Map<TwitchatActionType, TwitchatActionDefault> actions) {
}
