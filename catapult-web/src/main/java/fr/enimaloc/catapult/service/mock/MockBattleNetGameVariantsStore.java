package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantDto;
import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantRequest;
import fr.enimaloc.catapult.service.http.ApiClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The mock's Battle.net game variants: one store for the whole application, seeded with WoW: Forever disabled, as
 * migration V124 does. Answers like catapult-api: 201 created, 200 changed, 204 deleted; 400 invalid (or a changed
 * game or key), 404 unknown, 409 key already used by the game.
 */
class MockBattleNetGameVariantsStore {
    private static final Pattern FOURCC = Pattern.compile("[A-Za-z0-9]{1,4}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9-]{1,32}");
    private static final Set<String> MATCH_TYPES = Set.of("PREFIX", "CONTAINS");

    private final List<BattleNetGameVariantDto> variants = new ArrayList<>(List.of(
            new BattleNetGameVariantDto(UUID.fromString("00000000-0000-0000-0000-0000000000f1"), "WoW", "Forever",
                    "WoW:Forever", "PREFIX", List.of("Forever - "), "World of Warcraft: Forever", false,
                    Instant.parse("2026-11-21T00:00:00Z"), null)));

    synchronized List<BattleNetGameVariantDto> list() {
        return variants.stream().sorted(Comparator.comparing(BattleNetGameVariantDto::fourcc)
                .thenComparing(BattleNetGameVariantDto::variantKey)).toList();
    }

    synchronized ApiClient.Result<BattleNetGameVariantDto> create(BattleNetGameVariantRequest request,
                                                                  String updatedBy) {
        if (request == null || request.fourcc() == null || !FOURCC.matcher(request.fourcc()).matches()
                || request.variantKey() == null || !KEY.matcher(request.variantKey()).matches() || !valid(request)) {
            return new ApiClient.Result<>(400, null);
        }
        boolean taken = variants.stream().anyMatch(v -> v.fourcc().equals(request.fourcc())
                && v.variantKey().equals(request.variantKey()));
        if (taken) return new ApiClient.Result<>(409, null);
        BattleNetGameVariantDto created = dto(UUID.randomUUID(), request, updatedBy);
        variants.add(created);
        return new ApiClient.Result<>(201, created);
    }

    synchronized ApiClient.Result<BattleNetGameVariantDto> update(UUID id, BattleNetGameVariantRequest request,
                                                                  String updatedBy) {
        int index = indexOf(id);
        if (index < 0) return new ApiClient.Result<>(404, null);
        BattleNetGameVariantDto current = variants.get(index);
        if (request == null || !current.fourcc().equals(request.fourcc())
                || !current.variantKey().equals(request.variantKey()) || !valid(request)) {
            return new ApiClient.Result<>(400, null);
        }
        BattleNetGameVariantDto changed = dto(id, request, updatedBy);
        variants.set(index, changed);
        return new ApiClient.Result<>(200, changed);
    }

    synchronized ApiClient.Result<Void> delete(UUID id) {
        int index = indexOf(id);
        if (index < 0) return new ApiClient.Result<>(404, null);
        variants.remove(index);
        return new ApiClient.Result<>(204, null);
    }

    private int indexOf(UUID id) {
        for (int i = 0; i < variants.size(); i++) {
            if (variants.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static boolean valid(BattleNetGameVariantRequest request) {
        return MATCH_TYPES.contains(request.matchType()) && request.patterns() != null
                && !request.patterns().isEmpty() && request.patterns().size() <= 10
                && request.patterns().stream().allMatch(p -> p != null && !p.isBlank() && p.length() <= 100)
                && request.displayName() != null && !request.displayName().isBlank();
    }

    private static BattleNetGameVariantDto dto(UUID id, BattleNetGameVariantRequest request, String updatedBy) {
        return new BattleNetGameVariantDto(id, request.fourcc(), request.variantKey(),
                request.fourcc() + ":" + request.variantKey(), request.matchType(), List.copyOf(request.patterns()),
                request.displayName().strip(), request.enabled(), Instant.now(), updatedBy);
    }
}
