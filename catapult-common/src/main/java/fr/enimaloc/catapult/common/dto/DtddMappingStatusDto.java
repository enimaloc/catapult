package fr.enimaloc.catapult.common.dto;

public record DtddMappingStatusDto(DtddMappingCurrentDto current, DtddMappingProposalDto myPendingProposal, boolean canValidateDirectly, String igdbId) {}
