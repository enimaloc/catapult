package fr.enimaloc.catapult.repository.title;

import fr.enimaloc.catapult.domain.title.StreamTitleState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StreamTitleStateRepository extends JpaRepository<StreamTitleState, UUID> {
}
