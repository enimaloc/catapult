package fr.enimaloc.catapult.service.notification;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryDispatcherTest {

    @Mock NotificationDeliveryRepository deliveryRepo;
    @Mock TwitchatEventDispatcher twitchatDispatcher;
    @Mock TwitchChatService chatService;
    @Mock TaskScheduler scheduler;

    NotificationDeliveryDispatcher dispatcher;
    UserAccount user;
    final List<Runnable> scheduled = new ArrayList<>();
    final List<Instant> scheduledAt = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationDeliveryDispatcher(deliveryRepo, twitchatDispatcher, chatService, scheduler,
                new NotificationRenderer());
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchUsername("streamer");
        lenient().when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> {
            scheduled.add(inv.getArgument(0));
            scheduledAt.add(inv.getArgument(1));
            return null;
        });
    }

    private NotificationDelivery delivery(String title, String body, String ctaUrl, Instant expiresAt,
                                          fr.enimaloc.catapult.common.dto.notification.NotificationChannel channel) {
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
        d.setCreatedAt(Instant.now());
        return d;
    }

    @Test
    void flushTwitchat_dispatchesEachPendingOne_oldestFirst_withAPlainBody_andDeletesThem() {
        NotificationDelivery older = delivery("Old", "**bold** text", "https://x.test/a", null, TWITCHAT);
        NotificationDelivery newer = delivery("New", "plain", null, Instant.now().plusSeconds(3600), TWITCHAT);
        when(deliveryRepo.findPending(user.getId(), TWITCHAT)).thenReturn(List.of(older, newer));
        when(twitchatDispatcher.onNotification(eq(user), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        int delivered = dispatcher.flushTwitchat(user);

        assertThat(delivered).isEqualTo(2);
        InOrder order = inOrder(twitchatDispatcher, deliveryRepo);
        order.verify(twitchatDispatcher).onNotification(user, "Old", "bold text", "https://x.test/a", "Voir");
        order.verify(deliveryRepo).deleteById(older.getId());
        order.verify(twitchatDispatcher).onNotification(user, "New", "plain", "", "");
        order.verify(deliveryRepo).deleteById(newer.getId());
    }

    @Test
    void flushTwitchat_expired_isDeletedWithoutBeingSent() {
        NotificationDelivery expired = delivery("Gone", "b", null, Instant.now().minusSeconds(1), TWITCHAT);
        when(deliveryRepo.findPending(user.getId(), TWITCHAT)).thenReturn(List.of(expired));

        assertThat(dispatcher.flushTwitchat(user)).isZero();

        verify(deliveryRepo).deleteById(expired.getId());
        verifyNoInteractions(twitchatDispatcher);
    }

    @Test
    void flushTwitchat_aFailedDispatch_keepsItsRow_andGoesOn() {
        NotificationDelivery failing = delivery("A", "a", null, null, TWITCHAT);
        NotificationDelivery ok = delivery("B", "b", null, null, TWITCHAT);
        when(deliveryRepo.findPending(user.getId(), TWITCHAT)).thenReturn(List.of(failing, ok));
        when(twitchatDispatcher.onNotification(eq(user), eq("A"), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        when(twitchatDispatcher.onNotification(eq(user), eq("B"), any(), any(), any())).thenReturn(true);

        assertThat(dispatcher.flushTwitchat(user)).isEqualTo(1);

        verify(deliveryRepo, never()).deleteById(failing.getId());
        verify(deliveryRepo).deleteById(ok.getId());
    }

    @Test
    void streamStart_botDisabled_keepsThemPending() {
        user.setBotEnabled(false);

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));

        verifyNoInteractions(deliveryRepo, chatService, scheduler);
    }

    @Test
    void streamStart_postsEachOneInChat_threeSecondsApart_oldestFirst_deletingEachOnceSent() {
        user.setBotEnabled(true);
        NotificationDelivery first = delivery("Maintenance", "Ce **soir**", "https://x.test/m", null, STREAM_START_CHAT);
        NotificationDelivery second = delivery("Nouveauté", "Les commandes", null, null, STREAM_START_CHAT);
        when(deliveryRepo.findPending(user.getId(), STREAM_START_CHAT)).thenReturn(List.of(first, second));

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));

        assertThat(scheduled).hasSize(2);
        assertThat(Duration.between(scheduledAt.get(0), scheduledAt.get(1))).isEqualTo(Duration.ofSeconds(3));
        verifyNoInteractions(chatService);
        scheduled.forEach(Runnable::run);
        InOrder order = inOrder(chatService, deliveryRepo);
        order.verify(chatService).sendMessage(user, "[Catapult] Maintenance — Ce soir https://x.test/m");
        order.verify(deliveryRepo).deleteById(first.getId());
        order.verify(chatService).sendMessage(user, "[Catapult] Nouveauté — Les commandes");
        order.verify(deliveryRepo).deleteById(second.getId());
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
    void streamStart_aFailedPost_keepsItsRow() {
        user.setBotEnabled(true);
        NotificationDelivery d = delivery("T", "b", null, null, STREAM_START_CHAT);
        when(deliveryRepo.findPending(user.getId(), STREAM_START_CHAT)).thenReturn(List.of(d));
        doThrow(new IllegalStateException("chat down")).when(chatService).sendMessage(any(), any());

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));
        scheduled.forEach(Runnable::run);

        verify(deliveryRepo, never()).deleteById(any());
    }

    @Test
    void streamStart_expired_isDeletedWithoutBeingPosted() {
        user.setBotEnabled(true);
        NotificationDelivery expired = delivery("T", "b", null, Instant.now().minusSeconds(5), STREAM_START_CHAT);
        when(deliveryRepo.findPending(user.getId(), STREAM_START_CHAT)).thenReturn(List.of(expired));

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));

        verify(deliveryRepo).deleteById(expired.getId());
        assertThat(scheduled).isEmpty();
    }
}
