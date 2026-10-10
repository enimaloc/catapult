package fr.enimaloc.catapult.common.dto.channel;

import java.util.UUID;

public record DtddMappingProposalDto(UUID id, Long proposedDtddId, String reason) {}
