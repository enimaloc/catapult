package fr.enimaloc.catapult.common.dto.channel;

public record DtddMappingStatusDto(DtddMappingCurrentDto current, DtddMappingProposalDto myPendingProposal, boolean canValidateDirectly, String igdbId) {}
