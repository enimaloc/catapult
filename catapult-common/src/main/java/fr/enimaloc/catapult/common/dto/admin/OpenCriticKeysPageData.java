package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

public record OpenCriticKeysPageData(List<KeyStatus> keys, boolean opencriticEnabled) {}
