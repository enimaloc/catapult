package fr.enimaloc.catapult.service.enrichment.chess;

/** A Chess.com profile: the stable {@code player_id}, the (lowercase) name the API knows, the profile page. */
public record ChesscomPlayer(long playerId, String username, String url, String title, String avatarUrl) {
}
