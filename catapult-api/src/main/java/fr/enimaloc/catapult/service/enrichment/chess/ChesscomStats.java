package fr.enimaloc.catapult.service.enrichment.chess;

import java.util.Map;

/**
 * A Chess.com player's ratings by time class ({@code daily}, {@code daily960}, {@code rapid}, {@code blitz},
 * {@code bullet}: only those played) and the FIDE rating they declared (null when none).
 */
public record ChesscomStats(Map<String, ChessRating> modes, Integer fide) {

    public ChesscomStats {
        modes = Map.copyOf(modes);
    }
}
