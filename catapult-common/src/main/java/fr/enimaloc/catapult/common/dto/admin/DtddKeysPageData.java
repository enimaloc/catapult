package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

public record DtddKeysPageData(List<KeyStatus> keys, boolean dtddEnabled) {}
