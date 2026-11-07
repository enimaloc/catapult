package fr.enimaloc.catapult.common.dto.channel;

/**
 * The DTDD mapping of the game a channel plays, as one viewer sees it. {@code viewerIsAdmin}: whether they may
 * validate a mapping at all, for the dashboard to offer it once {@code canValidateDirectly} becomes true; null from
 * a catapult-api that predates it.
 */
public record DtddMappingStatusDto(DtddMappingCurrentDto current, DtddMappingProposalDto myPendingProposal,
                                   boolean canValidateDirectly, String igdbId, Boolean viewerIsAdmin) {

    public DtddMappingStatusDto(DtddMappingCurrentDto current, DtddMappingProposalDto myPendingProposal,
                                boolean canValidateDirectly, String igdbId) {
        this(current, myPendingProposal, canValidateDirectly, igdbId, null);
    }
}
