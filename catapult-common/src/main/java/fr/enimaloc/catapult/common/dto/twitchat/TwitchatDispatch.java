package fr.enimaloc.catapult.common.dto.twitchat;

import java.util.List;
import java.util.UUID;

/**
 * Every payload one Catapult event sends to Twitchat, relayed to the owner's pages, whose
 * twitchat.js sends them in order. {@code id} is shared by every page receiving it so only
 * one of them sends it; {@code channel} is the owner's Twitch username.
 */
public record TwitchatDispatch(String id, String channel, String eventType, List<TwitchatPayload> payloads) {

    public TwitchatDispatch(String channel, String eventType, List<TwitchatPayload> payloads) {
        this(UUID.randomUUID().toString(), channel, eventType, payloads);
    }
}
