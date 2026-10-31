package fr.enimaloc.catapult.common.dto.experiment;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;

import java.util.Map;
import java.util.UUID;

/** One page of an experiment's participants, plus how many each variant holds (every page, whatever the filter). */
public record ExperimentAssignmentsDto(AdminPage<ExperimentAssignmentDto> page, Map<UUID, Long> counts) {}
