package fr.enimaloc.catapult.common.dto.channel;

/**
 * The owner's chess accounts, for the settings card: the Chess.com and the Lichess names they declared (each null
 * when not declared) and their profile pages. Public names only: no internal id.
 */
public record ChessData(
        String chesscomUsername,
        String chesscomProfileUrl,
        String lichessUsername,
        String lichessProfileUrl
) {}
