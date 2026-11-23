package fr.enimaloc.catapult.common.dto.channel;

/**
 * The owner's chess accounts, for the settings card: the Chess.com and the Lichess names they declared (each null
 * when not declared). Public names only: no internal id.
 */
public record ChessData(
        String chesscomUsername,
        String lichessUsername
) {}
