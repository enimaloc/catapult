package fr.enimaloc.catapult.common.dto;

import java.util.List;

public record DtddKeysPageData(List<KeyStatus> keys, boolean dtddEnabled) {}
