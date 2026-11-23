package fr.enimaloc.catapult.service.enrichment.chess;

import java.util.List;

/**
 * The public Chess.com and Lichess APIs (no key). Each method throws {@link ChessNotFoundException} on the site's
 * recognised "not found" and {@link ChessUnavailableException} on anything else that is not a well-formed answer.
 */
public interface ChessClient {

    /** The Chess.com profile of {@code username} (any case); a closed one ({@code status: closed…}) is not found. */
    ChesscomPlayer chesscomPlayer(String username);

    /** The Chess.com ratings of {@code username}. */
    ChesscomStats chesscomStats(String username);

    /** The Chess.com daily games {@code username} has in progress (none: an empty list). */
    List<ChesscomDailyGame> chesscomGames(String username);

    /**
     * The Lichess account {@code username} (any case); a closed one, or one marked for breaking the terms, is not
     * found.
     */
    LichessUser lichessUser(String username);
}
