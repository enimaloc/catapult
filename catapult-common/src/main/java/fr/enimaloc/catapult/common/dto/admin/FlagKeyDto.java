package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** A targeting tag key some member carries, with the distinct non-null values it takes (sorted; empty for a bare tag). */
public record FlagKeyDto(String key, List<String> values) {}
