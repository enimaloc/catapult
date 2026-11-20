package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.ChannelCategoryChangedEvent;
import fr.enimaloc.catapult.event.ChannelStateResyncedEvent;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService.CurrentCategory;
import fr.enimaloc.catapult.site.SiteRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import fr.enimaloc.catapult.domain.stream.StreamCategorySegment;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionTimelineListenerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T18:30:00Z");

    private final SessionTimelineService timeline = mock(SessionTimelineService.class);
    private final TwitchEventSubService eventSub = mock(TwitchEventSubService.class);
    private final GameStateService gameState = mock(GameStateService.class);
    private final SiteRole siteRole = mock(SiteRole.class);
    /** Runs the Helix read inline unless a test needs a real background thread. */
    private SessionTimelineListener listener = listener(Runnable::run);

    private SessionTimelineListener listener(java.util.concurrent.Executor executor) {
        return new SessionTimelineListener(timeline, eventSub, gameState, siteRole, Clock.fixed(NOW, ZoneOffset.UTC),
                executor);
    }

    private static StreamCategorySegment running(String categoryId) {
        StreamCategorySegment segment = new StreamCategorySegment();
        segment.setCategoryId(categoryId);
        segment.setStartedAt(NOW);
        return segment;
    }

    private UserAccount user;

    @BeforeEach
    void setUp() {
        when(siteRole.isActive()).thenReturn(true);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    private static DetectedGame game(String name) {
        return new DetectedGame("source-" + name, null, name);
    }

    @Test
    void streamOnlineOpensWithTheKnownCategory() {
        when(eventSub.currentCategory(user.getId())).thenReturn(Optional.of(new CurrentCategory("1", "Factorio")));
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.of(game("Factorio")));

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(timeline).observe(user, "1", "Factorio", "Factorio", NOW);
        verify(eventSub, never()).fetchCurrentCategory(any());
    }

    @Test
    void streamOnlineFallsBackToHelix() {
        when(eventSub.currentCategory(user.getId())).thenReturn(Optional.empty());
        when(eventSub.fetchCurrentCategory(user)).thenReturn(Optional.of(new CurrentCategory("2", "Just Chatting")));
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.empty());
        when(timeline.currentSession(user)).thenReturn(List.of(running(null)));

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(timeline).observe(user, null, null, null, NOW);
        verify(timeline).observeCategory(user, "2", "Just Chatting", NOW);
    }

    @Test
    void streamOnlineDoesNotWaitForHelix() throws InterruptedException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        listener = listener(executor);
        CountDownLatch helixAnswers = new CountDownLatch(1);
        when(eventSub.currentCategory(user.getId())).thenReturn(Optional.empty());
        when(eventSub.fetchCurrentCategory(user)).thenAnswer(invocation -> {
            helixAnswers.await(5, TimeUnit.SECONDS);
            return Optional.of(new CurrentCategory("2", "Just Chatting"));
        });
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.of(game("Factorio")));
        when(timeline.currentSession(user)).thenReturn(List.of(running(null)));

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(timeline).observe(user, null, null, "Factorio", NOW);
        verify(timeline, never()).observeCategory(any(), any(), any(), any());
        helixAnswers.countDown();
        verify(timeline, timeout(5000)).observeCategory(user, "2", "Just Chatting", NOW);
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void helixAnswerDoesNotOverrideACategoryAlreadyKnown() {
        when(eventSub.currentCategory(user.getId())).thenReturn(Optional.empty());
        when(eventSub.fetchCurrentCategory(user)).thenReturn(Optional.of(new CurrentCategory("2", "Just Chatting")));
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.empty());
        // A channel.update got there first
        when(timeline.currentSession(user)).thenReturn(List.of(running("3")));

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(timeline, never()).observeCategory(any(), any(), any(), any());
    }

    @Test
    void helixFillDoesNothingOnceTheSiteIsPassive() {
        when(eventSub.currentCategory(user.getId())).thenReturn(Optional.empty());
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.empty());
        when(timeline.currentSession(user)).thenReturn(List.of(running(null)));
        // The site turns passive while Helix answers
        when(eventSub.fetchCurrentCategory(user)).thenAnswer(invocation -> {
            when(siteRole.isActive()).thenReturn(false);
            return Optional.of(new CurrentCategory("2", "Just Chatting"));
        });

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(timeline).observe(user, null, null, null, NOW);
        verify(timeline, never()).observeCategory(any(), any(), any(), any());
    }

    @Test
    void emptyCategoryReadsNoHelix() {
        when(eventSub.currentCategory(user.getId())).thenReturn(Optional.of(new CurrentCategory("", "")));
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.empty());

        listener.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(timeline).observe(user, null, null, null, NOW);
        verify(eventSub, never()).fetchCurrentCategory(any());
    }

    @Test
    void streamOnlineWithoutCategoryOpensUnknown() {
        when(eventSub.currentCategory(user.getId())).thenReturn(Optional.empty());
        when(eventSub.fetchCurrentCategory(user)).thenThrow(new IllegalStateException("Helix down"));
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.of(game("MOLTYN")));

        assertThatNoException().isThrownBy(() -> listener.onStreamOnline(new StreamOnlineEvent(this, user)));

        verify(timeline).observe(user, null, null, "MOLTYN", NOW);
        verify(timeline, never()).observeCategory(any(), any(), any(), any());
    }

    @Test
    void categoryChangeIsObserved() {
        listener.onCategoryChanged(new ChannelCategoryChangedEvent(this, user, "3", "Games + Demos"));

        verify(timeline).observeCategory(user, "3", "Games + Demos", NOW);
    }

    @Test
    void gameDetectionIsObserved() {
        listener.onGameDetected(new GameDetectedEvent(this, user, game("Factorio"), false));

        verify(timeline).observeGame(user, "Factorio", NOW);
    }

    @Test
    void noGameIsObserved() {
        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));

        verify(timeline).observeGame(user, null, NOW);
    }

    @Test
    void streamOfflineCloses() {
        listener.onStreamOffline(new StreamOfflineEvent(this, user));

        verify(timeline).closeOpen(user, NOW);
    }

    @Test
    void passiveSiteDoesNothing() {
        when(siteRole.isActive()).thenReturn(false);

        listener.onStreamOnline(new StreamOnlineEvent(this, user));
        listener.onCategoryChanged(new ChannelCategoryChangedEvent(this, user, "3", "Games + Demos"));
        listener.onGameDetected(new GameDetectedEvent(this, user, game("Factorio"), false));
        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));
        listener.onStreamOffline(new StreamOfflineEvent(this, user));

        verifyNoInteractions(timeline, eventSub, gameState);
    }

    @Test
    void aFailureIsSwallowed() {
        doThrow(new DataIntegrityViolationException("two running periods"))
                .when(timeline).observeGame(any(), any(), any());
        doThrow(new IllegalStateException("database down")).when(timeline).closeOpen(any(), any());
        when(eventSub.currentCategory(user.getId())).thenReturn(Optional.of(new CurrentCategory("1", "Factorio")));
        doThrow(new DataIntegrityViolationException("two running periods"))
                .when(timeline).observe(any(), any(), any(), any(), any());

        assertThatNoException().isThrownBy(() -> {
            listener.onGameDetected(new GameDetectedEvent(this, user, game("Factorio"), false));
            listener.onStreamOffline(new StreamOfflineEvent(this, user));
            listener.onStreamOnline(new StreamOnlineEvent(this, user));
        });

        when(gameState.getLastKnownGame(user)).thenThrow(new IllegalStateException("no game state"));
        assertThatNoException().isThrownBy(() -> listener.onStreamOnline(new StreamOnlineEvent(this, user)));
    }

    @Test
    void resyncedCategoryIsObserved() {
        listener.onChannelStateResynced(new ChannelStateResyncedEvent(this, user, "3", "Games + Demos"));

        verify(timeline).observeCategory(user, "3", "Games + Demos", NOW);
    }

    @Test
    void resyncOnPassiveSiteDoesNothing() {
        when(siteRole.isActive()).thenReturn(false);

        listener.onChannelStateResynced(new ChannelStateResyncedEvent(this, user, "3", "Games + Demos"));

        verifyNoInteractions(timeline, eventSub, gameState);
    }

    @Test
    void aResyncFailureIsSwallowed() {
        doThrow(new IllegalStateException("database down"))
                .when(timeline).observeCategory(any(), any(), any(), any());

        assertThatNoException().isThrownBy(() ->
                listener.onChannelStateResynced(new ChannelStateResyncedEvent(this, user, "3", "Games + Demos")));
    }
}
