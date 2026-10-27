package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** IANA's Well-Known URIs registry, from iana.org ("LIVE") or the copy bundled with catapult-api ("SNAPSHOT"). */
public record IanaRegistryDto(List<IanaWellKnownDto> entries, String source) {}
