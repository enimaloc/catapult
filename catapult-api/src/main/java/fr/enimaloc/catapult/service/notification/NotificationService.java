package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.event.NotificationAllReadEvent;
import fr.enimaloc.catapult.event.NotificationCreatedEvent;
import fr.enimaloc.catapult.event.NotificationDeletedEvent;
import fr.enimaloc.catapult.event.NotificationReadEvent;
import fr.enimaloc.catapult.repository.NotificationRecipientRepository;
import fr.enimaloc.catapult.repository.NotificationRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notifRepo;
    private final NotificationRecipientRepository recipientRepo;
    private final UserAccountRepository userRepo;
    private final NotificationRenderer renderer;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public NotificationDto create(CreateRequest req, UserAccount actor) {
        if (req.ctaUrl() != null && !NotificationRenderer.isValidCtaUrl(req.ctaUrl())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid CTA URL");
        }
        Notification n = new Notification();
        n.setTitle(req.title());
        n.setBody(req.body());
        n.setSeverity(req.severity());
        n.setCtaUrl(req.ctaUrl());
        n.setCtaLabel(req.ctaLabel());
        n.setExpiresAt(req.expiresAt());
        n.setCreatedAt(Instant.now());
        n.setCreatedBy(actor.getId());
        n.setAudience(req.targetUserId() == null ? Notification.Audience.BROADCAST : Notification.Audience.TARGETED);
        notifRepo.save(n);

        List<UserAccount> recipients;
        if (req.targetUserId() != null) {
            recipients = List.of(userRepo.findById(req.targetUserId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        } else {
            recipients = userRepo.findByStatus(UserAccount.Status.ACTIVE);
        }
        List<NotificationRecipient> rows = new ArrayList<>(recipients.size());
        for (UserAccount user : recipients) {
            NotificationRecipient r = new NotificationRecipient();
            r.setId(new NotificationRecipientId(n.getId(), user.getId()));
            r.setNotification(n);
            r.setUser(user);
            rows.add(r);
        }
        recipientRepo.saveAll(rows);

        NotificationDto dto = toDto(n, false);
        List<UUID> ids = recipients.stream().map(UserAccount::getId).toList();
        publisher.publishEvent(new NotificationCreatedEvent(this, ids, dto));
        return dto;
    }

    @Transactional
    public Page<NotificationDto> listForUser(UUID userId, Pageable pageable) {
        return recipientRepo.findByUserIdOrderByNotificationCreatedAtDesc(userId, pageable)
                .map(r -> toDto(r.getNotification(), r.getReadAt() != null));
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        NotificationRecipient r = recipientRepo.findById(new NotificationRecipientId(notificationId, userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (r.getReadAt() == null) {
            r.setReadAt(Instant.now());
        }
        long unread = recipientRepo.countByUserIdAndReadAtIsNull(userId);
        publisher.publishEvent(new NotificationReadEvent(userId, notificationId, unread));
    }

    @Transactional
    public void markAllRead(UUID userId) {
        recipientRepo.markAllReadForUser(userId, Instant.now());
        publisher.publishEvent(new NotificationAllReadEvent(userId));
    }

    @Transactional
    public void delete(UUID userId, UUID notificationId) {
        recipientRepo.findById(new NotificationRecipientId(notificationId, userId))
                .ifPresent(recipientRepo::delete);
        long unread = recipientRepo.countByUserIdAndReadAtIsNull(userId);
        publisher.publishEvent(new NotificationDeletedEvent(userId, notificationId, unread));
    }

    public long unreadCount(UUID userId) {
        return recipientRepo.countByUserIdAndReadAtIsNull(userId);
    }

    public long countUnread(UUID userId) {
        return unreadCount(userId);
    }

    public List<NotificationDto> findRecent(UUID userId, int size) {
        return recipientRepo.findByUserIdOrderByNotificationCreatedAtDesc(userId, PageRequest.of(0, size))
                .map(r -> toDto(r.getNotification(), r.getReadAt() != null))
                .toList();
    }

    private NotificationDto toDto(Notification n, boolean read) {
        return new NotificationDto(
                n.getId(),
                n.getTitle(),
                renderer.render(n.getBody()),
                n.getSeverity(),
                n.getCtaUrl(),
                n.getCtaLabel(),
                n.getExpiresAt(),
                n.getCreatedAt(),
                read
        );
    }

    public record CreateRequest(
            String title,
            String body,
            Notification.Severity severity,
            String ctaUrl,
            String ctaLabel,
            Instant expiresAt,
            UUID targetUserId
    ) {}
}
