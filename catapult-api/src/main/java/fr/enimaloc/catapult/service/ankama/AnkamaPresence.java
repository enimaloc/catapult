package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.service.ankama.chat.ChatMessages.EndpointProperties;

import java.util.List;
import java.util.OptionalInt;

import static fr.enimaloc.catapult.service.ankama.chat.ChatMessages.PRESENCE_OFFLINE;

/** Decides which Ankama game a user is playing from the endpoints the launcher chat reports. */
public final class AnkamaPresence {

    private AnkamaPresence() {
    }

    /**
     * The game in progress: the first endpoint that is not the chat or the launcher. The list replaces the previous
     * one on each update, and an offline presence means no game whatever the endpoints say.
     */
    public static OptionalInt currentGame(List<EndpointProperties> endpoints, int presence) {
        if (presence == PRESENCE_OFFLINE) {
            return OptionalInt.empty();
        }
        return endpoints.stream()
                .mapToInt(EndpointProperties::applicationId)
                .filter(id -> !AnkamaTitles.IGNORED.contains(id))
                .findFirst();
    }
}
