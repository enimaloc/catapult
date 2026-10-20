package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.http.HttpSession;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

/**
 * The channels a logged-in session can act on (its member's own and those they moderate), as
 * catapult-api last listed them: what its navbar shows, and what its streams may follow. Kept in
 * the session, so it goes with it; as JSON text, the session being stored in Redis for every
 * catapult-web instance to read.
 */
public final class SessionChannels {
    static final String ATTRIBUTE = SessionChannels.class.getName();

    private SessionChannels() {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<ChannelDto>> CHANNELS = new TypeReference<>() {};

    public static void remember(HttpSession session, List<ChannelDto> channels) {
        session.setAttribute(ATTRIBUTE, JSON.writeValueAsString(channels));
    }

    /** Forgets what {@code session} listed: it now acts as another account, whose list is to be read. */
    public static void forget(HttpSession session) {
        session.removeAttribute(ATTRIBUTE);
    }

    /** What {@code session} last listed; none yet. Throws IllegalStateException once it is gone. */
    static List<ChannelDto> of(HttpSession session) {
        if (!(session.getAttribute(ATTRIBUTE) instanceof String channels)) {
            return List.of();
        }
        try {
            return List.copyOf(JSON.readValue(channels, CHANNELS));
        } catch (RuntimeException e) {
            return List.of(); // written by another version: listed again on the next page
        }
    }

    /**
     * Whether {@code session} may follow {@code username}: from what it last listed, else (a first
     * stream, or a list grown since) from catapult-api, whose answer it then keeps. Called within
     * the session's request, which carries its JWT to the API.
     */
    static boolean canFollow(HttpSession session, String username, ApiService api) {
        if (lists(of(session), username)) {
            return true;
        }
        ChannelListResponse fresh = api.channelList();
        if (fresh == null) {
            return false;
        }
        remember(session, fresh.channels());
        return lists(fresh.channels(), username);
    }

    /**
     * The channel named {@code username} among those {@code session} last listed; empty when it
     * lists none such, or once the session is gone.
     */
    public static Optional<ChannelDto> find(HttpSession session, String username) {
        try {
            return of(session).stream().filter(channel -> channel.twitchUsername().equalsIgnoreCase(username)).findFirst();
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    private static boolean lists(List<ChannelDto> channels, String username) {
        return channels.stream().anyMatch(channel -> channel.twitchUsername().equalsIgnoreCase(username));
    }
}
