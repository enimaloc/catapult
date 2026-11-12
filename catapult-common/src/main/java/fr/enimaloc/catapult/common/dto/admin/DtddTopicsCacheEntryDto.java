package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A DTDD title's cached topics: {@code name} the title's, when its game is cached too (else null); {@code stale}
 * past the cache's lifetime (the next read refreshes it); {@code severities} each topic's by its name, null when
 * fetched before they were kept (a topic without levels absent).
 */
public record DtddTopicsCacheEntryDto(long dtddId, String name, List<String> yesTopics, List<String> noTopics,
                                      List<String> mostlyTopics, Instant fetchedAt, boolean stale,
                                      Map<String, DtddTopicSeverityDto> severities) {

    /** Topics without their severities. */
    public DtddTopicsCacheEntryDto(long dtddId, String name, List<String> yesTopics, List<String> noTopics,
                                   List<String> mostlyTopics, Instant fetchedAt, boolean stale) {
        this(dtddId, name, yesTopics, noTopics, mostlyTopics, fetchedAt, stale, null);
    }
}
