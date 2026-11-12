package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.getter.dtdd.DtddApiClient.DtddSeverity;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnBooleanProperty("dtdd.enabled")
@RequiredArgsConstructor
public class DtddSignalService {

    private final DtddService dtddService;

    /**
     * A game's DTDD topics marked yes or mostly, with each one's severity by its name: {@code severities} null when
     * they weren't cached (fetched before they were kept), a topic without levels absent from it.
     */
    public record TopicSignals(Set<String> topics, Map<String, DtddSeverity> severities) {
        public static final TopicSignals NONE = new TopicSignals(Set.of(), Map.of());
    }

    /** Returns the union of DTDD topics marked yes and mostly for the given game. */
    public Set<String> getYesMostlyTopics(String igdbId, String name) {
        return getYesMostlySignals(igdbId, name).topics();
    }

    /** As {@link #getYesMostlyTopics}, with the topics' severities. */
    public TopicSignals getYesMostlySignals(String igdbId, String name) {
        return dtddService.getTopics(igdbId, name).map(t -> {
            Set<String> out = new HashSet<>();
            if (t.yesTopics()    != null) out.addAll(t.yesTopics());
            if (t.mostlyTopics() != null) out.addAll(t.mostlyTopics());
            return new TopicSignals(out, t.severities());
        }).orElse(TopicSignals.NONE);
    }
}
