package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

public record SteamKeysPageData(List<SteamKeyStatus> keys, boolean steamEnabled) {}
