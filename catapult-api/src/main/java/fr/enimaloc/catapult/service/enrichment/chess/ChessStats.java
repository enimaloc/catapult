package fr.enimaloc.catapult.service.enrichment.chess;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A member's chess stats, the one projection of the aggregate and of the detail route: their Chess.com and their
 * Lichess account, each null when not declared. {@code status} is {@code ok} when one of the two was read, else
 * the status of the first declared one. {@code account} is the name shown (the Chess.com one, else the Lichess one).
 * {@code chesscom.games} (every daily game in progress) is filled in the detail only (absent from the aggregate).
 * {@code more} links to the detail route; the service leaves it null, the API sets it ({@link #withMore}).
 */
public record ChessStats(Status status, String game, String account, Chesscom chesscom, Lichess lichess,
                         String more) {

    /** The {@code game} of every chess stats object. */
    public static final String GAME = "chess";

    /** These stats with the link to their detail route. */
    public ChessStats withMore(String link) {
        return new ChessStats(status, game, account, chesscom, lichess, link);
    }

    /**
     * The Chess.com account: ratings by time class ({@code daily}, {@code daily960}, {@code rapid}, {@code blitz},
     * {@code bullet}, only those played), the FIDE rating declared, how many daily games are in progress and in
     * how many it is the member's move. Everything but {@code status}, {@code username} and {@code url} is null
     * unless the status is ok; {@code dailyGames} and {@code yourTurn} are null when the games could not be read.
     */
    public record Chesscom(Status status, String username, String url, Map<String, ChessRating> ratings,
                           Integer fide, Integer dailyGames, Integer yourTurn,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<ChesscomDailyGame> games) {
    }

    /**
     * The Lichess account: ratings by variant (only those played), the game counts, whether a game is being played
     * now and its link, when the member was last seen. Everything but {@code status}, {@code username} and
     * {@code url} is null unless the status is ok.
     */
    public record Lichess(Status status, String username, String url, String title, Map<String, ChessRating> ratings,
                          Integer games, Integer win, Integer loss, Integer draw, Boolean playing, String playingUrl,
                          Instant seenAt) {
    }
}
