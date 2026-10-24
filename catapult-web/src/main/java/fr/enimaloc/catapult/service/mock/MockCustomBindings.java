package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingSaveResult;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.service.http.ApiClient;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One mock member's custom bindings and personal API token, answering like catapult-api: a name is trimmed, 1-40
 * characters and unique whatever its case (400 invalid, 409 taken), a Twitch category is required (400), 20 at most
 * (409), a single binding active at a time. Starts with "Art" (active) and "Développement".
 */
class MockCustomBindings {
    static final int MAX = 20;
    private static final int NAME_MAX = 40;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final List<CustomBindingDto> bindings = new ArrayList<>();
    private UUID activeId;
    private Instant activatedAt;
    private Instant tokenCreatedAt;

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
        return new CustomBindingsStateDto(true, List.copyOf(bindings), activeId, activatedAt);
    }

    synchronized ApiClient.Result<CustomBindingDto> create(CustomBindingRequest request) {
        int invalid = invalid(request, null);
        if (invalid != 0) return new ApiClient.Result<>(invalid, null);
        if (bindings.size() >= MAX) return new ApiClient.Result<>(409, null);
        CustomBindingDto created = dto(UUID.randomUUID(), request);
        bindings.add(created);
        return new ApiClient.Result<>(201, created);
    }

    /** Whether the edit re-applied is told for the active binding only. */
    synchronized ApiClient.Result<CustomBindingSaveResult> update(UUID id, CustomBindingRequest request) {
        int index = indexOf(id);
        if (index < 0) return new ApiClient.Result<>(404, null);
        int invalid = invalid(request, id);
        if (invalid != 0) return new ApiClient.Result<>(invalid, null);
        CustomBindingDto saved = dto(id, request);
        bindings.set(index, saved);
        return new ApiClient.Result<>(200, new CustomBindingSaveResult(saved, id.equals(activeId) ? CustomBindingSaveResult.APPLIED : null));
    }

    /** 204, or 404 when unknown; the active binding is deactivated first. */
    synchronized int delete(UUID id) {
        int index = indexOf(id);
        if (index < 0) return 404;
        if (id.equals(activeId)) deactivate();
        bindings.remove(index);
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

    private int indexOf(UUID id) {
        for (int i = 0; i < bindings.size(); i++) if (bindings.get(i).id().equals(id)) return i;
        return -1;
    }

    /** 400 for an invalid request, 409 for a name taken by another binding than {@code self}; 0 when fine. */
    private int invalid(CustomBindingRequest request, UUID self) {
        String name = request == null || request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > NAME_MAX) return 400;
        if (blank(request.twitchGameId()) || blank(request.twitchGameName())) return 400;
        boolean taken = bindings.stream().anyMatch(b -> !b.id().equals(self) && b.name().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
        return taken ? 409 : 0;
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
