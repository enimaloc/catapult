package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/** Keyed by the streamer's account id. */
public interface CustomBindingActivationRepository extends JpaRepository<CustomBindingActivation, UUID> {

    @Query("select a.userId from CustomBindingActivation a where a.origin = :origin")
    List<UUID> findUserIdsByOrigin(CustomBindingActivation.Origin origin);
}
