package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The severity levels of DTDD topics as the cached titles give them (DTDD lists no topic's levels on its own): what
 * the admin picks a TW mapping's least severe level from.
 */
@Component
@RequiredArgsConstructor
public class TwDtddTopicLevels {

    private static final TypeReference<List<String>> LEVELS = new TypeReference<>() {};

    private final DtddTopicsCacheRepository topicsRepo;
    private final JsonMapper json;

    /** Each topic's levels by its lower-cased name, mildest first; a topic no cached title has levels for absent. */
    public Map<String, List<String>> of(Collection<String> topics) {
        if (topics.isEmpty()) return Map.of();
        Map<String, List<String>> out = new HashMap<>();
        for (Object[] row : topicsRepo.findLevelsOf(topics.stream()
                .map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()))) {
            out.put((String) row[0], json.readValue((String) row[1], LEVELS));
        }
        return out;
    }
}
