package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.FlagKeyDto;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The mock's member custom flags: one store for the whole application. Answers like catapult-api:
 * 204, 400 for a blank key. A key is trimmed, a flag set again keeps its place with its new value,
 * and deleting a flag the member doesn't have is quietly a success.
 */
class MockTargetingStore {
    private final Map<UUID, Map<String, String>> flags = new HashMap<>();

    synchronized List<MemberTargetingDto.Flag> flagsOf(UUID memberId) {
        List<MemberTargetingDto.Flag> result = new ArrayList<>();
        flags.getOrDefault(memberId, Map.of()).forEach((key, value) -> result.add(new MemberTargetingDto.Flag(key, value)));
        return result;
    }

    synchronized int setFlag(UUID memberId, String key, String value) {
        if (key == null || key.isBlank()) return 400;
        flags.computeIfAbsent(memberId, id -> new LinkedHashMap<>()).put(key.trim(), value);
        return 204;
    }

    synchronized int deleteFlag(UUID memberId, String key) {
        Map<String, String> own = flags.get(memberId);
        if (own != null) own.remove(key);
        return 204;
    }

    /** Every key some member carries, with the distinct non-null values it takes, both sorted. */
    synchronized List<FlagKeyDto> flagKeys() {
        Map<String, TreeSet<String>> byKey = new TreeMap<>();
        flags.values().forEach(own -> own.forEach((key, value) -> {
            TreeSet<String> values = byKey.computeIfAbsent(key, k -> new TreeSet<>());
            if (value != null) values.add(value);
        }));
        return byKey.entrySet().stream().map(e -> new FlagKeyDto(e.getKey(), List.copyOf(e.getValue()))).toList();
    }
}
