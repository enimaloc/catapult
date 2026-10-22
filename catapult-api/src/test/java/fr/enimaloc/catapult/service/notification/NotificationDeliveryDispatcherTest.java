package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.notification.Notification;
import fr.enimaloc.catapult.domain.notification.NotificationDelivery;
import fr.enimaloc.catapult.domain.notification.NotificationDeliveryId;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.notification.NotificationDeliveryRepository;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static fr.enimaloc.catapult.common.dto.notification.NotificationChannel.STREAM_START_CHAT;
import static fr.enimaloc.catapult.common.dto.notification.NotificationChannel.TWITCHAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryDispatcherTest {

    @Mock NotificationDeliveryRepository deliveryRepo;
    @Mock TwitchatEventDispatcher twitchatDispatcher;
    @Mock TwitchatWidgetSettingsService widgetSettings;
    @Mock TwitchChatService chatService;
    @Mock TaskScheduler scheduler;

    NotificationDeliveryDispatcher dispatcher;
    UserAccount user;
    final List<Runnable> scheduled = new ArrayList<>();
    final List<Instant> scheduledAt = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationDeliveryDispatcher(deliveryRepo, twitchatDispatcher, widgetSettings, chatService,
                scheduler, new NotificationRenderer());
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchUsername("streamer");
        lenient().when(widgetSettings.isEnabled(user)).thenReturn(true);
        // Every row is there to claim unless a test says otherwise
        lenient().when(deliveryRepo.claim(any(), any(), any())).thenReturn(1);
        lenient().when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> {
            scheduled.add(inv.getArgument(0));
            scheduledAt.add(inv.getArgument(1));
            return null;
        });
    }

    private NotificationDelivery delivery(String title, String body, String ctaUrl, Instant expiresAt, NotificationChannel channel) {
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setTitle(title);
        n.setBody(body);
        n.setSeverity(Severity.INFO);
        n.setCtaUrl(ctaUrl);
        n.setCtaLabel(ctaUrl == null ? null : "Voir");
        n.setExpiresAt(expiresAt);
        n.setCreatedAt(Instant.now());
        NotificationDelivery d = new NotificationDelivery();
        d.setId(new NotificationDeliveryId(n.getId(), user.getId(), channel));
        d.setNotification(n);
        d.setCreatedAt(Instant.parse("2026-10-22T10:00:00Z"));
        return d;
    }

    private void verifyClaimed(NotificationDelivery d) {
        verify(deliveryRepo).claim(d.getId().getNotificationId(), d.getId().getUserId(), d.getId().getChannel());
    }

    private void verifyRestored(NotificationDelivery d) {
        verify(deliveryRepo).restore(d.getId().getNotificationId(), d.getId().getUserId(), d.getId().getChannel().name(),
                d.getCreatedAt());
    }

    // --- Twitchat flush --------------------------------------------------------------------------

    @Test
    void flushTwitchat_claimsThenDispatchesEachPendingOne_oldestFirst_withAPlainBody() {
        NotificationDelivery older = delivery("Old", "**bold** text", "https://x.test/a", null, TWITCHAT);
        NotificationDelivery newer = delivery("New", "plain", null, Instant.now().plusSeconds(3600), TWITCHAT);
        when(deliveryRepo.findPending(user.getId(), TWITCHAT)).thenReturn(List.of(older, newer));
        when(twitchatDispatcher.onNotification(eq(user), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        assertThat(dispatcher.flushTwitchat(user)).isEqualTo(2);

        InOrder order = inOrder(twitchatDispatcher, deliveryRepo);
        order.verify(deliveryRepo).claim(older.getId().getNotificationId(), user.getId(), TWITCHAT);
        order.verify(twitchatDispatcher).onNotification(user, "Old", "bold text", "https://x.test/a", "Voir");
        order.verify(deliveryRepo).claim(newer.getId().getNotificationId(), user.getId(), TWITCHAT);
        order.verify(twitchatDispatcher).onNotification(user, "New", "plain", "", "");
        verify(deliveryRepo, never()).restore(any(), any(), any(), any());
    }

    /** The owner's page and widget, or two web instances, flushing at once: each row goes out once. */
    @Test
    void concurrentFlushes_deliverEachRowOnce() {
        NotificationDelivery d = delivery("T", "b", null, null, TWITCHAT);
        when(deliveryRepo.findPending(user.getId(), TWITCHAT)).thenReturn(List.of(d));
        when(deliveryRepo.claim(any(), any(), any())).thenReturn(1).thenReturn(0);
        when(twitchatDispatcher.onNotification(any(), any(), any(), any(), any())).thenReturn(true);

        int first = dispatcher.flushTwitchat(user);
        int second = dispatcher.flushTwitchat(user);

        assertThat(first + second).isEqualTo(1);
        verify(twitchatDispatcher, times(1)).onNotification(any(), any(), any(), any(), any());
    }

    @Test
    void flushTwitchat_expired_isClaimedWithoutBeingSent() {
        NotificationDelivery expired = delivery("Gone", "b", null, Instant.now().minusSeconds(1), TWITCHAT);
        when(deliveryRepo.findPending(user.getId(), TWITCHAT)).thenReturn(List.of(expired));

        assertThat(dispatcher.flushTwitchat(user)).isZero();

        verifyClaimed(expired);
        verifyNoInteractions(twitchatDispatcher);
    }

    @Test
    void flushTwitchat_aFailedDispatch_putsItsRowBack_andGoesOn() {
        NotificationDelivery failing = delivery("A", "a", null, null, TWITCHAT);
        NotificationDelivery ok = delivery("B", "b", null, null, TWITCHAT);
        when(deliveryRepo.findPending(user.getId(), TWITCHAT)).thenReturn(List.of(failing, ok));
        when(twitchatDispatcher.onNotification(eq(user), eq("A"), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        when(twitchatDispatcher.onNotification(eq(user), eq("B"), any(), any(), any())).thenReturn(true);

        assertThat(dispatcher.flushTwitchat(user)).isEqualTo(1);

        verifyRestored(failing);
        verify(deliveryRepo, never()).restore(eq(ok.getId().getNotificationId()), any(), any(), any());
    }

    /** Nothing would reach Twitchat: kept for when the widget is enabled again. */
    @Test
    void flushTwitchat_widgetDisabled_keepsEveryRow() {
        when(widgetSettings.isEnabled(user)).thenReturn(false);

        assertThat(dispatcher.flushTwitchat(user)).isZero();

        verifyNoInteractions(deliveryRepo, twitchatDispatcher);
    }

    // --- stream start ----------------------------------------------------------------------------

    @Test
    void streamStart_botDisabled_keepsThemPending() {
        user.setBotEnabled(false);

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));

        verifyNoInteractions(deliveryRepo, chatService, scheduler);
    }

    @Test
    void streamStart_postsEachOneInChat_threeSecondsApart_oldestFirst_claimingEachWhenItsTurnComes() {
        user.setBotEnabled(true);
        NotificationDelivery first = delivery("Maintenance", "Ce **soir**", "https://x.test/m", null, STREAM_START_CHAT);
        NotificationDelivery second = delivery("Nouveauté", "Les commandes", null, null, STREAM_START_CHAT);
        when(deliveryRepo.findPending(user.getId(), STREAM_START_CHAT)).thenReturn(List.of(first, second));

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));

        assertThat(scheduled).hasSize(2);
        assertThat(Duration.between(scheduledAt.get(0), scheduledAt.get(1))).isEqualTo(Duration.ofSeconds(3));
        verifyNoInteractions(chatService);
        verify(deliveryRepo, never()).claim(any(), any(), any());
        scheduled.forEach(Runnable::run);
        InOrder order = inOrder(chatService, deliveryRepo);
        order.verify(deliveryRepo).claim(first.getId().getNotificationId(), user.getId(), STREAM_START_CHAT);
        order.verify(chatService).sendMessage(user, "[Catapult] Maintenance — Ce soir https://x.test/m");
        order.verify(deliveryRepo).claim(second.getId().getNotificationId(), user.getId(), STREAM_START_CHAT);
        order.verify(chatService).sendMessage(user, "[Catapult] Nouveauté — Les commandes");
    }

    /** EventSub redelivery, a restart or a second api instance: the same row is only posted once. */
    @Test
    void streamStartTwice_postsOnce() {
        user.setBotEnabled(true);
        NotificationDelivery d = delivery("T", "b", null, null, STREAM_START_CHAT);
        when(deliveryRepo.findPending(user.getId(), STREAM_START_CHAT)).thenReturn(List.of(d));
        when(deliveryRepo.claim(any(), any(), any())).thenReturn(1).thenReturn(0);

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));
        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));
        scheduled.forEach(Runnable::run);

        verify(chatService, times(1)).sendMessage(any(), any());
    }

    @Test
    void streamStart_aRowReadBetweenScheduleAndPost_isNotPosted() {
        user.setBotEnabled(true);
        NotificationDelivery d = delivery("T", "b", null, null, STREAM_START_CHAT);
        when(deliveryRepo.findPending(user.getId(), STREAM_START_CHAT)).thenReturn(List.of(d));
        when(deliveryRepo.claim(any(), any(), any())).thenReturn(0);

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));
        scheduled.forEach(Runnable::run);

        verifyNoInteractions(chatService);
    }

    @Test
    void streamStart_aRowExpiredBetweenScheduleAndPost_isDroppedUnposted() {
        user.setBotEnabled(true);
        NotificationDelivery d = delivery("T", "b", null, Instant.now().plusMillis(50), STREAM_START_CHAT);
        when(deliveryRepo.findPending(user.getId(), STREAM_START_CHAT)).thenReturn(List.of(d));

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));
        d.getNotification().setExpiresAt(Instant.now().minusSeconds(1));
        scheduled.forEach(Runnable::run);

        verifyClaimed(d);
        verifyNoInteractions(chatService);
        verify(deliveryRepo, never()).restore(any(), any(), any(), any());
    }

    @Test
    void streamStart_cutsALongMessageTo500Characters() {
        user.setBotEnabled(true);
        NotificationDelivery longOne = delivery("T", "x".repeat(800), null, null, STREAM_START_CHAT);
        when(deliveryRepo.findPending(user.getId(), STREAM_START_CHAT)).thenReturn(List.of(longOne));

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));
        scheduled.forEach(Runnable::run);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(chatService).sendMessage(eq(user), text.capture());
        assertThat(text.getValue()).hasSize(500).startsWith("[Catapult] T — xxx").endsWith("x…");
    }

    @Test
    void streamStart_aFailedPost_putsItsRowBack() {
        user.setBotEnabled(true);
        NotificationDelivery d = delivery("T", "b", null, null, STREAM_START_CHAT);
        when(deliveryRepo.findPending(user.getId(), STREAM_START_CHAT)).thenReturn(List.of(d));
        doThrow(new IllegalStateException("chat down")).when(chatService).sendMessage(any(), any());

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));
        scheduled.forEach(Runnable::run);

        verifyClaimed(d);
        verifyRestored(d);
    }

    @Test
    void streamStart_expired_isClaimedWithoutBeingScheduled() {
        user.setBotEnabled(true);
        NotificationDelivery expired = delivery("T", "b", null, Instant.now().minusSeconds(5), STREAM_START_CHAT);
        when(deliveryRepo.findPending(user.getId(), STREAM_START_CHAT)).thenReturn(List.of(expired));

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));

        verifyClaimed(expired);
        assertThat(scheduled).isEmpty();
    }
}
