package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.GameBinding;

import java.util.Set;

/**
 * Projection of {@link GameBinding} used as the {@code binding.upserted} WS event payload.
 * Contains only the fields needed by the channel-page JS row renderer — heavy lazy
 * collections (TWs, sourceId, timestamps, etc.) are omitted intentionally.
 */
public record BindingDto(
        String id,
        String sourceType,
        String sourceName,
        String twitchGameName,
        Set<String> ccls,
        String status,
        boolean cclEnabled,
        boolean ignored
) {

    /**
     * Projects a {@link GameBinding} entity into this DTO.
     * The CCL set is defensively copied so the returned record is detached
     * from any JPA-managed collection.
     */
    public static BindingDto from(GameBinding b) {
        return new BindingDto(
                b.getId().toString(),
                b.getSourceType().name(),
                b.getSourceName(),
                b.getTwitchGameName(),
                Set.copyOf(b.getCcls()),
                b.getStatus().name(),
                b.isCclEnabled(),
                b.isIgnored()
        );
    }
}
