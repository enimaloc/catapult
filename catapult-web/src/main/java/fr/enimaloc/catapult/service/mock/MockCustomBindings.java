package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.channel.ExtensionSlotDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingScheduleDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingScheduleRequest;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingSaveResult;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.service.CustomBindingSave;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One mock member's custom bindings and personal API tokens (full and read-only), answering like catapult-api: a name is trimmed, 1-40
 * characters and unique whatever its case (400 invalid, 409 taken), a Twitch category is required (400), the free
 * limit at most on a free account (409), a single binding active at a time. Starts with two bindings, the first one active.
 */
class MockCustomBindings {
    /** The free tier's cap, which the mock account is held to unless premium. */
    static final int FREE_LIMIT = 5;
    private static final int NAME_MAX = 40;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final List<CustomBindingDto> bindings = new ArrayList<>();
    private final Map<UUID, List<ExtensionSlotDto>> extensions = new HashMap<>();
    private final Map<UUID, List<CustomBindingScheduleDto>> schedules = new HashMap<>();
    private UUID activeId;
    private Instant activatedAt;
    private Instant tokenCreatedAt;
    private Instant readTokenCreatedAt;
    /** Set from the mock admin page; free by default, like most accounts. */
    private boolean premium;

    MockCustomBindings() {
        CustomBindingDto art = new CustomBindingDto(UUID.randomUUID(), "Art", "509660", "Art",
                List.of(), List.of(), false);
        bindings.add(art);
        bindings.add(new CustomBindingDto(UUID.randomUUID(), "Développement", "1469308723", "Software and Game Development",
                List.of("Violence"), List.of(), true));
        activeId = art.id();
        activatedAt = Instant.now();
    }

    synchronized CustomBindingsStateDto state() {
        Map<UUID, List<CustomBindingScheduleDto>> current = new HashMap<>();
        schedules.forEach((id, slots) -> current.put(id, refreshed(id, slots)));
        return new CustomBindingsStateDto(true, List.copyOf(bindings), activeId, activatedAt,
                premium ? null : FREE_LIMIT, Map.copyOf(extensions), Map.copyOf(current), premium, true);
    }

    synchronized boolean isPremium() {
        return premium;
    }

    /** Losing premium suspends the slots a free account may not have; getting it back resumes them. */
    synchronized void setPremium(boolean premium) {
        this.premium = premium;
    }

    /**
     * The account's rules: anything when premium; when free, only a one-off slot on Twitch's "Special Events"
     * category, else PREMIUM_REQUIRED, but for a slot kept as it was saved (paused or not), as catapult-api does: it
     * stays, suspended while no longer allowed. An end not after its start, a slot longer than catapult-api allows
     * (24 hours repeated, 7 days once), or an unknown time zone, is INVALID. It is a demo, so a slot's next
     * start is its start while that is still ahead (null otherwise): the mock does not expand repeats.
     */
    synchronized CustomBindingSave<List<CustomBindingScheduleDto>> saveSchedules(UUID id, List<CustomBindingScheduleRequest> requests) {
        int index = indexOf(id);
        if (index < 0) return new CustomBindingSave<>(404, null, null);
        List<CustomBindingScheduleRequest> slots = requests == null ? List.of() : requests;
        for (CustomBindingScheduleRequest slot : slots) {
            if (slot.startsAt() == null || slot.endsAt() == null || !slot.endsAt().isAfter(slot.startsAt())
                    || tooLong(slot)
                    || (!blank(slot.timeZone()) && !ZoneId.getAvailableZoneIds().contains(slot.timeZone()))) {
                return new CustomBindingSave<>(400, null, ErrorCode.INVALID);
            }
        }
        Map<UUID, CustomBindingScheduleDto> saved = new HashMap<>();
        schedules.getOrDefault(id, List.of()).forEach(slot -> saved.put(slot.id(), slot));
        List<CustomBindingScheduleDto> kept = new ArrayList<>();
        for (CustomBindingScheduleRequest slot : slots) {
            boolean allowed = allowed(bindings.get(index), slot.repeat());
            boolean unchanged = slot.id() != null && sameSlot(saved.get(slot.id()), slot);
            if (!allowed && !unchanged) return new CustomBindingSave<>(403, null, ErrorCode.PREMIUM_REQUIRED);
            kept.add(scheduleDto(slot, !allowed));
        }
        if (kept.isEmpty()) schedules.remove(id);
        else schedules.put(id, kept);
        return new CustomBindingSave<>(200, kept, null);
    }

    synchronized CustomBindingSave<CustomBindingDto> create(CustomBindingRequest request) {
        CustomBindingSave<CustomBindingDto> refused = refusal(request, null);
        if (refused != null) return refused;
        if (!premium && bindings.size() >= FREE_LIMIT) return new CustomBindingSave<>(409, null, ErrorCode.LIMIT_REACHED);
        CustomBindingDto created = dto(UUID.randomUUID(), request);
        bindings.add(created);
        return new CustomBindingSave<>(201, created, null);
    }

    /** Whether the edit re-applied is told for the active binding only. */
    synchronized CustomBindingSave<CustomBindingSaveResult> update(UUID id, CustomBindingRequest request) {
        int index = indexOf(id);
        if (index < 0) return new CustomBindingSave<>(404, null, null);
        CustomBindingSave<CustomBindingSaveResult> refused = refusal(request, id);
        if (refused != null) return refused;
        CustomBindingDto saved = dto(id, request);
        bindings.set(index, saved);
        return new CustomBindingSave<>(200, new CustomBindingSaveResult(saved, id.equals(activeId) ? CustomBindingSaveResult.APPLIED : null), null);
    }

    /** 204, or 404 when unknown; the active binding is deactivated first. */
    synchronized int delete(UUID id) {
        int index = indexOf(id);
        if (index < 0) return 404;
        if (id.equals(activeId)) deactivate();
        bindings.remove(index);
        extensions.remove(id);
        schedules.remove(id);
        return 204;
    }

    /** Whether {@code id} exists; it then replaces the active binding. */
    synchronized boolean activate(UUID id) {
        if (indexOf(id) < 0) return false;
        activeId = id;
        activatedAt = Instant.now();
        return true;
    }

    synchronized void deactivate() {
        activeId = null;
        activatedAt = null;
    }

    synchronized ApiTokenStatusDto tokenStatus() {
        return new ApiTokenStatusDto(tokenCreatedAt != null, tokenCreatedAt);
    }

    /** A new token replacing the current one: the only time it is known. */
    synchronized ApiTokenCreatedDto regenerateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        tokenCreatedAt = Instant.now();
        return new ApiTokenCreatedDto("cat_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), tokenCreatedAt);
    }

    synchronized void revokeToken() {
        tokenCreatedAt = null;
    }

    synchronized ApiTokenStatusDto readTokenStatus() {
        return new ApiTokenStatusDto(readTokenCreatedAt != null, readTokenCreatedAt);
    }

    /** A new read-only token replacing the current one: the only time it is known. */
    synchronized ApiTokenCreatedDto regenerateReadToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        readTokenCreatedAt = Instant.now();
        return new ApiTokenCreatedDto("catr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), readTokenCreatedAt);
    }

    synchronized void revokeReadToken() {
        readTokenCreatedAt = null;
    }

    /** 204 once kept, 404 for an unknown binding; an empty layout keeps nothing. */
    synchronized int saveExtensions(UUID id, List<ExtensionSlotDto> slots) {
        if (indexOf(id) < 0) return 404;
        if (slots.isEmpty()) extensions.remove(id);
        else extensions.put(id, List.copyOf(slots));
        return 204;
    }

    private int indexOf(UUID id) {
        for (int i = 0; i < bindings.size(); i++) if (bindings.get(i).id().equals(id)) return i;
        return -1;
    }

    /** The refusal catapult-api would give: 400 invalid, or 409 name taken by another binding than {@code self}; else null. */
    private <T> CustomBindingSave<T> refusal(CustomBindingRequest request, UUID self) {
        String name = request == null || request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > NAME_MAX || blank(request.twitchGameId()) || blank(request.twitchGameName())) {
            return new CustomBindingSave<>(400, null, ErrorCode.INVALID);
        }
        boolean taken = bindings.stream().anyMatch(b -> !b.id().equals(self) && b.name().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
        return taken ? new CustomBindingSave<>(409, null, ErrorCode.NAME_TAKEN) : null;
    }

    /** Whether the account may have a slot repeating so on {@code binding}, as catapult-api's PremiumService decides. */
    private boolean allowed(CustomBindingDto binding, String repeat) {
        return premium || CustomBindingScheduleDto.FREE_TWITCH_GAME_ID.equals(binding.twitchGameId())
                && (repeat == null || "NONE".equals(repeat));
    }

    /** {@code slots} as the account's current rights make them: a slot is suspended while no longer allowed. */
    private List<CustomBindingScheduleDto> refreshed(UUID id, List<CustomBindingScheduleDto> slots) {
        int index = indexOf(id);
        if (index < 0) return slots;
        return slots.stream().map(slot -> {
            boolean suspended = !allowed(bindings.get(index), slot.repeat());
            Instant next = slot.startsAt().atZone(ZoneId.of(slot.timeZone())).toInstant();
            return new CustomBindingScheduleDto(slot.id(), slot.startsAt(), slot.endsAt(), slot.repeat(), slot.days(),
                    slot.repeatUntil(), slot.timeZone(), slot.paused(), suspended,
                    !suspended && next.isAfter(Instant.now()) ? next : null);
        }).toList();
    }

    /** Whether {@code slot} is {@code saved} as it was, but for its pause. */
    private static boolean sameSlot(CustomBindingScheduleDto saved, CustomBindingScheduleRequest slot) {
        return saved != null && saved.startsAt().equals(slot.startsAt()) && saved.endsAt().equals(slot.endsAt())
                && saved.repeat().equals(slot.repeat() == null ? "NONE" : slot.repeat())
                && saved.days().equals(sorted(slot.days()))
                && Objects.equals(saved.repeatUntil(), slot.repeatUntil())
                && saved.timeZone().equals(zone(slot).getId());
    }

    private static CustomBindingScheduleDto scheduleDto(CustomBindingScheduleRequest slot, boolean suspended) {
        LocalDateTime start = slot.startsAt();
        ZoneId zone = zone(slot);
        Instant next = start.atZone(zone).toInstant();
        return new CustomBindingScheduleDto(slot.id() == null ? UUID.randomUUID() : slot.id(), start, slot.endsAt(),
                slot.repeat() == null ? "NONE" : slot.repeat(), sorted(slot.days()), slot.repeatUntil(), zone.getId(),
                slot.paused(), suspended, !suspended && next.isAfter(Instant.now()) ? next : null);
    }

    /** Longer than catapult-api takes a slot: 24 hours when repeated, 7 days once. */
    private static boolean tooLong(CustomBindingScheduleRequest slot) {
        boolean once = slot.repeat() == null || "NONE".equals(slot.repeat());
        return Duration.between(slot.startsAt(), slot.endsAt()).compareTo(once ? Duration.ofDays(7) : Duration.ofHours(24)) > 0;
    }

    /** The zone a slot was typed in: the browser's, else the default one. */
    private static ZoneId zone(CustomBindingScheduleRequest slot) {
        return ZoneId.of(blank(slot.timeZone()) ? CustomBindingScheduleDto.ZONE : slot.timeZone());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static CustomBindingDto dto(UUID id, CustomBindingRequest request) {
        return new CustomBindingDto(id, request.name().trim(), request.twitchGameId(), request.twitchGameName(),
                sorted(request.ccls()), sorted(request.tws()), request.uninterruptible());
    }

    private static List<String> sorted(java.util.Collection<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }
}
