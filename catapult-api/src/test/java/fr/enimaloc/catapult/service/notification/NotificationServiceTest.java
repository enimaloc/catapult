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

    NotificationService service;
    UserAccount admin;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notifRepo, recipientRepo, userRepo, renderer, publisher);
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
        when(recipientRepo.findByIdNotificationId(n.getId())).thenReturn(List.of(r));

        var page = service.adminPage(org.springframework.data.domain.Pageable.unpaged());

        assertThat(page.getContent().get(0).targetUserId()).isEqualTo(target.getId());
        assertThat(page.getContent().get(0).targetLogin()).isEqualTo("someone");
        assertThat(page.getContent().get(0).bodyHtml()).isEqualTo("<p>b</p>");
        assertThat(page.getContent().get(1).targetLogin()).isNull();
        verify(recipientRepo, never()).findByIdNotificationId(all.getId());
    }

    private UserAccount userWithStatus(UserAccount.Status s) {
        UserAccount u = new UserAccount();
        u.setId(UUID.randomUUID());
        u.setStatus(s);
        return u;
    }
}
