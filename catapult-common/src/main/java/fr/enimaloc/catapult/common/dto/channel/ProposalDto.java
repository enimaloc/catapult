package fr.enimaloc.catapult.common.dto.channel;

import java.util.UUID;

public record ProposalDto(UUID id, Long proposedDtddId, String reason) {}
