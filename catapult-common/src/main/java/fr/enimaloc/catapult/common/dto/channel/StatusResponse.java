package fr.enimaloc.catapult.common.dto.channel;

public record StatusResponse(DtddMappingCurrentDto current, DtddMappingProposalDto myPendingProposal, boolean canValidateDirectly) {}
