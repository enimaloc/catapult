package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Keyed by the streamer's account id. */
public interface CustomBindingActivationRepository extends JpaRepository<CustomBindingActivation, UUID> {
}
