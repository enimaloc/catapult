package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** The shared Supercell API keys of one game (masked) and whether the Supercell source is enabled. */
public record SupercellKeysPageData(List<KeyStatus> keys, boolean supercellEnabled) {}
