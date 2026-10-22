package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.notification.NotificationChannelPreference;
import fr.enimaloc.catapult.domain.notification.NotificationChannelPreferenceId;
import fr.enimaloc.catapult.event.NotificationDeliveryPendingEvent;
import fr.enimaloc.catapult.repository.notification.NotificationChannelPreferenceRepository;
import fr.enimaloc.catapult.repository.notification.NotificationDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static fr.enimaloc.catapult.common.dto.notification.NotificationChannel.STREAM_START_CHAT;
import static fr.enimaloc.catapult.common.dto.notification.NotificationChannel.TWITCHAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceTest {

    @Mock NotificationChannelPreferenceRepository preferenceRepo;
    @Mock NotificationDeliveryRepository deliveryRepo;
    @Mock TwitchatEventActionService eventActionService;
    @Mock ApplicationEventPublisher publisher;

    NotificationDeliveryService service;
    UserAccount user;

    @BeforeEach
    void setUp() {
        service = new NotificationDeliveryService(preferenceRepo, deliveryRepo, eventActionService, publisher);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    private NotificationChannelPreference pref(fr.enimaloc.catapult.common.dto.notification.NotificationChannel channel) {
        return new NotificationChannelPreference(new NotificationChannelPreferenceId(user.getId(), channel), Instant.now());
    }

    @Test
    void channels_areTheStoredPreferences() {
        when(preferenceRepo.findByIdUserId(user.getId())).thenReturn(List.of(pref(TWITCHAT)));

        assertThat(service.channels(user.getId())).containsExactly(TWITCHAT);
    }

    @Test
    void replaceChannels_addsTheNewOnes_removesTheOthers_andCancelsTheirPendingDeliveries() {
        when(preferenceRepo.findByIdUserId(user.getId())).thenReturn(List.of(pref(STREAM_START_CHAT)));

        service.replaceChannels(user, Set.of(TWITCHAT));

        ArgumentCaptor<List<NotificationChannelPreference>> added = ArgumentCaptor.forClass(List.class);
        verify(preferenceRepo).saveAll(added.capture());
        assertThat(added.getValue()).extracting(p -> p.getId().getChannel()).containsExactly(TWITCHAT);
        verify(preferenceRepo).deleteAllById(List.of(new NotificationChannelPreferenceId(user.getId(), STREAM_START_CHAT)));
        verify(deliveryRepo).deleteByUserAndChannels(user.getId(), List.of(STREAM_START_CHAT));
    }

    @Test
    void optingInToTwitchat_givesTheAccountItsNotificationPayload() {
        when(preferenceRepo.findByIdUserId(user.getId())).thenReturn(List.of());

        service.replaceChannels(user, Set.of(TWITCHAT));

        verify(eventActionService).ensureNotificationPayload(user);
    }

    @Test
    void keepingTheSameChannels_changesNothing() {
        when(preferenceRepo.findByIdUserId(user.getId())).thenReturn(List.of(pref(TWITCHAT)));

        service.replaceChannels(user, Set.of(TWITCHAT));

        verify(preferenceRepo, never()).saveAll(any());
        verify(deliveryRepo, never()).deleteByUserAndChannels(any(), anyList());
        verify(eventActionService, never()).ensureNotificationPayload(any());
    }

    @Test
    void createFor_createsTheDeliveriesInBulk_andTellsTheWebWhoseTwitchatHasSome() {
        UUID notificationId = UUID.randomUUID();
        UUID waiting = UUID.randomUUID();
        when(deliveryRepo.createForNotification(eq(notificationId), any())).thenReturn(2);
        when(deliveryRepo.findUserIdsByNotificationAndChannel(notificationId, TWITCHAT)).thenReturn(List.of(waiting));

        service.createFor(notificationId);

        verify(publisher).publishEvent(new NotificationDeliveryPendingEvent(List.of(waiting)));
    }

    @Test
    void createFor_onlyStreamStartDeliveries_tellsNobody() {
        UUID notificationId = UUID.randomUUID();
        when(deliveryRepo.createForNotification(eq(notificationId), any())).thenReturn(1);
        when(deliveryRepo.findUserIdsByNotificationAndChannel(notificationId, TWITCHAT)).thenReturn(List.of());

        service.createFor(notificationId);

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void createFor_noneCreated_skipsTheLookup() {
        UUID notificationId = UUID.randomUUID();
        when(deliveryRepo.createForNotification(eq(notificationId), any())).thenReturn(0);

        service.createFor(notificationId);

        verify(deliveryRepo, never()).findUserIdsByNotificationAndChannel(any(), any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void cancel_deletesThatNotificationsPendingDeliveries_cancelAll_everyOne() {
        UUID notificationId = UUID.randomUUID();

        service.cancel(user.getId(), notificationId);
        service.cancelAll(user.getId());

        verify(deliveryRepo).deleteByUserAndNotification(user.getId(), notificationId);
        verify(deliveryRepo).deleteByUser(user.getId());
    }
}
