package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.UUID;

/** One recorded exchange as the list shows it: no headers, no bodies. {@code direction} is IN or OUT. */
public record DebugExchangeSummaryDto(UUID id, Instant occurredAt, String direction, String service,
                                      String operation, String method, String url, Integer status, boolean error,
                                      long durationMs, boolean filtered) {
}
