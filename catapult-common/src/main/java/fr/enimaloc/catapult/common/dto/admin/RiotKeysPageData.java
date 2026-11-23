package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** The shared Riot API keys (masked) and whether the Riot source is enabled on this instance. */
public record RiotKeysPageData(List<KeyStatus> keys, boolean riotEnabled) {}
