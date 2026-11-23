package fr.enimaloc.catapult.service.enrichment.chess;

import java.time.Instant;

/**
 * A Chess.com daily game in progress, seen from the player: their colour, the opponent's name, whether it is their
 * move and by when it must be made.
 */
public record ChesscomDailyGame(String url, String color, String opponent, boolean yourTurn, Instant moveBy,
                                String rules) {
}
