package fr.enimaloc.catapult.common.dto;

public record StatusResponse(MappingDto current, ProposalDto myPendingProposal, boolean canValidateDirectly) {}
