package fr.enimaloc.catapult.common.dto.channel;

public record StatusResponse(MappingDto current, ProposalDto myPendingProposal, boolean canValidateDirectly) {}
