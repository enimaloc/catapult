package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.site.SiteRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RaidRecapListenerTest {

    private static final Instant RAID = Instant.parse("2026-11-20T21:00:00Z");

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final StreamSessionService sessions = mock(StreamSessionService.class);
    private final SessionTimelineService timeline = mock(SessionTimelineService.class);
    private final TwitchatEventDispatcher dispatcher = mock(TwitchatEventDispatcher.class);
    private final SiteRole siteRole = mock(SiteRole.class);
    private final RaidRecapListener listener = new RaidRecapListener(accounts, sessions, timeline, dispatcher, siteRole);

    private UserAccount raider;
    private UserAccount target;

    @BeforeEach
    void setUp() {
        when(siteRole.isActive()).thenReturn(true);
        raider = new UserAccount();
        raider.setId(UUID.randomUUID());
        raider.setTwitchUsername("RaiderA");
        target = new UserAccount();
        target.setId(UUID.randomUUID());
        target.setTwitchUsername("TargetB");
        when(accounts.findByTwitchId("target-id")).thenReturn(Optional.of(target));
        StreamSession session = new StreamSession();
        session.setStreamId("stream-1");
        session.setStartedAt(RAID.minus(Duration.ofHours(3)));
        when(sessions.find(raider)).thenReturn(Optional.of(session));
    }

    private static StreamCategorySegment segment(String categoryId, String categoryName, String gameName,
                                                 Instant start, Instant end) {
        StreamCategorySegment segment = new StreamCategorySegment();
        segment.setCategoryId(categoryId);
        segment.setCategoryName(categoryName);
        segment.setGameName(gameName);
        segment.setStartedAt(start);
        segment.setEndedAt(end);
        return segment;
    }

    private ChannelRaidedOutEvent raid(String toBroadcasterId) {
        return new ChannelRaidedOutEvent(this, raider, "509658", "", toBroadcasterId, RAID);
    }

    private SessionRecap sentRecap() {
        ArgumentCaptor<SessionRecap> recap = ArgumentCaptor.forClass(SessionRecap.class);
        verify(dispatcher).onRaidedByMember(eq(target), eq("RaiderA"), recap.capture());
        return recap.getValue();
    }

    @Test
    void raidToAMemberSendsTheRecap() {
        when(timeline.currentSession(raider)).thenReturn(List.of(
                segment("1", "Factorio", "Factorio", RAID.minus(Duration.ofHours(3)), RAID.minus(Duration.ofMinutes(30))),
                segment("509658", "Just Chatting", null, RAID.minus(Duration.ofMinutes(30)), RAID)));

        listener.onRaid(raid("target-id"));

        SessionRecap recap = sentRecap();
        assertThat(recap.gamesText()).isEqualTo("- Factorio (Factorio) (2h30)\n- Just Chatting (30min)");
        assertThat(SessionRecap.format(recap.total())).isEqualTo("3h");
    }

    @Test
    void recapStopsAtTheRaidInstant() {
        when(timeline.currentSession(raider)).thenReturn(List.of(
                segment("1", "Factorio", "Factorio", RAID.minus(Duration.ofMinutes(45)), null)));

        listener.onRaid(raid("target-id"));

        assertThat(sentRecap().gamesText()).isEqualTo("- Factorio (Factorio) (45min)");
    }

    @Test
    void theRaiderIsNamedAsTheRaidCarriesIt() {
        when(timeline.currentSession(raider)).thenReturn(List.of(
                segment("1", "Factorio", "Factorio", RAID.minus(Duration.ofMinutes(45)), null)));

        listener.onRaid(new ChannelRaidedOutEvent(this, raider, "509658", "Raider_Display", "target-id", RAID));

        verify(dispatcher).onRaidedByMember(eq(target), eq("Raider_Display"), any());
    }

    @Test
    void raidToANonMemberSendsNothing() {
        when(accounts.findByTwitchId("stranger")).thenReturn(Optional.empty());

        listener.onRaid(raid("stranger"));
        listener.onRaid(raid(""));
        listener.onRaid(raid(null));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void raidWithoutSessionSendsNothing() {
        when(sessions.find(raider)).thenReturn(Optional.empty());

        listener.onRaid(raid("target-id"));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void raidAfterTheSessionEndedSendsNothing() {
        StreamSession ended = new StreamSession();
        ended.setStreamId("stream-1");
        ended.setStartedAt(RAID.minus(Duration.ofHours(3)));
        ended.setEndedAt(RAID.minus(Duration.ofMinutes(5)));
        when(sessions.find(raider)).thenReturn(Optional.of(ended));

        listener.onRaid(raid("target-id"));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void anEmptyRecapSendsNothing() {
        when(timeline.currentSession(raider)).thenReturn(List.of(
                segment("1", "Factorio", null, RAID.minus(Duration.ofSeconds(20)), null)));

        listener.onRaid(raid("target-id"));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void passiveSiteDoesNothing() {
        when(siteRole.isActive()).thenReturn(false);

        listener.onRaid(raid("target-id"));

        verifyNoInteractions(accounts, sessions, timeline, dispatcher);
    }

    @Test
    void aFailureIsNeverThrown() {
        when(accounts.findByTwitchId(anyString())).thenThrow(new IllegalStateException("db down"));

        assertThatNoException().isThrownBy(() -> listener.onRaid(raid("target-id")));
        verify(dispatcher, never()).onRaidedByMember(any(), any(), any());
    }
}
