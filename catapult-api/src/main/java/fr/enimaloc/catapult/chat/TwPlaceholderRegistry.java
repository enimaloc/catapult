package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.event.TwDefinitionsChangedEvent;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TwPlaceholderRegistry {

    private final TwDefinitionRepository repo;
    private volatile Set<String> knownPaths = Set.of();

    @PostConstruct
    public void load() {
        knownPaths = repo.findAllByEnabledTrueOrderBySortOrderAscIdAsc().stream()
            .map(d -> d.getId()).collect(Collectors.toUnmodifiableSet());
    }

    @EventListener
    public void onChanged(TwDefinitionsChangedEvent e) { load(); }

    public Set<String> getKnownPaths() { return knownPaths; }
}
