package fr.enimaloc.catapult.repository.osu;

import fr.enimaloc.catapult.domain.osu.OsuAppCredentials;
import org.springframework.data.jpa.repository.JpaRepository;

/** The osu! OAuth application credentials (one row at most). */
public interface OsuAppCredentialsRepository extends JpaRepository<OsuAppCredentials, Short> {
}
