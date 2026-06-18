package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.event.NotificationCreatedEvent;
import fr.enimaloc.catapult.repository.NotificationRecipientRepository;
import fr.enimaloc.catapult.repository.NotificationRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        when(renderer.render(any())).thenAnswer(inv -> "<p>" + inv.getArgument(0) + "</p>");
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
                "T", "B", Notification.Severity.INFO, null, null, null, targetId);

        service.create(req, admin);

        verify(notifRepo).save(any(Notification.class));
        ArgumentCaptor<List<NotificationRecipient>> cap = ArgumentCaptor.forClass(List.class);
        verify(recipientRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0).getUser().getId()).isEqualTo(targetId);

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
                "T", "B", Notification.Severity.WARNING, null, null, null, null);

        service.create(req, admin);

        ArgumentCaptor<List<NotificationRecipient>> cap = ArgumentCaptor.forClass(List.class);
        verify(recipientRepo).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(2);
    }

    private UserAccount userWithStatus(UserAccount.Status s) {
        UserAccount u = new UserAccount();
        u.setId(UUID.randomUUID());
        u.setStatus(s);
        return u;
    }
}
