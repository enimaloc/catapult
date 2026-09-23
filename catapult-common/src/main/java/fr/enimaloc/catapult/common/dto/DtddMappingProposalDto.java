package fr.enimaloc.catapult.common.dto;

import java.util.UUID;

public record DtddMappingProposalDto(UUID id, Long proposedDtddId, String reason) {}
