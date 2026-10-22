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
import fr.enimaloc.catapult.event.NotificationDeliveryPendingEvent;
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
    private final NotificationDeliveryService deliveries;

    @Autowired(required = false)
    private CatapultApiMetrics apiMetrics;

    @Transactional
    public NotificationDto create(CreateRequest req, UserAccount actor) {
        validate(req);
        List<UserAccount> recipients;
        if (req.targetUserId() != null) {
            recipients = List.of(userRepo.findById(req.targetUserId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown member")));
        } else {
            recipients = userRepo.findByStatus(UserAccount.Status.ACTIVE);
        }
        List<UUID> pendingTwitchat = new ArrayList<>();
        NotificationDto dto = send(req, actor, recipients, req.targetUserId() == null, pendingTwitchat);
        announcePending(pendingTwitchat);
        return dto;
    }

    /** What {@link CreateRequest#ctaUrl()} may carry, replaced by each recipient's channel by {@link #createForEach}. */
    public static final String CHANNEL_PLACEHOLDER = "{channel}";

    /**
     * One notification per member of {@code recipients}, each targeted to them alone: its call to
     * action may name their own channel ({@link #CHANNEL_PLACEHOLDER}, e.g. {@code
     * https://…/channel/{channel}#integrations}). Returns how many were sent.
     */
    @Transactional
    public int createForEach(CreateRequest req, UserAccount actor, List<UserAccount> recipients) {
        validate(withChannel(req, "channel"));
        List<UUID> pendingTwitchat = new ArrayList<>();
        recipients.forEach(user -> send(withChannel(req, user.getTwitchUsername()), actor, List.of(user), false, pendingTwitchat));
        announcePending(pendingTwitchat);
        return recipients.size();
    }

    private static CreateRequest withChannel(CreateRequest req, String channel) {
        if (req.ctaUrl() == null) return req;
        return new CreateRequest(req.title(), req.body(), req.severity(),
                req.ctaUrl().replace(CHANNEL_PLACEHOLDER, channel), req.ctaLabel(), req.expiresAt(), req.targetUserId());
    }

    /**
     * One notification shared by every member of {@code recipients}, targeted to them (not a broadcast).
     * Returns how many were told; none stores nothing.
     */
    @Transactional
    public int createForAll(CreateRequest req, UserAccount actor, List<UserAccount> recipients) {
        validate(req);
        if (recipients.isEmpty()) return 0;
        List<UUID> pendingTwitchat = new ArrayList<>();
        send(req, actor, recipients, false, pendingTwitchat);
        announcePending(pendingTwitchat);
        return recipients.size();
    }

    /**
     * Stores the notification for {@code recipients} and their pending deliveries, adding to
     * {@code pendingTwitchat} the members with a Twitchat one (announced once by the caller).
     */
    private NotificationDto send(CreateRequest req, UserAccount actor, List<UserAccount> recipients, boolean broadcast,
                                 List<UUID> pendingTwitchat) {
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
        n.setAudience(broadcast ? Notification.Audience.BROADCAST : Notification.Audience.TARGETED);
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
        // Also through the channels the recipients opted in to: always after their rows, which it selects from
        pendingTwitchat.addAll(deliveries.createFor(n.getId()));

        NotificationDto dto = toDto(n, false);
        List<UUID> ids = recipients.stream().map(UserAccount::getId).toList();
        publisher.publishEvent(new NotificationCreatedEvent(this, ids, dto));
        if (apiMetrics != null) apiMetrics.recordNotificationAction("created");
        return dto;
    }

    /** catapult-web flushes, after commit, the members it holds a Twitchat page or widget open for. */
    private void announcePending(List<UUID> pendingTwitchat) {
        if (!pendingTwitchat.isEmpty()) {
            publisher.publishEvent(new NotificationDeliveryPendingEvent(List.copyOf(pendingTwitchat)));
        }
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
        deliveries.cancel(userId, notificationId);
        long unread = recipientRepo.countByUserIdAndReadAtIsNull(userId);
        publisher.publishEvent(new NotificationReadEvent(userId, notificationId, unread));
        if (apiMetrics != null) apiMetrics.recordNotificationAction("read");
    }

    @Transactional
    public void markAllRead(UUID userId) {
        recipientRepo.markAllReadForUser(userId, Instant.now());
        deliveries.cancelAll(userId);
        publisher.publishEvent(new NotificationAllReadEvent(userId));
        if (apiMetrics != null) apiMetrics.recordNotificationAction("markAllRead");
    }

    @Transactional
    public void delete(UUID userId, UUID notificationId) {
        recipientRepo.findById(new NotificationRecipientId(notificationId, userId))
                .ifPresent(recipientRepo::delete);
        deliveries.cancel(userId, notificationId);
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
        long recipients = 0;
        if (n.getAudience() == Notification.Audience.TARGETED) {
            // counted, not loaded: a shared notification can have thousands of recipients; only a lone one is named
            recipients = recipientRepo.countByIdNotificationId(n.getId());
            UserAccount target = recipients == 1
                    ? recipientRepo.findByIdNotificationId(n.getId()).stream().findFirst().map(NotificationRecipient::getUser).orElse(null)
                    : null;
            if (target != null) {
                targetId = target.getId();
                targetLogin = target.getTwitchUsername();
            }
        }
        return new AdminNotificationDto(n.getId(), n.getTitle(), n.getBody(), renderer.render(n.getBody()),
                n.getSeverity(), n.getCtaUrl(), n.getCtaLabel(), n.getExpiresAt(), n.getCreatedAt(),
                n.getAudience().name(), targetId, targetLogin, recipients);
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
