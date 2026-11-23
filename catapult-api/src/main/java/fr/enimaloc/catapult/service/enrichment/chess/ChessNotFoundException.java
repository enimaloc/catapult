package fr.enimaloc.catapult.service.enrichment.chess;

/**
 * The site answered with its recognised "not found" (Chess.com's 404 JSON {@code {"code":0,"message":"User … not
 * found."}}, Lichess's 404 JSON {@code {"error":"Not found"}}), or Lichess says the account is closed.
 */
public class ChessNotFoundException extends RuntimeException {

    public ChessNotFoundException(String message) {
        super(message);
    }
}
