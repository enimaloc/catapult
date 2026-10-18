package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewDto;
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
import fr.enimaloc.catapult.service.metrics.CatapultApiMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final int TITLE_MAX = 255, CTA_LABEL_MAX = 64, CTA_URL_MAX = 512;

    private final NotificationRepository notifRepo;
    private final NotificationRecipientRepository recipientRepo;
    private final UserAccountRepository userRepo;
    private final NotificationRenderer renderer;
    private final ApplicationEventPublisher publisher;

    @Autowired(required = false)
    private CatapultApiMetrics apiMetrics;

    @Transactional
    public NotificationDto create(CreateRequest req, UserAccount actor) {
        validate(req);
        List<UserAccount> recipients;
        if (req.targetUserId() != null) {
            recipients = List.of(userRepo.findById(req.targetUserId())
                    .orElseThrow(() -> bad("Unknown member")));
        } else {
            recipients = userRepo.findByStatus(UserAccount.Status.ACTIVE);
        }
        boolean hasCta = req.ctaUrl() != null && !req.ctaUrl().isBlank();
        Notification n = new Notification();
        n.setTitle(req.title());
        n.setBody(req.body());
        n.setSeverity(req.severity());
        n.setCtaUrl(hasCta ? req.ctaUrl() : null);
        n.setCtaLabel(hasCta ? req.ctaLabel() : null);
        n.setExpiresAt(req.expiresAt());
        n.setCreatedAt(Instant.now());
        n.setCreatedBy(actor.getId());
        n.setAudience(req.targetUserId() == null ? Notification.Audience.BROADCAST : Notification.Audience.TARGETED);
        notifRepo.save(n);

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
        if (apiMetrics != null) apiMetrics.recordNotificationAction("created");
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
        if (apiMetrics != null) apiMetrics.recordNotificationAction("read");
    }

    @Transactional
    public void markAllRead(UUID userId) {
        recipientRepo.markAllReadForUser(userId, Instant.now());
        publisher.publishEvent(new NotificationAllReadEvent(userId));
        if (apiMetrics != null) apiMetrics.recordNotificationAction("markAllRead");
    }

    @Transactional
    public void delete(UUID userId, UUID notificationId) {
        recipientRepo.findById(new NotificationRecipientId(notificationId, userId))
                .ifPresent(recipientRepo::delete);
        long unread = recipientRepo.countByUserIdAndReadAtIsNull(userId);
        publisher.publishEvent(new NotificationDeletedEvent(userId, notificationId, unread));
        if (apiMetrics != null) apiMetrics.recordNotificationAction("deleted");
    }

    public long unreadCount(UUID userId) {
        return recipientRepo.countByUserIdAndReadAtIsNull(userId);
    }

    public long countUnread(UUID userId) {
        return unreadCount(userId);
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> findRecent(UUID userId, int size) {
        return recipientRepo.findByUserIdOrderByNotificationCreatedAtDesc(userId, PageRequest.of(0, size))
                .map(r -> toDto(r.getNotification(), r.getReadAt() != null))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<AdminNotificationDto> adminPage(Pageable pageable) {
        return notifRepo.findAllByOrderByCreatedAtDesc(pageable).map(this::toAdminDto);
    }

    public NotificationPreviewDto preview(String body) {
        return new NotificationPreviewDto(renderer.render(body));
    }

    private AdminNotificationDto toAdminDto(Notification n) {
        UUID targetId = null;
        String targetLogin = null;
        if (n.getAudience() == Notification.Audience.TARGETED) {
            // a targeted notification has exactly one recipient
            UserAccount target = recipientRepo.findByIdNotificationId(n.getId()).stream()
                    .findFirst().map(NotificationRecipient::getUser).orElse(null);
            if (target != null) {
                targetId = target.getId();
                targetLogin = target.getTwitchUsername();
            }
        }
        return new AdminNotificationDto(n.getId(), n.getTitle(), n.getBody(), renderer.render(n.getBody()),
                n.getSeverity(), n.getCtaUrl(), n.getCtaLabel(), n.getExpiresAt(), n.getCreatedAt(),
                n.getAudience().name(), targetId, targetLogin);
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

    private static void validate(CreateRequest req) {
        if (req.title() == null || req.title().isBlank()) throw bad("A title is required");
        if (req.title().length() > TITLE_MAX) throw bad("The title is longer than " + TITLE_MAX + " characters");
        if (req.body() == null || req.body().isBlank()) throw bad("A body is required");
        if (req.severity() == null) throw bad("A severity is required");
        boolean url = req.ctaUrl() != null && !req.ctaUrl().isBlank();
        boolean label = req.ctaLabel() != null && !req.ctaLabel().isBlank();
        if (url != label) throw bad("A call to action needs both a label and a URL");
        if (label && req.ctaLabel().length() > CTA_LABEL_MAX) throw bad("The call to action label is longer than " + CTA_LABEL_MAX + " characters");
        if (url && (req.ctaUrl().length() > CTA_URL_MAX || !NotificationRenderer.isValidCtaUrl(req.ctaUrl()))) throw bad("Invalid CTA URL");
        if (req.expiresAt() != null && !req.expiresAt().isAfter(Instant.now())) throw bad("The expiry must be in the future");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record CreateRequest(
            String title,
            String body,
            Severity severity,
            String ctaUrl,
            String ctaLabel,
            Instant expiresAt,
            UUID targetUserId
    ) {}
}
