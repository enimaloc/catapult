package fr.enimaloc.catapult.service.enrichment.chess;

import java.time.Instant;
import java.util.Map;

/**
 * A Lichess account: its stable lowercase {@code id}, the name as written, the ratings of the variants played
 * (by Lichess key: {@code bullet}, {@code blitz}, {@code rapid}, {@code classical}, {@code correspondence},
 * {@code chess960}...), the game counts, and the link of the game being played now (null when none).
 */
public record LichessUser(String id, String username, String title, String url, Map<String, ChessRating> perfs,
                          Integer games, Integer win, Integer loss, Integer draw, String playingUrl,
                          Instant seenAt) {

    public LichessUser {
        perfs = Map.copyOf(perfs);
    }
}
