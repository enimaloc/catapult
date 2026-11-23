package fr.enimaloc.catapult.service.enrichment.supercell;

import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.SupercellPlayer;

/**
 * The Supercell game APIs ({@code GET /v1/players/{tag}} of each game), with a key of that game's shared pool. Every
 * request is charged to the {@link SupercellRateLimiter}. Failures are {@link SupercellUnavailableException}; only
 * Supercell's JSON {@code notFound} 404 is {@link SupercellNotFoundException}.
 */
public interface SupercellClient {

    /** The player of {@code game} whose normalized tag ({@code #2PP}) is {@code tag}. */
    SupercellPlayer player(SupercellGame game, String tag);
}
