package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.event.TwsMissedSteamEvent;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The deferred TW resolution: planned after the limit, once per binding, its result pushed. */
class TwSteamRetryTest {

    private final BindingService bindings = mock(BindingService.class);
    private final ChannelEventPublisher channelEvents = mock(ChannelEventPublisher.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final TwSteamRetry retry = new TwSteamRetry(bindings, channelEvents, scheduler);
    private final UUID bindingId = UUID.randomUUID();
    private final Instant until = Instant.parse("2026-11-12T10:05:00Z");

    @Test
    void itIsPlannedJustAfterTheLimit_oncePerBindingAtATime() {
        retry.onMissedSteam(new TwsMissedSteamEvent(bindingId, until));
        retry.onMissedSteam(new TwsMissedSteamEvent(bindingId, until.plusSeconds(30)));

        verify(scheduler, times(1)).schedule(any(Runnable.class), eq(until.plus(TwSteamRetry.MARGIN)));
    }

    @Test
    void itsResult_isPushedToTheDashboard_andAnotherMayBePlannedThen() {
        UserAccount owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        GameBinding binding = new GameBinding();
        binding.setUser(owner);
        when(bindings.retryTwsAfterSteamLimit(bindingId)).thenReturn(Optional.of(binding));
        retry.onMissedSteam(new TwsMissedSteamEvent(bindingId, until));
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), any(Instant.class));

        task.getValue().run();

        verify(channelEvents).bindingUpserted(owner.getId(), binding);
        retry.onMissedSteam(new TwsMissedSteamEvent(bindingId, until.plusSeconds(60)));
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void aFailure_isLogged_notThrown() {
        when(bindings.retryTwsAfterSteamLimit(bindingId)).thenThrow(new IllegalStateException("boom"));

        retry.retry(bindingId);

        verifyNoInteractions(channelEvents);
    }
}
