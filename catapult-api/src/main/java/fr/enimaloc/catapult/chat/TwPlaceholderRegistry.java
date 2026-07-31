package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.event.TwDefinitionsChangedEvent;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TwPlaceholderRegistry {

    private final TwDefinitionRepository repo;
    private volatile Set<String> knownPaths = Set.of();
    private volatile List<Map<String, String>> allOptions = List.of();

    @PostConstruct
    public void load() {
        knownPaths = repo.findAllByEnabledTrueOrderBySortOrderAscIdAsc().stream()
            .map(d -> d.getId()).collect(Collectors.toUnmodifiableSet());
        allOptions = repo.findAllByOrderBySortOrderAscIdAsc().stream()
            .map(d -> {
                Map<String, String> option = new LinkedHashMap<>();
                option.put("id", d.getId());
                option.put("label", d.getLabel());
                return Map.copyOf(option);
            })
            .toList();
    }

    @EventListener
    public void onChanged(TwDefinitionsChangedEvent e) { load(); }

    public Set<String> getKnownPaths() { return knownPaths; }

    /** Every registered TW definition (including disabled ones), as {@code {id, label}} pairs — used by the DSL's {@code allTws} named list so command authors can iterate the full catalog rather than only what's currently active. */
    public List<Map<String, String>> getAllOptions() { return allOptions; }
}
