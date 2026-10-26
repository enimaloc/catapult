package fr.enimaloc.catapult.common.dto.channel;

/** A proposal's body; the game it is about is the path's {@code igdbId}. */
public record DtddProposalRequest(Long dtddId, String reason) {}
