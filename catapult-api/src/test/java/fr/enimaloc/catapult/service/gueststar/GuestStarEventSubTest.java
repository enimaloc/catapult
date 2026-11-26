package fr.enimaloc.catapult.service.gueststar;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuestStarEventSubTest {

    private final GuestStarRoster roster = mock(GuestStarRoster.class);
    private final GuestStarConsent consent = mock(GuestStarConsent.class);
    private final StreamTitleService titles = mock(StreamTitleService.class);
    private final GuestStarSessionClient client = mock(GuestStarSessionClient.class);
    private final GuestStarEventSub eventSub = new GuestStarEventSub(roster, consent, titles, client);
    private final UserAccount user = new UserAccount();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        user.setTwitchId("42");
    }

    private void follows(boolean experiment, boolean scope) {
        when(titles.guestStarsAvailableReadOnly(user)).thenReturn(experiment);
        when(consent.hasScope(user)).thenReturn(scope);
    }

    @Test
    void subscriptionsWhenRolledOutWithScope() {
        follows(true, true);
        assertThat(eventSub.subscriptionsFor(user)).extracting(GuestStarEventSub.Spec::type).containsExactly(
                "channel.guest_star_session.begin", "channel.guest_star_session.end",
                "channel.guest_star_guest.update");
        assertThat(eventSub.subscriptionsFor(user)).allSatisfy(spec -> {
            assertThat(spec.version()).isEqualTo("beta");
            assertThat(spec.condition()).isEqualTo(Map.of("broadcaster_user_id", "42", "moderator_user_id", "42"));
        });
    }

    @Test
    void noSubscriptionWithoutScopeOrOutsideExperiment() {
        follows(true, false);
        assertThat(eventSub.subscriptionsFor(user)).isEmpty();
        follows(false, true);
        assertThat(eventSub.subscriptionsFor(user)).isEmpty();
    }

    @Test
    void guestUpdateFeedsRoster() throws Exception {
        eventSub.onNotification(user, "channel.guest_star_guest.update", json.readTree("""
                {"slot_id": "1", "guest_user_id": "10", "guest_user_name": "Alice", "state": "live"}"""));
        verify(roster).onGuestUpdate(user, "1", "10", "Alice", "live");

        eventSub.onNotification(user, "channel.guest_star_guest.update", json.readTree("""
                {"slot_id": null, "guest_user_id": "10", "guest_user_name": "Alice", "state": "removed"}"""));
        verify(roster).onGuestUpdate(user, null, "10", "Alice", "removed");
    }

    @Test
    void sessionBeginAndEndClearRoster() throws Exception {
        eventSub.onNotification(user, "channel.guest_star_session.end", json.readTree("{}"));
        eventSub.onNotification(user, "channel.guest_star_session.begin", json.readTree("{}"));
        verify(roster, org.mockito.Mockito.times(2)).clear(user);
    }

    @Test
    void resyncReplacesTheRosterWhenFollowed() {
        follows(true, true);
        List<GuestStarRoster.Guest> live = List.of(new GuestStarRoster.Guest("1", "10", "Alice"));
        when(client.liveGuests(user)).thenReturn(live);
        eventSub.resync(user);
        verify(roster).replace(user, live);
    }

    @Test
    void resyncClearsWhenNotFollowed() {
        follows(true, false);
        eventSub.resync(user);
        verify(roster).clear(user);
        verifyNoInteractions(client);
        verify(roster, never()).replace(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void handlesOnlyItsTypes() {
        assertThat(eventSub.handles("channel.guest_star_guest.update")).isTrue();
        assertThat(eventSub.handles("channel.update")).isFalse();
    }

    @Test
    void aRevokedGuestStarSubscriptionEmptiesTheRoster() {
        eventSub.onRevocation(user, "channel.guest_star_guest.update");
        verify(roster).clear(user);
        eventSub.onRevocation(user, "channel.update");
        verify(roster, org.mockito.Mockito.times(1)).clear(user);
    }
}
