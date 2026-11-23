package fr.enimaloc.catapult.repository.chess;

import fr.enimaloc.catapult.domain.chess.ChessAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** The members' chess accounts. */
public interface ChessAccountRepository extends JpaRepository<ChessAccount, UUID> {
}
