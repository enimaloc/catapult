package fr.enimaloc.catapult.service.gueststar;

import com.fasterxml.jackson.databind.JsonNode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * What TwitchEventSubService does for Guest Star: the three beta subscriptions of a channel followed (in experiment
 * {@value StreamTitleService#GUEST_STARS_KEY} and with the consent), their notifications into the roster, and the
 * roster read again from Helix when the connection opens or the stream goes live.
 */
@Component
@RequiredArgsConstructor
public class GuestStarEventSub {

    /** One subscription to make: its type, version and condition. */
    public record Spec(String type, String version, Map<String, String> condition) {}

    static final String SESSION_BEGIN = "channel.guest_star_session.begin";
    static final String SESSION_END = "channel.guest_star_session.end";
    static final String GUEST_UPDATE = "channel.guest_star_guest.update";
    private static final List<String> TYPES = List.of(SESSION_BEGIN, SESSION_END, GUEST_UPDATE);

    private final GuestStarRoster roster;
    private final GuestStarConsent consent;
    private final StreamTitleService streamTitleService;
    private final GuestStarSessionClient sessionClient;

    /** Whether {@code user}'s guests are followed: in the experiment, and the consent given. */
    public boolean follows(UserAccount user) {
        return streamTitleService.guestStarsAvailableReadOnly(user) && consent.hasScope(user);
    }

    /** The subscriptions to make for {@code user}: none while not followed. */
    public List<Spec> subscriptionsFor(UserAccount user) {
        if (!follows(user)) return List.of();
        Map<String, String> condition = Map.of("broadcaster_user_id", user.getTwitchId(),
                "moderator_user_id", user.getTwitchId());
        return TYPES.stream().map(type -> new Spec(type, "beta", condition)).toList();
    }

    public boolean handles(String subscriptionType) {
        return TYPES.contains(subscriptionType);
    }

    /** A notification of one of {@link #handles its types}, {@code event} its {@code payload.event}. */
    public void onNotification(UserAccount user, String subscriptionType, JsonNode event) {
        if (GUEST_UPDATE.equals(subscriptionType)) {
            roster.onGuestUpdate(user, text(event, "slot_id"), text(event, "guest_user_id"),
                    text(event, "guest_user_name"), text(event, "state"));
        } else if (handles(subscriptionType)) {
            roster.clear(user);
        }
    }

    /** Twitch revoked a subscription: one of Guest Star's leaves its guests unknown, none is shown any more. */
    public void onRevocation(UserAccount user, String subscriptionType) {
        if (handles(subscriptionType)) roster.clear(user);
    }

    /** The roster read again from Helix, or emptied while the channel isn't followed. */
    public void resync(UserAccount user) {
        if (!follows(user)) {
            roster.clear(user);
            return;
        }
        roster.replace(user, sessionClient.liveGuests(user));
    }

    private static String text(JsonNode event, String field) {
        JsonNode value = event.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
