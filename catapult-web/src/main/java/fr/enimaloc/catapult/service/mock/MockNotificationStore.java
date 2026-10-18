package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationSnapshotDto;
import fr.enimaloc.catapult.common.dto.notification.Severity;
import fr.enimaloc.catapult.event.notification.BroadcastStreamEvent;
import fr.enimaloc.catapult.event.notification.NotificationStreamEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The mock's notifications and broadcasts: one store for the whole application, so what an admin
 * sends reaches the other mock sessions' bells. Mirrors catapult-api's validation and its rate
 * limit; the body has no Markdown here, only escaping and line breaks.
 */
class MockNotificationStore {
    private static final int PAGE_SIZE = 20;
    private static final int SNAPSHOT_SIZE = 10;
    private static final int BROADCASTS_PER_MINUTE = 10;

    private final ApplicationEventPublisher publisher;
    /** Per account, oldest first (listings reverse it). */
    private final Map<UUID, List<NotificationDto>> byAccount = new LinkedHashMap<>();
    /** What admins sent, oldest first. */
    private final List<AdminNotificationDto> sent = new ArrayList<>();
    private final Deque<Instant> broadcasts = new ArrayDeque<>();

    private final Clock clock;
    /** Whether an account the store has not seen yet is a mock member. */
    private final Predicate<UUID> knownMember;

    MockNotificationStore(ApplicationEventPublisher publisher) {
        this(publisher, Clock.systemUTC(), id -> false);
    }

    MockNotificationStore(ApplicationEventPublisher publisher, Clock clock, Predicate<UUID> knownMember) {
        this.publisher = publisher;
        this.clock = clock;
        this.knownMember = knownMember;
    }

    synchronized NotificationSnapshotDto snapshot(UUID account) {
        List<NotificationDto> live = live(account);
        return new NotificationSnapshotDto(live.stream().limit(SNAPSHOT_SIZE).toList(), unread(live));
    }

    synchronized AdminPage<NotificationDto> page(UUID account, int page) {
        return paged(live(account), page);
    }

    synchronized int markRead(UUID account, UUID id) {
        List<NotificationDto> list = notifications(account);
        for (int i = 0; i < list.size(); i++) {
            NotificationDto n = list.get(i);
            if (!n.id().equals(id)) continue;
            list.set(i, withRead(n));
            publish(account, NotificationStreamEvent.READ, counted(id, unread(live(account))));
            return 204;
        }
        return 404;
    }

    synchronized int markAllRead(UUID account) {
        List<NotificationDto> list = notifications(account);
        list.replaceAll(n -> n.read() ? n : withRead(n));
        publish(account, NotificationStreamEvent.ALL_READ, Map.of("unreadCount", 0L));
        return 204;
    }

    /** Sends to {@code target}, or to every account seen so far when null; catapult-api's status. */
    synchronized int create(String title, String body, Severity severity, String ctaUrl, String ctaLabel,
                            Instant expiresAt, UUID target) {
        if (!valid(title, body, severity, ctaUrl, ctaLabel, expiresAt)) return 400;
        if (target != null && !byAccount.containsKey(target) && !knownMember.test(target)) return 400;
        ctaUrl = blankToNull(ctaUrl);
        ctaLabel = blankToNull(ctaLabel);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String html = html(body);
        NotificationDto dto = new NotificationDto(id, title, html, severity, ctaUrl, ctaLabel, expiresAt, now, false);
        List<UUID> recipients = target == null ? List.copyOf(byAccount.keySet()) : List.of(target);
        for (UUID account : recipients) {
            notifications(account).add(dto);
            publish(account, NotificationStreamEvent.CREATED, dto);
        }
        sent.add(new AdminNotificationDto(id, title, body, html, severity, ctaUrl, ctaLabel, expiresAt, now,
                target == null ? "ALL" : "USER", target, null));
        return 201;
    }

    synchronized int delete(UUID id) {
        boolean known = sent.removeIf(n -> n.id().equals(id));
        for (Map.Entry<UUID, List<NotificationDto>> entry : byAccount.entrySet()) {
            if (!entry.getValue().removeIf(n -> n.id().equals(id))) continue;
            known = true;
            publish(entry.getKey(), NotificationStreamEvent.DELETED, counted(id, unread(live(entry.getKey()))));
        }
        return known ? 204 : 404;
    }

    synchronized AdminPage<AdminNotificationDto> adminPage(int page) {
        List<AdminNotificationDto> newestFirst = new ArrayList<>(sent);
        Collections.reverse(newestFirst);
        return paged(newestFirst, page);
    }

    /** The mock has no Markdown: the body escaped, its line breaks kept. */
    String preview(String body) {
        return html(body);
    }

    /** Publishes the broadcast to every stream; 429 past ten a minute, as the API. */
    synchronized int broadcast(BroadcastRequestDto request) {
        Instant now = clock.instant();
        while (!broadcasts.isEmpty() && broadcasts.peekFirst().isBefore(now.minusSeconds(60))) broadcasts.pollFirst();
        if (broadcasts.size() >= BROADCASTS_PER_MINUTE) return 429;
        broadcasts.addLast(now);
        publisher.publishEvent(new BroadcastStreamEvent(request.name(), request.data()));
        return 202;
    }

    // --- internals: callers hold the lock ---

    /** The account's list, seeded with a welcome notification the first time it is seen. */
    private List<NotificationDto> notifications(UUID account) {
        return byAccount.computeIfAbsent(account, a -> {
            List<NotificationDto> list = new ArrayList<>();
            list.add(new NotificationDto(UUID.randomUUID(), "Bienvenue sur Catapult", "Ceci est une notification de test.",
                    Severity.INFO, null, null, null, clock.instant(), false));
            return list;
        });
    }

    /** The account's unexpired notifications, newest first. */
    private List<NotificationDto> live(UUID account) {
        Instant now = clock.instant();
        List<NotificationDto> live = new ArrayList<>(notifications(account).stream()
                .filter(n -> n.expiresAt() == null || n.expiresAt().isAfter(now)).toList());
        Collections.reverse(live);
        return live;
    }

    private static long unread(List<NotificationDto> live) {
        return live.stream().filter(n -> !n.read()).count();
    }

    private static NotificationDto withRead(NotificationDto n) {
        return new NotificationDto(n.id(), n.title(), n.bodyHtml(), n.severity(), n.ctaUrl(), n.ctaLabel(),
                n.expiresAt(), n.createdAt(), true);
    }

    private static Map<String, Object> counted(UUID id, long unreadCount) {
        return Map.of("notificationId", id.toString(), "unreadCount", unreadCount);
    }

    private static <T> AdminPage<T> paged(List<T> all, int requestedPage) {
        int page = Math.max(requestedPage, 0);
        int from = Math.min(page * PAGE_SIZE, all.size());
        int totalPages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        return new AdminPage<>(all.subList(from, Math.min(from + PAGE_SIZE, all.size())), page, totalPages, all.size());
    }

    private void publish(UUID account, String name, Object data) {
        publisher.publishEvent(new NotificationStreamEvent(account, name, data));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean valid(String title, String body, Severity severity, String ctaUrl, String ctaLabel, Instant expiresAt) {
        if (title == null || title.isBlank() || title.length() > 255) return false;
        if (body == null || body.isBlank() || severity == null) return false;
        boolean hasUrl = ctaUrl != null && !ctaUrl.isBlank();
        boolean hasLabel = ctaLabel != null && !ctaLabel.isBlank();
        if (hasUrl != hasLabel) return false;
        if (hasUrl && (ctaUrl.length() > 512 || ctaLabel.length() > 64 || !isHttp(ctaUrl))) return false;
        return expiresAt == null || expiresAt.isAfter(clock.instant());
    }

    private static boolean isHttp(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String html(String body) {
        return body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r\n", "\n").replace("\n", "<br>");
    }
}
