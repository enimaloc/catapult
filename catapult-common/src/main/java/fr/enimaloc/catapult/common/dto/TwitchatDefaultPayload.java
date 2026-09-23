package fr.enimaloc.catapult.common.dto;

import java.util.Map;

public record TwitchatDefaultPayload(String message, String style, String icon, String authorName,
                                      Map<TwitchatActionType, TwitchatActionDefault> actions) {
}
