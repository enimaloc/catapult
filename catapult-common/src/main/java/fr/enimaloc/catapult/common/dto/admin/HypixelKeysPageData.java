package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** The shared Hypixel API keys (masked) and whether the Hypixel source is enabled on this instance. */
public record HypixelKeysPageData(List<KeyStatus> keys, boolean hypixelEnabled) {}
