package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.UUID;

/** One recorded exchange in full: the summary's fields plus headers, bodies, the transport error and the trace id. */
public record DebugExchangeDto(UUID id, Instant occurredAt, String direction, String service, String operation,
                               String method, String url, Integer status, boolean error, long durationMs,
                               boolean filtered, String requestHeaders, String requestBody,
                               String responseHeaders, String responseBody, String errorMessage, String traceId) {
}
