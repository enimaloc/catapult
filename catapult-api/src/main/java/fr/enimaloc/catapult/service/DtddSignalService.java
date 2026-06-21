package fr.enimaloc.catapult.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
@ConditionalOnBooleanProperty("dtdd.enabled")
@RequiredArgsConstructor
public class DtddSignalService {

    private final DtddService dtddService;

    /** Returns the union of DTDD topics marked yes and mostly for the given game. */
    public Set<String> getYesMostlyTopics(String igdbId, String name) {
        return dtddService.getTopics(igdbId, name).map(t -> {
            Set<String> out = new HashSet<>();
            if (t.yesTopics()    != null) out.addAll(t.yesTopics());
            if (t.mostlyTopics() != null) out.addAll(t.mostlyTopics());
            return out;
        }).orElse(Set.of());
    }
}
