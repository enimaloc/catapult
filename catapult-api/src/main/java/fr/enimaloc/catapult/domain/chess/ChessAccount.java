package fr.enimaloc.catapult.domain.chess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * A member's chess accounts: the Chess.com name they declared with the {@code player_id} Chess.com proved for it
 * (stable when the name changes), and the Lichess account (its stable lowercase id and the name as written). Either
 * may be unset; the row exists only while it holds one of them. Nothing secret: both APIs are public.
 */
@Entity
@Table(name = "chess_account")
@Getter
@Setter
@ToString
public class ChessAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "chesscom_username")
    private String chesscomUsername;

    @Column(name = "chesscom_player_id")
    private Long chesscomPlayerId;

    @Column(name = "lichess_id")
    private String lichessId;

    @Column(name = "lichess_username")
    private String lichessUsername;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock: a write made from a stale read fails instead of bringing back what was removed meanwhile.
     * Null until first saved, so that a new row is persisted rather than merged.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public ChessAccount() {
    }

    public ChessAccount(UUID userId) {
        this.userId = userId;
    }

    /** Whether the row still holds an account on either site. */
    public boolean holdsAny() {
        return chesscomUsername != null || lichessId != null;
    }
}
