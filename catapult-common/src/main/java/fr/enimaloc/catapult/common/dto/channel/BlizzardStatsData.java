package fr.enimaloc.catapult.common.dto.channel;

import java.time.Instant;
import java.util.List;

/**
 * The owner's Blizzard settings, for the settings card: whether Battle.net is linked, the region of the account,
 * whether the World of Warcraft characters were imported and which of them is the main one
 * ({@code wowMainKey}, a {@code characters} key), when they were imported ({@code wowImportedAt}, null when not),
 * and whether the Blizzard API is set up on this instance
 * ({@code available}). Names only, never a token.
 */
public record BlizzardStatsData(
        boolean linked,
        String region,
        boolean wowImported,
        List<WowCharacterOption> characters,
        String wowMainKey,
        boolean available,
        Instant wowImportedAt
) {

    public BlizzardStatsData(boolean linked, String region, boolean wowImported, List<WowCharacterOption> characters,
                             String wowMainKey, boolean available) {
        this(linked, region, wowImported, characters, wowMainKey, available, null);
    }

    public BlizzardStatsData {
        characters = characters == null ? List.of() : List.copyOf(characters);
    }

    /** One character to pick as main: {@code key} is {@code <region>:<id>}, {@code label} is for display. */
    public record WowCharacterOption(String key, String label) {}
}
