package fr.enimaloc.catapult.service.enrichment.chess;

import java.time.Instant;

/**
 * A rating on one time class or variant. Chess.com gives the current rating, its deviation, the best one, the
 * record and the date of the last rated game; Lichess the rating, its deviation, whether it is provisional, the
 * progress over the last games and the number of games. What a site does not give is null.
 */
public record ChessRating(Integer rating, Integer deviation, Boolean provisional, Integer progress, Integer best,
                          Integer games, Integer win, Integer loss, Integer draw, Instant lastPlayed) {
}
