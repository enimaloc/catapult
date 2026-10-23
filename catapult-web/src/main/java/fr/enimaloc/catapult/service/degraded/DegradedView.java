package fr.enimaloc.catapult.service.degraded;

/**
 * What the site shows of catapult-api's degraded mode: the intervals it applies and where to read about it
 * ({@code link}: the Instatus incident when there is one, else the status page; null without a page).
 * Serialized as is for the browser.
 */
public record DegradedView(boolean degraded, Integer gameDetectionIntervalSeconds,
                           Integer minecraftFriendsSyncMinutes, String link, boolean incident) {
    public static final DegradedView NONE = new DegradedView(false, null, null, null, false);
}
