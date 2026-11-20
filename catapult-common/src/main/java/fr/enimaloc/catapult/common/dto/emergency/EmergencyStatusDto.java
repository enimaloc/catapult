package fr.enimaloc.catapult.common.dto.emergency;

import java.util.List;

/** What the public status banner shows: the active global stops only. */
public record EmergencyStatusDto(List<EmergencyStopDto> globalStops) {}
