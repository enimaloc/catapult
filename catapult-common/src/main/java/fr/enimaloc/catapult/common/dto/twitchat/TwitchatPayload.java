package fr.enimaloc.catapult.common.dto.twitchat;

import java.util.Map;

/** One Twitchat action as sent: its catalog key (named per protocol by the browser) and its data. */
public record TwitchatPayload(String action, Map<String, Object> data) {
}
