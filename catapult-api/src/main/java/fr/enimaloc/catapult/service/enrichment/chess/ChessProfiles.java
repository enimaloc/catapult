package fr.enimaloc.catapult.service.enrichment.chess;

/** The public profile pages of the chess sites. */
public final class ChessProfiles {

    private ChessProfiles() {
    }

    public static String chesscom(String username) {
        return "https://www.chess.com/member/" + username;
    }

    public static String lichess(String username) {
        return "https://lichess.org/@/" + username;
    }
}
