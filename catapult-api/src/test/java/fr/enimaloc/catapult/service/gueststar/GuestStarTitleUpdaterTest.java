package fr.enimaloc.catapult.service.gueststar;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.event.GuestStarsChangedEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.site.SiteRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuestStarTitleUpdaterTest {

    private final StreamTitleService titles = mock(StreamTitleService.class);
    private final TwitchService twitch = mock(TwitchService.class);
    private final StreamStateService streamState = mock(StreamStateService.class);
    private final UserSettingsRepository settingsRepository = mock(UserSettingsRepository.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final SiteRole siteRole = mock(SiteRole.class);
    private final GuestStarTitleUpdater updater = new GuestStarTitleUpdater(titles, twitch, streamState,
            settingsRepository, accounts, scheduler, siteRole);
    private final UserAccount user = new UserAccount();
    private final UserSettings settings = new UserSettings();

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        settings.setTitleFeatureEnabled(true);
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        when(settingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(siteRole.isActive()).thenReturn(true);
        when(streamState.isLive(user)).thenReturn(true);
        when(titles.guestStarsRefresh(user)).thenReturn(Optional.of("X | A"));
        when(titles.lastSentTitle(user)).thenReturn(Optional.of("X"));
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> mock(ScheduledFuture.class));
    }

    /** The task scheduled last, run now. */
    private void runScheduled(int scheduledSoFar) {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(scheduledSoFar)).schedule(task.capture(), any(Instant.class));
        task.getValue().run();
    }

    @Test
    void scheduledFifteenSecondsLater() {
        Instant before = Instant.now();
        updater.onGuestStarsChanged(new GuestStarsChangedEvent(this, user));
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(any(Runnable.class), at.capture());
        assertThat(Duration.between(before, at.getValue())).isBetween(Duration.ofSeconds(14), Duration.ofSeconds(16));
    }

    @Test
    void burstGivesOnePatch() {
        ScheduledFuture<?> first = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> first);
        when(twitch.updateTitle(user, "X", "X | A")).thenReturn(ChannelUpdateOutcome.APPLIED);
        for (int i = 0; i < 3; i++) updater.onGuestStarsChanged(new GuestStarsChangedEvent(this, user));

        verify(first, times(2)).cancel(false);
        runScheduled(3);
        verify(twitch, times(1)).updateTitle(user, "X", "X | A");
        verify(titles).sentRefresh(user, "X | A");
    }

    @Test
    void handEditedOrUnchanged_notRecorded() {
        when(twitch.updateTitle(user, "X", "X | A")).thenReturn(ChannelUpdateOutcome.SKIPPED);
        updater.onGuestStarsChanged(new GuestStarsChangedEvent(this, user));
        runScheduled(1);
        verify(titles, never()).sentRefresh(any(), anyString());
    }

    @Test
    void sameTitleSendsNothing() {
        when(titles.guestStarsRefresh(user)).thenReturn(Optional.of("X"));
        updater.onGuestStarsChanged(new GuestStarsChangedEvent(this, user));
        runScheduled(1);
        verify(twitch, never()).updateTitle(any(), anyString(), anyString());
    }

    @Test
    void offlineChannelSkipped() {
        when(streamState.isLive(user)).thenReturn(false);
        updater.onGuestStarsChanged(new GuestStarsChangedEvent(this, user));
        runScheduled(1);
        verify(twitch, never()).updateTitle(any(), anyString(), anyString());
    }

    @Test
    void titlesOffSkipped() {
        settings.setTitleFeatureEnabled(false);
        updater.onGuestStarsChanged(new GuestStarsChangedEvent(this, user));
        runScheduled(1);
        verify(twitch, never()).updateTitle(any(), anyString(), anyString());
    }

    @Test
    void noLastRenderSkipped() {
        when(titles.guestStarsRefresh(user)).thenReturn(Optional.empty());
        updater.onGuestStarsChanged(new GuestStarsChangedEvent(this, user));
        runScheduled(1);
        verify(twitch, never()).updateTitle(any(), anyString(), anyString());
    }

    @Test
    void standbySiteSkipped() {
        when(siteRole.isActive()).thenReturn(false);
        updater.onGuestStarsChanged(new GuestStarsChangedEvent(this, user));
        runScheduled(1);
        verify(twitch, never()).updateTitle(any(), anyString(), anyString());
    }

    @Test
    void aFailureIsOnlyLogged() {
        when(twitch.updateTitle(user, "X", "X | A")).thenThrow(new IllegalStateException("boom"));
        updater.onGuestStarsChanged(new GuestStarsChangedEvent(this, user));
        runScheduled(1);
        verify(titles, never()).sentRefresh(any(), anyString());
    }
}
