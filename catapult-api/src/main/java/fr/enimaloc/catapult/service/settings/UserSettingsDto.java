package fr.enimaloc.catapult.service.settings;

import fr.enimaloc.catapult.domain.UserSettings;

import java.util.Set;

/**
 * Projection of {@link UserSettings} used as the {@code settings.updated} WS event payload.
 * Contains four nested records, one per settings panel, so the channel-page JS can
 * reconstruct each panel's state from the event data without refetching HTML fragments.
 */
public record UserSettingsDto(
        Ccl ccl,
        Tw tw,
        NoGame noGame,
        IncompleteFallback incompleteFallback
) {

    public record Ccl(
            boolean enabled,
            Set<String> blockedCcls
    ) {}

    public record Tw(
            boolean enabled,
            Set<String> blockedTws
    ) {}

    public record NoGame(
            String twitchGameId,
            String twitchGameName,
            Set<String> ccls,
            boolean applyOnStreamStart,
            boolean applyOnNoGame,
            boolean applyOnStreamEnd
    ) {}

    public record IncompleteFallback(
            String twitchGameId,
            String twitchGameName,
            Set<String> ccls
    ) {}

    /**
     * Projects a {@link UserSettings} entity into this DTO.
     * All collections are defensively copied so the returned record is detached
     * from any JPA-managed collection.
     */
    public static UserSettingsDto from(UserSettings s) {
        return new UserSettingsDto(
                new Ccl(s.isCclFeatureEnabled(), Set.copyOf(s.getBlockedCcls())),
                new Tw(s.isTwFeatureEnabled(), Set.copyOf(s.getBlockedTws())),
                new NoGame(
                        s.getNoGameTwitchGameId(),
                        s.getNoGameTwitchGameName(),
                        Set.copyOf(s.getNoGameCcls()),
                        s.isApplyDefaultOnStreamStart(),
                        s.isApplyDefaultOnNoGame(),
                        s.isApplyDefaultOnStreamEnd()
                ),
                new IncompleteFallback(
                        s.getIncompleteFallbackTwitchGameId(),
                        s.getIncompleteFallbackTwitchGameName(),
                        Set.copyOf(s.getIncompleteFallbackCcls())
                )
        );
    }
}
