package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.common.dto.channel.DtddMappingCurrentDto;

/**
 * The DoesTheDogDie mapping of the channel's game ({@code igdbId}) changed: the mapping now ({@code current}, null when
 * it has none). A live event tells how many proposals are pending on it ({@code pendingProposals}); the page's state
 * (see ChannelState) tells instead what its viewer sees: whether they have one pending ({@code proposed}) and may
 * validate it ({@code validatable}). Without {@code igdbId} (an older catapult-api), the mapping section is stale.
 */
public record DtddMappingChangedEvent(String username, String igdbId, DtddMappingCurrentDto current,
                                      Long pendingProposals, Boolean proposed, Boolean validatable)
        implements ChannelUpdatedEvent {

    /** A change that carries nothing but the channel: its dashboard reads the mapping again. */
    public DtddMappingChangedEvent(String username) {
        this(username, null, null, null, null, null);
    }
}
