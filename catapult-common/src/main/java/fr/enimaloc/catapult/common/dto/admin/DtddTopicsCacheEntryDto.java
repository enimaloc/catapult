package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.List;

/**
 * A DTDD title's cached topics: {@code name} the title's, when its game is cached too (else null); {@code stale}
 * past the cache's lifetime (the next read refreshes it).
 */
public record DtddTopicsCacheEntryDto(long dtddId, String name, List<String> yesTopics, List<String> noTopics,
                                      List<String> mostlyTopics, Instant fetchedAt, boolean stale) {}
