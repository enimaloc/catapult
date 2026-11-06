package fr.enimaloc.catapult.common.dto;

import java.time.Instant;

/**
 * Whether this site is the active one, why, and since when.
 * When the site guard is not configured the site is always active.
 */
public record SiteStatusDto(boolean configured, boolean active, String reason, Instant since) {}
