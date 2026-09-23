package fr.enimaloc.catapult.common.dto;

import java.util.UUID;

public record ProposalDto(UUID id, Long proposedDtddId, String reason) {}
