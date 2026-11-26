package fr.enimaloc.catapult.service.gueststar;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.GuestStarsChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GuestStarRosterTest {

    private ApplicationEventPublisher publisher;
    private GuestStarRoster roster;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        roster = new GuestStarRoster(publisher);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void liveGuestAdded() {
        roster.onGuestUpdate(user, "1", "10", "Alice", "live");
        assertThat(roster.names(user.getId())).contains("Alice");
    }

    @Test
    void hostAndScreenshareIgnored() {
        roster.onGuestUpdate(user, "0", "1", "Host", "live");
        roster.onGuestUpdate(user, "SCREENSHARE", "11", "Screen", "live");
        assertThat(roster.names(user.getId())).isEmpty();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void sortedBySlot() {
        roster.onGuestUpdate(user, "2", "20", "Bob", "live");
        roster.onGuestUpdate(user, "10", "30", "Carol", "live");
        roster.onGuestUpdate(user, "1", "10", "Alice", "live");
        assertThat(roster.names(user.getId())).contains("Alice, Bob, Carol");
    }

    @Test
    void nonLiveStateRemoves() {
        roster.onGuestUpdate(user, "1", "10", "Alice", "live");
        roster.onGuestUpdate(user, "1", "10", "Alice", "backstage");
        assertThat(roster.names(user.getId())).isEmpty();

        roster.onGuestUpdate(user, "1", "10", "Alice", "live");
        // removed: no slot id
        roster.onGuestUpdate(user, null, "10", "Alice", "removed");
        assertThat(roster.names(user.getId())).isEmpty();
    }

    @Test
    void emptySlotRemovesItsGuest() {
        roster.onGuestUpdate(user, "1", "10", "Alice", "live");
        roster.onGuestUpdate(user, "1", null, null, null);
        assertThat(roster.names(user.getId())).isEmpty();
    }

    @Test
    void eventOnlyOnChange() {
        roster.onGuestUpdate(user, "1", "10", "Alice", "live");
        roster.onGuestUpdate(user, "1", "10", "Alice", "live");
        roster.onGuestUpdate(user, null, "99", "Nobody", "invited");
        verify(publisher, times(1)).publishEvent(any(GuestStarsChangedEvent.class));
    }

    @Test
    void clearPublishesWhenNonEmpty() {
        roster.clear(user);
        verify(publisher, never()).publishEvent(any(Object.class));
        roster.onGuestUpdate(user, "1", "10", "Alice", "live");
        roster.clear(user);
        assertThat(roster.names(user.getId())).isEmpty();
        verify(publisher, times(2)).publishEvent(any(GuestStarsChangedEvent.class));
    }

    @Test
    void replaceFiltersTheHostAndScreenshare() {
        roster.replace(user, List.of(new GuestStarRoster.Guest("0", "1", "Host"),
                new GuestStarRoster.Guest("2", "20", "Bob"), new GuestStarRoster.Guest("SCREENSHARE", "11", "S"),
                new GuestStarRoster.Guest("1", "10", "Alice")));
        assertThat(roster.names(user.getId())).contains("Alice, Bob");
    }
}
