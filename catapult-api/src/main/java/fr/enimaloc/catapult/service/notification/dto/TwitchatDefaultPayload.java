package fr.enimaloc.catapult.service.notification.dto;

import fr.enimaloc.catapult.domain.TwitchatActionType;

import java.util.Map;

public record TwitchatDefaultPayload(String message, String style, String icon, String authorName,
                                      Map<TwitchatActionType, TwitchatActionDefault> actions) {
}
