package fr.enimaloc.catapult.service.emergency;

import java.time.Instant;

/** What is frozen when a report is made: the JSON snapshot, and the live stream (null when offline). */
public record ReportContext(String json, String streamId, Instant streamStartedAt) {
}
