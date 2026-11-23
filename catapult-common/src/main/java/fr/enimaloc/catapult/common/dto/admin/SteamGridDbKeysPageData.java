package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

public record SteamGridDbKeysPageData(List<KeyStatus> keys, boolean steamgriddbEnabled) {}
