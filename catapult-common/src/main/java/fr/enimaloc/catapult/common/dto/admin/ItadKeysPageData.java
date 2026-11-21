package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

public record ItadKeysPageData(List<KeyStatus> keys, boolean itadEnabled) {}
