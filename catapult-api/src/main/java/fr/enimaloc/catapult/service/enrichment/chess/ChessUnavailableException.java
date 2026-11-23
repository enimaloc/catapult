package fr.enimaloc.catapult.service.enrichment.chess;

/**
 * Chess.com or Lichess cannot be read right now (down, refusing, rate limited, redirecting, garbled or wrong-shaped
 * answer): never "nothing there". The message never carries a response body.
 */
public class ChessUnavailableException extends RuntimeException {

    public ChessUnavailableException(String message) {
        super(message);
    }
}
