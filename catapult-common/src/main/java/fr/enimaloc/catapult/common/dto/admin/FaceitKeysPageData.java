package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** The shared FACEIT Data API keys (masked) and whether the FACEIT source is enabled on this instance. */
public record FaceitKeysPageData(List<KeyStatus> keys, boolean faceitEnabled) {}
