package fr.enimaloc.catapult.service.enrichment.chess;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The two chess sites a member can declare an account on; each has its own host and its own limiter slot. */
public enum ChessSite {
    @JsonProperty("chesscom") CHESSCOM,
    @JsonProperty("lichess") LICHESS
}
