package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.notification.Notification;
import fr.enimaloc.catapult.domain.notification.NotificationRecipient;
import fr.enimaloc.catapult.domain.notification.NotificationRecipientId;
import fr.enimaloc.catapult.event.NotificationAllReadEvent;
import fr.enimaloc.catapult.event.NotificationCreatedEvent;
import fr.enimaloc.catapult.event.NotificationDeletedEvent;
import fr.enimaloc.catapult.event.NotificationReadEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.notification.NotificationRecipientRepository;
import fr.enimaloc.catapult.repository.notification.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.stream.Stream;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository notifRepo;
    @Mock NotificationRecipientRepository recipientRepo;
    @Mock UserAccountRepository userRepo;
    @Mock ApplicationEventPublisher publisher;
    @Mock NotificationRenderer renderer;
    @Mock NotificationDeliveryService deliveries;

    NotificationService service;
    UserAccount admin;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notifRepo, recipientRepo, userRepo, renderer, publisher, deliveries);
        admin = new UserAccount();
        admin.setId(UUID.randomUUID());
        lenient().when(renderer.render(any())).thenAnswer(inv -> "<p>" + inv.getArgument(0) + "</p>");
    }

    @Test
    void createTargeted_savesOneRecipient_andPublishesEvent() {
        UUID targetId = UUID.randomUUID();
        UserAccount target = new UserAccount();
        target.setId(targetId);
        when(userRepo.findById(targetId)).thenReturn(Optional.of(target));
        when(notifRepo.save(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return n;
        });

        NotificationService.CreateRequest req = new NotificationService.CreateRequest(
                "T", "B", Severity.INFO, null, null, null, targetId);

        service.create(req, admin);

        verify(notifRepo).save(any(Notification.class));
        ArgumentCaptor<List<NotificationRecipient>> cap = ArgumentCaptor.forClass(List.class);
        verify(recipientRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().getFirst().getUser().getId()).isEqualTo(targetId);

        verify(publisher).publishEvent(any(NotificationCreatedEvent.class));
    }

    /** The chat commands' end: each streamer told alone, the call to action naming their own channel. */
    @Test
    void createForEach_sendsOneTargetedNotificationPerRecipient_withTheirChannelInTheCta() {
        UserAccount u1 = userWithStatus(UserAccount.Status.ACTIVE);
        u1.setTwitchUsername("alice");
        UserAccount u2 = userWithStatus(UserAccount.Status.ACTIVE);
        u2.setTwitchUsername("bob");
        List<Notification> saved = new java.util.ArrayList<>();
        when(notifRepo.save(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            saved.add(n);
            return n;
        });

        int sent = service.createForEach(new NotificationService.CreateRequest("T", "B", Severity.WARNING,
                "https://catapult.test/channel/{channel}#integrations", "Voir", null, null), admin, List.of(u1, u2));

        assertThat(sent).isEqualTo(2);
        assertThat(saved).extracting(Notification::getCtaUrl).containsExactly(
                "https://catapult.test/channel/alice#integrations", "https://catapult.test/channel/bob#integrations");
        assertThat(saved).extracting(Notification::getAudience).containsOnly(Notification.Audience.TARGETED);
        ArgumentCaptor<List<NotificationRecipient>> cap = ArgumentCaptor.forClass(List.class);
        verify(recipientRepo, org.mockito.Mockito.times(2)).saveAll(cap.capture());
        assertThat(cap.getAllValues()).extracting(rows -> rows.getFirst().getUser()).containsExactly(u1, u2);
    }

    @Test
    void createForEach_refusesAnInvalidRequest_beforeSendingAny() {
        UserAccount u1 = userWithStatus(UserAccount.Status.ACTIVE);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createForEach(
                        new NotificationService.CreateRequest("T", "B", Severity.INFO, "javascript:{channel}", "Voir", null, null),
                        admin, List.of(u1)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(notifRepo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void createForAll_storesOneTargetedNotificationSharedByEveryRecipient() {
        UserAccount u1 = userWithStatus(UserAccount.Status.ACTIVE);
        UserAccount u2 = userWithStatus(UserAccount.Status.ACTIVE);
        when(notifRepo.save(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return n;
        });

        int sent = service.createForAll(new NotificationService.CreateRequest("T", "B", Severity.INFO,
                null, null, null, null), admin, List.of(u1, u2));

        assertThat(sent).isEqualTo(2);
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notifRepo).save(saved.capture());
        assertThat(saved.getValue().getAudience()).isEqualTo(Notification.Audience.TARGETED);
        ArgumentCaptor<List<NotificationRecipient>> cap = ArgumentCaptor.forClass(List.class);
        verify(recipientRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting(NotificationRecipient::getUser).containsExactly(u1, u2);
    }

    @Test
    void createForAll_withNoRecipient_storesNothing() {
        int sent = service.createForAll(new NotificationService.CreateRequest("T", "B", Severity.INFO,
                null, null, null, null), admin, List.of());

        assertThat(sent).isZero();
        verify(notifRepo, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void createBroadcast_savesAllActiveUsers() {
        UserAccount u1 = userWithStatus(UserAccount.Status.ACTIVE);
        UserAccount u2 = userWithStatus(UserAccount.Status.ACTIVE);
        when(userRepo.findByStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(u1, u2));
        when(notifRepo.save(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return n;
        });

        NotificationService.CreateRequest req = new NotificationService.CreateRequest(
                "T", "B", Severity.WARNING, null, null, null, null);

        service.create(req, admin);

        ArgumentCaptor<List<NotificationRecipient>> cap = ArgumentCaptor.forClass(List.class);
        verify(recipientRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(2);
    }

    /** Every audience goes through send: its recipients' other channels get their pending deliveries, after the recipients. */
    @Test
    void send_createsThePendingDeliveries_onceTheRecipientsAreSaved() {
        UserAccount u1 = userWithStatus(UserAccount.Status.ACTIVE);
        UUID id = UUID.randomUUID();
        when(notifRepo.save(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(id);
            return n;
        });

        service.createForAll(new NotificationService.CreateRequest("T", "B", Severity.INFO, null, null, null, null),
                admin, List.of(u1));

        var order = inOrder(recipientRepo, deliveries);
        order.verify(recipientRepo).saveAll(any());
        order.verify(deliveries).createFor(id);
    }

    @Test
    void markRead_cancelsItsPendingDeliveries() {
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        NotificationRecipient recipient = new NotificationRecipient();
        recipient.setId(new NotificationRecipientId(notifId, userId));
        when(recipientRepo.findById(new NotificationRecipientId(notifId, userId))).thenReturn(Optional.of(recipient));

        service.markRead(userId, notifId);

        verify(deliveries).cancel(userId, notifId);
    }

    @Test
    void markAllRead_andDelete_cancelThePendingDeliveries() {
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();

        service.markAllRead(userId);
        service.delete(userId, notifId);

        verify(deliveries).cancelAll(userId);
        verify(deliveries).cancel(userId, notifId);
    }

    @Test
    void markRead_firesNotificationReadEvent() {
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        NotificationRecipient recipient = new NotificationRecipient();
        recipient.setId(new NotificationRecipientId(notifId, userId));
        when(recipientRepo.findById(new NotificationRecipientId(notifId, userId)))
                .thenReturn(Optional.of(recipient));
        when(recipientRepo.countByUserIdAndReadAtIsNull(userId)).thenReturn(2L);

        service.markRead(userId, notifId);

        ArgumentCaptor<NotificationReadEvent> cap = ArgumentCaptor.forClass(NotificationReadEvent.class);
        verify(publisher).publishEvent(cap.capture());
        assertThat(cap.getValue().userId()).isEqualTo(userId);
        assertThat(cap.getValue().notificationId()).isEqualTo(notifId);
        assertThat(cap.getValue().newUnreadCount()).isEqualTo(2L);
    }

    @Test
    void markAllRead_firesNotificationAllReadEvent() {
        UUID userId = UUID.randomUUID();
        service.markAllRead(userId);
        verify(publisher).publishEvent(new NotificationAllReadEvent(userId));
    }

    @Test
    void delete_firesNotificationDeletedEvent() {
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        when(recipientRepo.findById(new NotificationRecipientId(notifId, userId)))
                .thenReturn(Optional.of(mock(NotificationRecipient.class)));
        when(recipientRepo.countByUserIdAndReadAtIsNull(userId)).thenReturn(3L);

        service.delete(userId, notifId);

        verify(publisher).publishEvent(new NotificationDeletedEvent(userId, notifId, 3L));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void createRefusesAnInvalidRequest(NotificationService.CreateRequest request) {
        assertThatThrownBy(() -> service.create(request, admin))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400);
        verify(notifRepo, never()).save(any());
    }

    static Stream<NotificationService.CreateRequest> invalidRequests() {
        return Stream.of(
                new NotificationService.CreateRequest(null, "b", Severity.INFO, null, null, null, null),
                new NotificationService.CreateRequest("  ", "b", Severity.INFO, null, null, null, null),
                new NotificationService.CreateRequest("x".repeat(256), "b", Severity.INFO, null, null, null, null),
                new NotificationService.CreateRequest("t", null, Severity.INFO, null, null, null, null),
                new NotificationService.CreateRequest("t", " ", Severity.INFO, null, null, null, null),
                new NotificationService.CreateRequest("t", "b", null, null, null, null, null),
                new NotificationService.CreateRequest("t", "b", Severity.INFO, "https://a.b", null, null, null),
                new NotificationService.CreateRequest("t", "b", Severity.INFO, null, "Open", null, null),
                new NotificationService.CreateRequest("t", "b", Severity.INFO, "https://a.b", "x".repeat(65), null, null),
                new NotificationService.CreateRequest("t", "b", Severity.INFO, "https://a.b/" + "x".repeat(510), "Open", null, null),
                new NotificationService.CreateRequest("t", "b", Severity.INFO, "javascript:alert(1)", "Open", null, null),
                new NotificationService.CreateRequest("t", "b", Severity.INFO, null, null, Instant.now().minusSeconds(1), null));
    }

    @Test
    void createRefusesAnUnknownTarget() {
        UUID ghost = UUID.randomUUID();
        when(userRepo.findById(ghost)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(new NotificationService.CreateRequest("t", "b", Severity.INFO, null, null, null, ghost), admin))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);
        verify(notifRepo, never()).save(any());
    }

    @Test
    void createStoresBlankCtaFieldsAsNull() {
        when(userRepo.findByStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of());
        when(notifRepo.save(any())).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return n;
        });
        service.create(new NotificationService.CreateRequest("t", "b", Severity.INFO, " ", "", null, null), admin);
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notifRepo).save(cap.capture());
        assertThat(cap.getValue().getCtaUrl()).isNull();
        assertThat(cap.getValue().getCtaLabel()).isNull();
    }

    @Test
    void adminPageResolvesTheTargetOfATargetedNotification() {
        UserAccount target = new UserAccount();
        target.setId(UUID.randomUUID());
        target.setTwitchUsername("someone");
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setTitle("t");
        n.setBody("b");
        n.setSeverity(Severity.INFO);
        n.setAudience(Notification.Audience.TARGETED);
        Notification all = new Notification();
        all.setId(UUID.randomUUID());
        all.setBody("b");
        all.setSeverity(Severity.INFO);
        all.setAudience(Notification.Audience.BROADCAST);
        NotificationRecipient r = new NotificationRecipient();
        r.setUser(target);
        when(notifRepo.findAllByOrderByCreatedAtDesc(any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(n, all)));
        when(recipientRepo.countByIdNotificationId(n.getId())).thenReturn(1L);
        when(recipientRepo.findByIdNotificationId(n.getId())).thenReturn(List.of(r));

        var page = service.adminPage(org.springframework.data.domain.Pageable.unpaged());

        assertThat(page.getContent().get(0).targetUserId()).isEqualTo(target.getId());
        assertThat(page.getContent().get(0).targetLogin()).isEqualTo("someone");
        assertThat(page.getContent().get(0).bodyHtml()).isEqualTo("<p>b</p>");
        assertThat(page.getContent().get(0).recipientCount()).isEqualTo(1);
        assertThat(page.getContent().get(1).targetLogin()).isNull();
        verify(recipientRepo, never()).findByIdNotificationId(all.getId());
        verify(recipientRepo, never()).countByIdNotificationId(all.getId());
    }

    @Test
    void adminPageCountsASharedNotificationsRecipients_withoutLoadingThemOrNamingOne() {
        Notification shared = new Notification();
        shared.setId(UUID.randomUUID());
        shared.setBody("b");
        shared.setSeverity(Severity.INFO);
        shared.setAudience(Notification.Audience.TARGETED);
        when(notifRepo.findAllByOrderByCreatedAtDesc(any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(shared)));
        when(recipientRepo.countByIdNotificationId(shared.getId())).thenReturn(3L);

        var dto = service.adminPage(org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);

        assertThat(dto.recipientCount()).isEqualTo(3);
        assertThat(dto.targetUserId()).isNull();
        assertThat(dto.targetLogin()).isNull();
        verify(recipientRepo, never()).findByIdNotificationId(shared.getId());
    }

    private UserAccount userWithStatus(UserAccount.Status s) {
        UserAccount u = new UserAccount();
        u.setId(UUID.randomUUID());
        u.setStatus(s);
        return u;
    }
}
