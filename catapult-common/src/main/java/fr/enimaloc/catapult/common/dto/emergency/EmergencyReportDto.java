package fr.enimaloc.catapult.common.dto.emergency;

import java.time.Instant;
import java.util.UUID;

/** A viewer's report, as the admin reads it: {@code vodUrl} is null without a resolved VOD. */
public record EmergencyReportDto(UUID id, String channelLogin, String provider, String reportedByLogin,
                                 Instant createdAt, boolean withdrawn, String vodUrl, String contextJson) {}
