package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

public record RetroAchievementsKeysPageData(List<KeyStatus> keys, boolean retroachievementsEnabled) {}
